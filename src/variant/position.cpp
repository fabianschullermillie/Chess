//
// Board state, FEN conversion, making and unmaking moves, attack detection.
//

#include "position.h"

#include <cctype>
#include <sstream>

namespace variant {

Position::Position(const Variant &v) : v(&v) {
    history.reserve(256);
}

void Position::put(Piece p, Square s) {
    board[s] = p;
    hash ^= v->piece_key(p, s);
    ++counts[p.color()][p.type()];
    if (v->kind(p.type()).royal) royal[p.color()] = s;
}

Piece Position::remove(Square s) {
    Piece p = board[s];
    board[s] = NO_PIECE;
    hash ^= v->piece_key(p, s);
    --counts[p.color()][p.type()];
    if (royal[p.color()] == s) royal[p.color()] = NO_SQUARE;
    return p;
}

void Position::set_castling(unsigned rights) {
    hash ^= v->castling_key(castling) ^ v->castling_key(rights);
    castling = rights;
}

void Position::set_en_passant(Square s) {
    if (ep_square != NO_SQUARE) hash ^= v->en_passant_key(ep_square);
    ep_square = s;
    if (ep_square != NO_SQUARE) hash ^= v->en_passant_key(ep_square);
}

int Position::set_fen(const std::string &fen) {
    const Geometry &g = geometry();
    board.fill(NO_PIECE);
    for (auto &c: counts)
        for (int &n: c) n = 0;
    royal = {NO_SQUARE, NO_SQUARE};
    history.clear();
    hash = 0;
    castling = 0;
    ep_square = NO_SQUARE;

    std::istringstream ss(fen);
    std::string placement, stm = "w", rights = "-", ep = "-";
    int halfmove = 0;
    ss >> placement >> stm >> rights >> ep >> halfmove;

    int file = 0, rank = g.ranks - 1;
    for (size_t i = 0; i < placement.size(); ++i) {
        char c = placement[i];
        if (c == '/') {
            file = 0;
            --rank;
        } else if (std::isdigit(c)) {
            int n = c - '0';
            while (i + 1 < placement.size() && std::isdigit(placement[i + 1])) n = n * 10 + (placement[++i] - '0');
            file += n;
        } else {
            PieceType t = v->type_for_letter(c);
            if (t >= 0 && file < g.files && rank >= 0)
                put(Piece(std::isupper(c) ? WHITE : BLACK, t), g.square(file, rank));
            ++file;
        }
    }

    side = stm == "b" ? BLACK : WHITE;
    if (side == BLACK) hash ^= v->side_key();

    unsigned r = 0;
    for (size_t i = 0; i < v->castling().size(); ++i)
        if (rights.find(v->castling()[i].rule.fen_letter) != std::string::npos) r |= 1u << i;
    set_castling(r);

    size_t len;
    Square s = g.parse(ep, 0, len);
    // Only remember the en passant square if a capture is possible (keeps repetition detection exact)
    if (s != NO_SQUARE) {
        for (Square from: v->pawn_capture_sources(side, s)) {
            Piece p = board[from];
            if (!p.empty() && p.color() == side && v->kind(p.type()).pawn) {
                set_en_passant(s);
                break;
            }
        }
    }
    return halfmove;
}

std::string Position::fen(int halfmove, int fullmove) const {
    const Geometry &g = geometry();
    std::string s;
    for (int rank = g.ranks - 1; rank >= 0; --rank) {
        int empty = 0;
        for (int file = 0; file < g.files; ++file) {
            Piece p = board[g.square(file, rank)];
            if (p.empty()) {
                ++empty;
                continue;
            }
            if (empty) s += std::to_string(empty);
            empty = 0;
            s += v->letter(p);
        }
        if (empty) s += std::to_string(empty);
        if (rank > 0) s += '/';
    }
    s += side == WHITE ? " w " : " b ";

    std::string rights;
    for (size_t i = 0; i < v->castling().size(); ++i)
        if (castling & (1u << i)) rights += v->castling()[i].rule.fen_letter;
    s += rights.empty() ? "-" : rights;

    s += ' ';
    s += ep_square == NO_SQUARE ? "-" : g.name(ep_square);
    s += " " + std::to_string(halfmove) + " " + std::to_string(fullmove);
    return s;
}

void Position::play(Move m) {
    const Color us = side;
    Undo u{m, board[m.from], NO_PIECE, NO_SQUARE, castling, ep_square, hash};
    set_en_passant(NO_SQUARE);

    if (m.flags & Move::CASTLE) {
        for (const auto &c: v->castling()) {
            if (c.rule.color != us || c.rule.king_from != m.from || c.rule.king_to != m.to) continue;
            Piece king = remove(c.rule.king_from);
            Piece rook = remove(c.rule.rook_from);
            put(king, c.rule.king_to);
            put(rook, c.rule.rook_to);
            break;
        }
    } else {
        if (m.flags & Move::EN_PASSANT) {
            u.captured_on = geometry().step(m.to, v->pawn_forward(~us));
            u.captured = remove(u.captured_on);
        } else if (!board[m.to].empty()) {
            u.captured_on = m.to;
            u.captured = remove(m.to);
        }
        Piece moving = remove(m.from);
        put(m.is_promotion() ? Piece(us, m.promotion_type()) : moving, m.to);

        if (m.flags & Move::DOUBLE_PUSH) {
            Square skipped = geometry().step(m.from, v->pawn_forward(us));
            for (Square s: v->pawn_capture_sources(~us, skipped)) {
                Piece p = board[s];
                if (!p.empty() && p.color() == ~us && v->kind(p.type()).pawn) {
                    set_en_passant(skipped);
                    break;
                }
            }
        }
    }

    // Moving the king or a rook (or capturing a rook) loses the corresponding castling rights
    if (castling) {
        unsigned rights = castling;
        for (size_t i = 0; i < v->castling().size(); ++i) {
            const CastlingRule &r = v->castling()[i].rule;
            if (m.from == r.king_from || m.from == r.rook_from || m.to == r.king_from || m.to == r.rook_from)
                rights &= ~(1u << i);
        }
        set_castling(rights);
    }

    side = ~side;
    hash ^= v->side_key();
    history.push_back(u);
}

void Position::undo() {
    const Undo u = history.back();
    history.pop_back();
    const Move m = u.move;
    side = ~side;
    const Color us = side;

    if (m.flags & Move::CASTLE) {
        for (const auto &c: v->castling()) {
            if (c.rule.color != us || c.rule.king_from != m.from || c.rule.king_to != m.to) continue;
            Piece king = remove(c.rule.king_to);
            Piece rook = remove(c.rule.rook_to);
            put(king, c.rule.king_from);
            put(rook, c.rule.rook_from);
            break;
        }
    } else {
        remove(m.to);
        put(u.moved, m.from);
        if (!u.captured.empty()) put(u.captured, u.captured_on);
    }

    castling = u.castling;
    ep_square = u.ep_square;
    hash = u.hash;
}

void Position::play_null() {
    history.push_back(Undo{Move{}, NO_PIECE, NO_PIECE, NO_SQUARE, castling, ep_square, hash});
    set_en_passant(NO_SQUARE);
    side = ~side;
    hash ^= v->side_key();
}

void Position::undo_null() {
    const Undo u = history.back();
    history.pop_back();
    side = ~side;
    ep_square = u.ep_square;
    hash = u.hash;
}

bool Position::is_attacked(Square s, Color by) const {
    for (PieceType t = 0; t < v->num_piece_types(); ++t) {
        if (!counts[by][t]) continue;
        const Piece attacker(by, t);
        const PieceKind &k = v->kind(t);

        if (k.pawn) {
            for (Square from: v->pawn_capture_sources(by, s))
                if (board[from] == attacker) return true;
            continue;
        }
        for (Square from: v->leap_sources(t, s))
            if (board[from] == attacker) return true;
        for (const Ray &ray: v->slide_sources(t, s)) {
            for (Square from: ray) {
                if (board[from].empty()) continue;
                if (board[from] == attacker) return true;
                break;
            }
        }
    }
    return false;
}

std::string Position::move_to_uci(Move m) const {
    if (m.is_null()) return "0000";
    std::string s = geometry().name(m.from) + geometry().name(m.to);
    if (m.is_promotion()) s += char(std::tolower(v->kind(m.promotion_type()).letter));
    return s;
}

Move Position::parse_uci(const std::string &uci) {
    Move moves[MAX_MOVES];
    Move *end = generate_legal_moves(moves);
    for (Move *m = moves; m != end; ++m)
        if (move_to_uci(*m) == uci) return *m;
    return Move{};
}

std::ostream &operator<<(std::ostream &os, const Position &p) {
    const Geometry &g = p.geometry();
    for (int rank = g.ranks - 1; rank >= 0; --rank) {
        os << rank + 1 << (rank + 1 < 10 ? "  " : " ");
        for (int file = 0; file < g.files; ++file) {
            Piece pc = p.at(g.square(file, rank));
            os << (pc.empty() ? '.' : p.variant().letter(pc)) << ' ';
        }
        os << '\n';
    }
    os << "   ";
    for (int file = 0; file < g.files; ++file) os << char('a' + file) << ' ';
    os << "\n" << (p.turn() == WHITE ? "White" : "Black") << " to move\n";
    return os;
}

} // namespace variant
