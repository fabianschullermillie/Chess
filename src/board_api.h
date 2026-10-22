//
// The interface between the search and a board implementation.
//
// The search (search.cpp), move ordering (movepick.h) and the UCI front end (uci.cpp) are templates
// over a "board" type B. B is a stateless traits struct: it names a position and a move type and
// provides static functions on them. Everything is resolved at compile time, so the standard board
// (standard_board.h, a thin wrapper around surge) runs exactly the code it did before the
// abstraction existed: no virtual calls, no extra indirection.
//
// Functions taking a `Color Us` template parameter receive the side to move as a compile-time
// constant (the search is specialised per color, which matters for surge's bitboard generator).
// Implementations that don't care can ignore it.
//
// Optional features are switched on with constexpr flags:
//   B::HAS_NNUE      - provides Accumulator, nnue_loaded/refresh/update/evaluate
//   B::HAS_BOOK      - the opening book and Syzygy tablebases (both are standard-chess only)
//

#ifndef CHESS_BOARD_API_H
#define CHESS_BOARD_API_H

#pragma once

#include <concepts>
#include <cstdint>
#include <ostream>
#include <string>
#include <type_traits>

#include "../lib/surge/src/types.h" // Color, ~Color

template<class B>
concept Board = requires(typename B::Position &p, const typename B::Position &cp, typename B::Move m,
                         typename B::Move *list, const std::string &s, std::ostream &os, Color c) {
    // Identity
    { B::NAME } -> std::convertible_to<std::string>;       // UCI_Variant name
    { B::START_FEN } -> std::convertible_to<std::string>;
    { B::NUM_SQUARES } -> std::convertible_to<int>;         // move.from()/to() are < NUM_SQUARES
    { B::MAX_MOVES } -> std::convertible_to<int>;           // upper bound for generate_legals
    { B::HAS_NNUE } -> std::convertible_to<bool>;
    { B::HAS_BOOK } -> std::convertible_to<bool>;

    // Position setup and inspection
    { B::set_fen(p, s) } -> std::same_as<int>;              // returns the halfmove clock
    { B::fen(cp, 0, 1) } -> std::same_as<std::string>;
    { B::turn(cp) } -> std::same_as<Color>;
    { B::key(cp) } -> std::same_as<uint64_t>;               // zobrist key incl. side, castling, ep
    { B::print(os, cp) };

    // Moves
    { B::template generate_legals<WHITE>(p, list) } -> std::same_as<typename B::Move *>;
    { B::template play<WHITE>(p, m) };
    { B::template undo<WHITE>(p, m) };
    { B::template in_check<WHITE>(cp) } -> std::same_as<bool>;
    { B::make_null(p) };
    { B::unmake_null(p) };

    // Move properties
    { B::from(m) } -> std::convertible_to<int>;
    { B::to(m) } -> std::convertible_to<int>;
    { B::is_null(m) } -> std::same_as<bool>;
    { B::is_capture(m) } -> std::same_as<bool>;
    { B::is_promotion(m) } -> std::same_as<bool>;
    { B::is_queen_promotion(m) } -> std::same_as<bool>;     // the only promotion searched in qsearch
    { B::resets_halfmove(cp, m) } -> std::same_as<bool>;    // capture or pawn move
    { B::move_code(m) } -> std::same_as<uint16_t>;          // compact form for the TT, 0 = null
    { B::move_from_code(uint16_t{}) } -> std::same_as<typename B::Move>;
    { B::move_to_uci(m) } -> std::same_as<std::string>;
    { B::parse_uci(p, s) } -> std::same_as<typename B::Move>;

    // Values and evaluation (centipawns)
    { B::captured_value(cp, m) } -> std::same_as<int>;      // 0 for non-captures
    { B::moving_value(cp, m) } -> std::same_as<int>;
    { B::promotion_value(m) } -> std::same_as<int>;
    { B::see_ge(cp, m, 0) } -> std::same_as<bool>;
    { B::evaluate(cp) } -> std::same_as<int>;               // side to move's point of view
    { B::insufficient_material(cp) } -> std::same_as<bool>;
    { B::has_non_pawn_material(cp, c) } -> std::same_as<bool>;
};

// B::Accumulator if the board supports NNUE, an empty placeholder otherwise
template<class B, class = void>
struct AccumulatorOf { struct type {}; };

template<class B>
struct AccumulatorOf<B, std::void_t<typename B::Accumulator>> { using type = typename B::Accumulator; };

#endif //CHESS_BOARD_API_H
