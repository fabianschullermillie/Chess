//
// A Variant is a Rules description plus lookup tables derived from it once at startup:
// for every piece type and square, the squares it can jump to and the rays it can slide along.
// Move generation and attack detection only use these tables, never the geometry directly.
//

#ifndef CHESS_VARIANT_VARIANT_H
#define CHESS_VARIANT_VARIANT_H

#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "rules.h"

namespace variant {

using Ray = std::vector<Square>; // squares in order of distance, excluding the origin

class Variant {
public:
    explicit Variant(const Rules &rules);

    const Rules &rules() const { return rules_; }
    const Geometry &geometry() const { return rules_.geometry; }
    const PieceKind &kind(PieceType t) const { return rules_.pieces[t]; }
    int num_piece_types() const { return int(rules_.pieces.size()); }
    int num_squares() const { return rules_.geometry.size(); }

    // Squares a piece of type t on s jumps to (deduplicated)
    const std::vector<Square> &leaps(PieceType t, Square s) const { return leaps_[t][s]; }
    // Rays a piece of type t on s slides along. On a wrapping board a ray stops before it returns
    // to s; two rays may then reach the same square from different sides.
    const std::vector<Ray> &slides(PieceType t, Square s) const { return slides_[t][s]; }

    // Squares from which a piece of type t would attack s (leaps and slides reversed)
    const std::vector<Square> &leap_sources(PieceType t, Square s) const { return leap_sources_[t][s]; }
    const std::vector<Ray> &slide_sources(PieceType t, Square s) const { return slide_sources_[t][s]; }

    // Pawns
    Offset pawn_forward(Color c) const { return {0, c == WHITE ? 1 : -1}; }
    const std::vector<Square> &pawn_captures(Color c, Square s) const { return pawn_captures_[c][s]; }
    // Squares from which a pawn of color c attacks s
    const std::vector<Square> &pawn_capture_sources(Color c, Square s) const { return pawn_capture_sources_[c][s]; }
    bool is_promotion_square(Color c, Square s) const { return geometry().rank_of(s) == rules_.pawns.promotion_rank[c]; }

    // Castling: squares that must be empty, and squares the king must not be attacked on
    struct Castling {
        CastlingRule rule;
        std::vector<Square> must_be_empty;
        std::vector<Square> must_be_safe;
    };
    const std::vector<Castling> &castling() const { return castling_; }

    // Zobrist keys
    uint64_t piece_key(Piece p, Square s) const { return piece_keys_[p.index()][s]; }
    uint64_t side_key() const { return side_key_; }
    uint64_t castling_key(unsigned rights) const { return castling_keys_[rights]; }
    uint64_t en_passant_key(Square s) const { return en_passant_keys_[s]; }

    // Piece type for a FEN letter (either case), or -1
    PieceType type_for_letter(char c) const;
    char letter(Piece p) const;

private:
    template<class T>
    using PerTypeAndSquare = std::vector<std::array<T, MAX_SQUARES>>;

    Rules rules_;
    PerTypeAndSquare<std::vector<Square>> leaps_, leap_sources_;
    PerTypeAndSquare<std::vector<Ray>> slides_, slide_sources_;
    std::array<std::array<std::vector<Square>, MAX_SQUARES>, 2> pawn_captures_, pawn_capture_sources_;
    std::vector<Castling> castling_;

    uint64_t piece_keys_[2 * MAX_PIECE_TYPES][MAX_SQUARES];
    uint64_t side_key_;
    uint64_t castling_keys_[16];
    uint64_t en_passant_keys_[MAX_SQUARES];
};

} // namespace variant

#endif //CHESS_VARIANT_VARIANT_H
