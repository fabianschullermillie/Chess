//
// Basic vocabulary of the variant board: colors, squares, pieces and moves.
//
// The variant board favours readability and extensibility over speed. A variant is described as
// data (see rules.h): the shape of the board, how each piece moves, how pawns behave, which
// castling moves exist and the starting position. Move generation, evaluation and the rest are
// written once against that description.
//

#ifndef CHESS_VARIANT_TYPES_H
#define CHESS_VARIANT_TYPES_H

#pragma once

#include <cstdint>

namespace variant {

// Squares are numbered rank by rank from the bottom-left corner (a1 = 0, b1 = 1, ...).
// Moves encode squares in 6 bits, so boards may have up to 64 squares.
constexpr int MAX_SQUARES = 64;
using Square = int;
constexpr Square NO_SQUARE = -1;

enum Color : uint8_t { WHITE, BLACK };
constexpr Color operator~(Color c) { return Color(c ^ 1); }

// Index into Rules::pieces
using PieceType = int;
constexpr int MAX_PIECE_TYPES = 14;

// A colored piece, or an empty square
class Piece {
public:
    constexpr Piece() = default;
    constexpr Piece(Color c, PieceType t) : code(int8_t(t * 2 + c)) {}

    constexpr bool empty() const { return code < 0; }
    constexpr Color color() const { return Color(code & 1); }
    constexpr PieceType type() const { return code >> 1; }
    // Dense index (0 .. 2 * MAX_PIECE_TYPES - 1) for tables such as zobrist keys
    constexpr int index() const { return code; }

    constexpr bool operator==(const Piece &o) const = default;

private:
    int8_t code = -1;
};

constexpr Piece NO_PIECE{};

// A board direction or jump, in files and ranks
struct Offset {
    int df, dr;
    constexpr Offset operator-() const { return {-df, -dr}; }
};

struct Move {
    enum Flags : uint8_t {
        QUIET = 0,
        CAPTURE = 1,
        DOUBLE_PUSH = 2,
        EN_PASSANT = 4,
        CASTLE = 8,
    };

    uint8_t from = 0;
    uint8_t to = 0;
    uint8_t promotion = 0; // promoted piece type + 1, 0 = no promotion
    uint8_t flags = QUIET;

    static constexpr Move make(Square from, Square to, uint8_t flags = QUIET, int promotion = 0) {
        return Move{uint8_t(from), uint8_t(to), uint8_t(promotion), flags};
    }

    bool is_capture() const { return flags & CAPTURE; }
    bool is_promotion() const { return promotion != 0; }
    PieceType promotion_type() const { return promotion - 1; }

    // from, to and promotion identify a move in a position; the flags follow from them.
    uint16_t code() const { return uint16_t(from | to << 6 | promotion << 12); }
    static Move from_code(uint16_t c) { return Move{uint8_t(c & 63), uint8_t((c >> 6) & 63), uint8_t(c >> 12), QUIET}; }

    bool is_null() const { return from == to; }
    bool operator==(const Move &o) const { return code() == o.code(); }
};

} // namespace variant

#endif //CHESS_VARIANT_TYPES_H
