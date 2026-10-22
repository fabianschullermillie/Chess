//
// Move generation: pseudo-legal moves from the piece descriptions, then a legality filter that
// plays each move and checks whether the own royal piece is attacked.
//

#include <bitset>

#include "position.h"

namespace variant {

Move *Position::add_pawn_moves(Square from, Move *list) const {
    const Color us = side;
    const Geometry &g = geometry();
    const PawnRules &rules = v->rules().pawns;

    // Writes a move, expanded into one move per promotion piece when it reaches the last rank
    auto emit = [&](Square to, uint8_t flags) {
        if (v->is_promotion_square(us, to)) {
            for (PieceType t: rules.promotion_types) *list++ = Move::make(from, to, flags, t + 1);
        } else {
            *list++ = Move::make(from, to, flags);
        }
    };

    // Pushes
    Square one = g.step(from, v->pawn_forward(us));
    if (one != NO_SQUARE && board[one].empty()) {
        emit(one, Move::QUIET);
        Square two = g.step(one, v->pawn_forward(us));
        if (g.rank_of(from) == rules.double_step_rank[us] && two != NO_SQUARE && board[two].empty())
            emit(two, Move::DOUBLE_PUSH);
    }

    // Captures
    for (Square to: v->pawn_captures(us, from)) {
        if (!board[to].empty() && board[to].color() != us) emit(to, Move::CAPTURE);
        else if (to == ep_square) emit(to, Move::CAPTURE | Move::EN_PASSANT);
    }
    return list;
}

Move *Position::add_piece_moves(Square from, Move *list) const {
    const Color us = side;
    const PieceType t = board[from].type();
    // On a wrapping board a slider can reach a square along two different rays
    std::bitset<MAX_SQUARES> seen;

    auto try_square = [&](Square to) {
        if (seen[to]) return;
        seen[to] = true;
        if (board[to].empty()) *list++ = Move::make(from, to);
        else if (board[to].color() != us) *list++ = Move::make(from, to, Move::CAPTURE);
    };

    for (Square to: v->leaps(t, from)) {
        if (board[to].empty() || board[to].color() != us) try_square(to);
    }
    for (const Ray &ray: v->slides(t, from)) {
        for (Square to: ray) {
            if (board[to].empty()) {
                try_square(to);
                continue;
            }
            if (board[to].color() != us) try_square(to);
            break;
        }
    }
    return list;
}

Move *Position::add_castling_moves(Move *list) const {
    for (size_t i = 0; i < v->castling().size(); ++i) {
        const Variant::Castling &c = v->castling()[i];
        if (c.rule.color != side || !(castling & (1u << i))) continue;
        if (royal[side] != c.rule.king_from) continue;
        Piece rook = board[c.rule.rook_from];
        if (rook.empty() || rook.color() != side) continue;

        bool ok = true;
        for (Square s: c.must_be_empty) ok = ok && board[s].empty();
        for (Square s: c.must_be_safe) ok = ok && !is_attacked(s, ~side);
        if (ok) *list++ = Move::make(c.rule.king_from, c.rule.king_to, Move::CASTLE);
    }
    return list;
}

Move *Position::generate_pseudo_legal_moves(Move *list) const {
    for (Square s = 0; s < v->num_squares(); ++s) {
        Piece p = board[s];
        if (p.empty() || p.color() != side) continue;
        list = v->kind(p.type()).pawn ? add_pawn_moves(s, list) : add_piece_moves(s, list);
    }
    return add_castling_moves(list);
}

Move *Position::generate_legal_moves(Move *list) {
    Move pseudo[MAX_MOVES];
    Move *end = generate_pseudo_legal_moves(pseudo);
    const Color us = side;
    for (Move *m = pseudo; m != end; ++m) {
        play(*m);
        if (!in_check(us)) *list++ = *m;
        undo();
    }
    return list;
}

} // namespace variant
