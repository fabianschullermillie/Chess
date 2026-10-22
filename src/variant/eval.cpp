//
// A small hand-crafted evaluation that only relies on the variant description:
//   material, mobility, pawn advancement / passed pawns, king attack and king shelter,
//   and king proximity in won endgames.
// Midgame and endgame terms are blended by the amount of material left on the board.
//

#include "eval.h"

#include <algorithm>
#include <bitset>
#include <cstdlib>

namespace variant {

namespace {

struct Score {
    int mg = 0, eg = 0;
    void add(int m, int e) { mg += m; eg += e; }
    void add(int both) { mg += both; eg += both; }
};

// Material (excluding pawns and royals) at which the game counts as a full midgame
constexpr int MIDGAME_MATERIAL = 6400;

bool is_minor(const PieceKind &k) { return !k.pawn && !k.royal && k.value <= 350; }

// Squares around the royal piece of color c
std::bitset<MAX_SQUARES> king_zone(const Position &p, Color c) {
    std::bitset<MAX_SQUARES> zone;
    Square k = p.royal_square(c);
    if (k == NO_SQUARE) return zone;
    zone[k] = true;
    const Geometry &g = p.geometry();
    for (int df = -1; df <= 1; ++df)
        for (int dr = -1; dr <= 1; ++dr) {
            Square s = g.step(k, {df, dr});
            if (s != NO_SQUARE) zone[s] = true;
        }
    return zone;
}

// No enemy pawn in front of the pawn on its own or a neighbouring file
bool is_passed(const Position &p, Square s, Color us) {
    const Geometry &g = p.geometry();
    const Variant &v = p.variant();
    const int promo = v.rules().pawns.promotion_rank[us];
    const int dir = us == WHITE ? 1 : -1;
    for (int df = -1; df <= 1; ++df) {
        Square file_start = g.step(s, {df, 0});
        if (file_start == NO_SQUARE) continue;
        for (int r = g.rank_of(s) + dir; r != promo + dir; r += dir) {
            Piece q = p.at(g.square(g.file_of(file_start), r));
            if (!q.empty() && q.color() != us && v.kind(q.type()).pawn) return false;
        }
    }
    return true;
}

// Number of squares a piece on s can move to (ignoring pins), counting attacks on the enemy king zone
int mobility(const Position &p, Square s, const std::bitset<MAX_SQUARES> &enemy_zone, int &zone_attacks) {
    const Variant &v = p.variant();
    const Piece pc = p.at(s);
    std::bitset<MAX_SQUARES> reach;
    for (Square to: v.leaps(pc.type(), s)) reach[to] = true;
    for (const Ray &ray: v.slides(pc.type(), s)) {
        for (Square to: ray) {
            reach[to] = true;
            if (!p.at(to).empty()) break;
        }
    }
    zone_attacks += int((reach & enemy_zone).count());
    int n = 0;
    for (Square to = 0; to < v.num_squares(); ++to)
        if (reach[to] && (p.at(to).empty() || p.at(to).color() != pc.color())) ++n;
    return n;
}

Score evaluate_side(const Position &p, Color us, int &material_left) {
    const Variant &v = p.variant();
    const Geometry &g = p.geometry();
    const auto enemy_zone = king_zone(p, ~us);
    const auto own_zone = king_zone(p, us);
    Score s;
    int attack_units = 0, attackers = 0, shelter = 0;

    for (Square sq = 0; sq < v.num_squares(); ++sq) {
        const Piece pc = p.at(sq);
        if (pc.empty() || pc.color() != us) continue;
        const PieceKind &k = v.kind(pc.type());
        s.add(k.value);
        if (own_zone[sq] && !k.royal) ++shelter;

        if (k.pawn) {
            int to_go = std::abs(v.rules().pawns.promotion_rank[us] - g.rank_of(sq));
            int advanced = g.ranks - 1 - to_go;
            if (is_passed(p, sq, us)) s.add(advanced * advanced * 2, advanced * advanced * 5);
            else s.add(advanced * 2, advanced * 3);
            continue;
        }
        if (k.royal) continue;

        material_left += k.value;
        int zone_attacks = 0;
        int mob = mobility(p, sq, enemy_zone, zone_attacks);
        s.add(mob * k.mobility_weight);
        if (zone_attacks) {
            attack_units += zone_attacks * std::max(1, k.value / 300);
            ++attackers;
        }
    }

    // King attack: several pieces aiming at the zone around the enemy king
    if (attackers >= 2) s.add(std::min(attack_units * attack_units, 400), 0);
    s.add(std::min(shelter, 4) * 8, 0);
    return s;
}

int pawn_count(const Position &p, Color c) {
    int n = 0;
    for (PieceType t = 0; t < p.variant().num_piece_types(); ++t)
        if (p.variant().kind(t).pawn) n += p.count(c, t);
    return n;
}

int piece_material(const Position &p, Color c) {
    int m = 0;
    for (PieceType t = 0; t < p.variant().num_piece_types(); ++t) {
        const PieceKind &k = p.variant().kind(t);
        if (!k.pawn && !k.royal) m += p.count(c, t) * k.value;
    }
    return m;
}

} // namespace

int evaluate(const Position &p) {
    int material_left = 0;
    Score white = evaluate_side(p, WHITE, material_left);
    Score black = evaluate_side(p, BLACK, material_left);
    int mg = white.mg - black.mg;
    int eg = white.eg - black.eg;

    // Without pawns, a material advantage only wins by bringing the kings together
    const int w_mat = piece_material(p, WHITE), b_mat = piece_material(p, BLACK);
    if (pawn_count(p, WHITE) + pawn_count(p, BLACK) == 0 && std::abs(w_mat - b_mat) >= 400) {
        Square wk = p.royal_square(WHITE), bk = p.royal_square(BLACK);
        if (wk != NO_SQUARE && bk != NO_SQUARE) {
            int closeness = (8 - p.geometry().distance(wk, bk)) * 10;
            eg += w_mat > b_mat ? closeness : -closeness;
        }
    }

    int phase = std::min(material_left, MIDGAME_MATERIAL);
    int score = (mg * phase + eg * (MIDGAME_MATERIAL - phase)) / MIDGAME_MATERIAL;
    if (p.turn() == BLACK) score = -score;
    return score + 10; // tempo
}

int captured_value(const Position &p, Move m) {
    if (m.flags & Move::EN_PASSANT) {
        for (PieceType t = 0; t < p.variant().num_piece_types(); ++t)
            if (p.variant().kind(t).pawn) return p.variant().kind(t).value;
    }
    if (!m.is_capture() || p.at(m.to).empty()) return 0;
    return p.variant().kind(p.at(m.to).type()).value;
}

namespace {

// Cheapest piece of color c attacking s, treating `removed` squares as empty.
// Royal pieces count as the most valuable attackers.
Square least_valuable_attacker(const Position &p, Square s, Color c, const std::bitset<MAX_SQUARES> &removed) {
    const Variant &v = p.variant();
    Square best = NO_SQUARE;
    int best_value = 1 << 30;

    auto consider = [&](Square from, PieceType t) {
        Piece q = p.at(from);
        if (removed[from] || q.empty() || q != Piece(c, t)) return;
        const PieceKind &k = v.kind(t);
        int value = k.royal ? (1 << 29) : k.value;
        if (value < best_value) {
            best_value = value;
            best = from;
        }
    };

    for (PieceType t = 0; t < v.num_piece_types(); ++t) {
        if (!p.count(c, t)) continue;
        if (v.kind(t).pawn) {
            for (Square from: v.pawn_capture_sources(c, s)) consider(from, t);
            continue;
        }
        for (Square from: v.leap_sources(t, s)) consider(from, t);
        for (const Ray &ray: v.slide_sources(t, s)) {
            for (Square from: ray) {
                if (removed[from] || p.at(from).empty()) continue;
                consider(from, t);
                break;
            }
        }
    }
    return best;
}

} // namespace

// Swap algorithm (as in Stockfish): alternately capture on the target square with the least
// valuable attacker and track whether the side to move is still ahead of the threshold.
bool see_ge(const Position &p, Move m, int threshold) {
    if (m.flags & Move::CASTLE) return threshold <= 0;
    const Variant &v = p.variant();

    int swap = captured_value(p, m) - threshold;
    if (swap < 0) return false;
    const PieceKind &mover = v.kind(p.at(m.from).type());
    swap = (mover.royal ? 0 : mover.value) - swap;
    if (swap <= 0) return true;

    std::bitset<MAX_SQUARES> removed;
    removed[m.from] = true;
    if (m.flags & Move::EN_PASSANT) removed[p.geometry().step(m.to, v.pawn_forward(~p.turn()))] = true;

    Color stm = p.turn();
    int res = 1;
    while (true) {
        stm = ~stm;
        Square from = least_valuable_attacker(p, m.to, stm, removed);
        if (from == NO_SQUARE) break;
        res ^= 1;

        const PieceKind &k = v.kind(p.at(from).type());
        if (k.royal) {
            // The king may only capture if the opponent has no attackers left
            return least_valuable_attacker(p, m.to, ~stm, removed) != NO_SQUARE ? res ^ 1 : res;
        }
        if ((swap = k.value - swap) < res) break;
        removed[from] = true;
    }
    return bool(res);
}

bool insufficient_material(const Position &p) {
    const Variant &v = p.variant();
    int minors = 0;
    for (PieceType t = 0; t < v.num_piece_types(); ++t) {
        const PieceKind &k = v.kind(t);
        if (k.royal) continue;
        int n = p.count(WHITE, t) + p.count(BLACK, t);
        if (n == 0) continue;
        if (!is_minor(k)) return false;
        minors += n;
    }
    return minors <= 1;
}

bool has_non_pawn_material(const Position &p, Color c) {
    return piece_material(p, c) > 0;
}

} // namespace variant
