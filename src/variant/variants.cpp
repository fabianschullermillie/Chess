//
// The variants the engine knows about.
//

#include "rules.h"

namespace variant {

namespace {

// Plain arrays (constant-initialized): the rules may be built during static initialization of
// another translation unit.
constexpr Offset ORTHOGONAL[] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
constexpr Offset DIAGONAL[] = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
constexpr Offset ALL_DIRECTIONS[] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
constexpr Offset KNIGHT_JUMPS[] = {{1, 2}, {2, 1}, {2, -1}, {1, -2}, {-1, -2}, {-2, -1}, {-2, 1}, {-1, 2}};

template<size_t N>
std::vector<Offset> offsets(const Offset (&list)[N]) { return {list, list + N}; }

enum : PieceType { PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING };

std::vector<PieceKind> orthodox_pieces() {
    return {
        {.name = "pawn", .letter = 'P', .value = 100, .pawn = true},
        {.name = "knight", .letter = 'N', .value = 320, .leaps = offsets(KNIGHT_JUMPS), .mobility_weight = 4},
        {.name = "bishop", .letter = 'B', .value = 330, .slides = offsets(DIAGONAL), .mobility_weight = 5},
        {.name = "rook", .letter = 'R', .value = 500, .slides = offsets(ORTHOGONAL), .mobility_weight = 3},
        {.name = "queen", .letter = 'Q', .value = 950, .slides = offsets(ALL_DIRECTIONS), .mobility_weight = 1},
        {.name = "king", .letter = 'K', .value = 0, .leaps = offsets(ALL_DIRECTIONS), .royal = true},
    };
}

PawnRules orthodox_pawns() {
    PawnRules p;
    p.promotion_types = {QUEEN, ROOK, BISHOP, KNIGHT};
    return p;
}

} // namespace

const Rules &standard_rules() {
    static const Rules rules = [] {
        Rules r;
        r.name = "chess";
        r.pieces = orthodox_pieces();
        r.pawns = orthodox_pawns();
        const Geometry &g = r.geometry;
        r.castling = {
            {WHITE, 'K', g.square(4, 0), g.square(6, 0), g.square(7, 0), g.square(5, 0)},
            {WHITE, 'Q', g.square(4, 0), g.square(2, 0), g.square(0, 0), g.square(3, 0)},
            {BLACK, 'k', g.square(4, 7), g.square(6, 7), g.square(7, 7), g.square(5, 7)},
            {BLACK, 'q', g.square(4, 7), g.square(2, 7), g.square(0, 7), g.square(3, 7)},
        };
        r.start_fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        r.description = "Orthodox chess";
        return r;
    }();
    return rules;
}

// Toroidal chess. The a- and h-files are adjacent, and so are the 1st and 8th ranks, so every
// piece can leave the board on one side and re-enter on the other. The orthodox setup would start
// with both kings in check, so the pieces are arranged as on Wikipedia's "Cylinder chess" page:
// the kings and queens stand behind a wedge of pawns, rooks and bishops guard the seam between the
// 1st and 8th rank.
//
// Pawns still move towards the opponent and promote on the last rank (they never cross the seam).
// Only pawns that start on the 2nd / 7th rank may make a double step. There is no castling.
const Rules &toroidal_rules() {
    static const Rules rules = [] {
        Rules r;
        r.name = "toroidal";
        r.geometry.wrap_files = true;
        r.geometry.wrap_ranks = true;
        r.pieces = orthodox_pieces();
        r.pawns = orthodox_pawns();
        r.start_fen = "r1b2b1r/pp4pp/n1pqkp1n/3pp3/3PP3/N1PQKP1N/PP4PP/R1B2B1R w - - 0 1";
        r.description = "Chess on a torus: files and ranks wrap around";
        return r;
    }();
    return rules;
}

} // namespace variant
