//
// Derives the lookup tables of a Variant from its Rules.
//

#include "variant.h"

#include <algorithm>
#include <cctype>

namespace variant {

namespace {

// Walks from s in direction o until leaving the board or coming back to s
Ray make_ray(const Geometry &g, Square s, Offset o) {
    Ray ray;
    for (Square t = g.step(s, o); t != NO_SQUARE && t != s; t = g.step(t, o)) {
        if (std::find(ray.begin(), ray.end(), t) != ray.end()) break; // a cycle not through s
        ray.push_back(t);
    }
    return ray;
}

void add_unique(std::vector<Square> &v, Square s) {
    if (std::find(v.begin(), v.end(), s) == v.end()) v.push_back(s);
}

uint64_t splitmix64(uint64_t &state) {
    uint64_t z = (state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

} // namespace

Variant::Variant(const Rules &rules) : rules_(rules) {
    const Geometry &g = rules_.geometry;
    const int types = num_piece_types();
    leaps_.resize(types);
    leap_sources_.resize(types);
    slides_.resize(types);
    slide_sources_.resize(types);

    for (PieceType t = 0; t < types; ++t) {
        const PieceKind &k = rules_.pieces[t];
        for (Square s = 0; s < g.size(); ++s) {
            for (Offset o: k.leaps) {
                Square to = g.step(s, o);
                if (to != NO_SQUARE && to != s) add_unique(leaps_[t][s], to);
            }
            for (Offset o: k.slides) {
                Ray forward = make_ray(g, s, o);
                if (!forward.empty()) slides_[t][s].push_back(forward);
                Ray backward = make_ray(g, s, -o);
                if (!backward.empty()) slide_sources_[t][s].push_back(backward);
            }
        }
        for (Square s = 0; s < g.size(); ++s)
            for (Square to: leaps_[t][s]) add_unique(leap_sources_[t][to], s);
    }

    for (Color c: {WHITE, BLACK}) {
        const int forward = pawn_forward(c).dr;
        for (Square s = 0; s < g.size(); ++s) {
            for (int df: {-1, 1}) {
                Square to = g.step(s, {df, forward});
                if (to != NO_SQUARE && to != s) add_unique(pawn_captures_[c][s], to);
            }
        }
        for (Square s = 0; s < g.size(); ++s)
            for (Square to: pawn_captures_[c][s]) add_unique(pawn_capture_sources_[c][to], s);
    }

    for (const CastlingRule &rule: rules_.castling) {
        Castling c{rule, {}, {}};
        const int rank = g.rank_of(rule.king_from);
        auto file = [&](Square s) { return g.file_of(s); };
        int lo = std::min({file(rule.king_from), file(rule.king_to), file(rule.rook_from), file(rule.rook_to)});
        int hi = std::max({file(rule.king_from), file(rule.king_to), file(rule.rook_from), file(rule.rook_to)});
        for (int f = lo; f <= hi; ++f) {
            Square s = g.square(f, rank);
            if (s != rule.king_from && s != rule.rook_from) c.must_be_empty.push_back(s);
        }
        int step = file(rule.king_to) > file(rule.king_from) ? 1 : -1;
        for (int f = file(rule.king_from);; f += step) {
            c.must_be_safe.push_back(g.square(f, rank));
            if (f == file(rule.king_to)) break;
        }
        castling_.push_back(c);
    }

    uint64_t seed = 0x70201DA1ULL;
    for (auto &row: piece_keys_)
        for (auto &k: row) k = splitmix64(seed);
    side_key_ = splitmix64(seed);
    for (auto &k: castling_keys_) k = splitmix64(seed);
    castling_keys_[0] = 0;
    for (auto &k: en_passant_keys_) k = splitmix64(seed);
}

PieceType Variant::type_for_letter(char c) const {
    for (PieceType t = 0; t < num_piece_types(); ++t)
        if (rules_.pieces[t].letter == std::toupper(c)) return t;
    return -1;
}

char Variant::letter(Piece p) const {
    char c = kind(p.type()).letter;
    return p.color() == WHITE ? c : char(std::tolower(c));
}

} // namespace variant
