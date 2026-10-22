//
// Standard chess: the Board implementation (see board_api.h) backed by surge's bitboards.
// Every function is a forwarding inline wrapper, so the search compiles to the same code as when it
// called surge directly.
//

#ifndef CHESS_STANDARD_BOARD_H
#define CHESS_STANDARD_BOARD_H

#pragma once

#include <ostream>
#include <string>

#include "board.h"
#include "board_api.h"
#include "eval.h"
#include "nnue.h"
#include "see.h"

struct StandardBoard {
    using Position = ::Position;
    using Move = ::Move;
    using Accumulator = nnue::Accumulator;

    static constexpr const char *NAME = "chess";
    static constexpr const char *START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    static constexpr int NUM_SQUARES = 64;
    static constexpr int MAX_MOVES = 218;
    static constexpr bool HAS_NNUE = true;
    static constexpr bool HAS_BOOK = true;

    static int set_fen(Position &p, const std::string &fen) { return board::set_fen(p, fen); }
    static std::string fen(const Position &p, int halfmove, int fullmove) { return board::fen(p, halfmove, fullmove); }
    static Color turn(const Position &p) { return p.turn(); }
    static uint64_t key(const Position &p) { return board::key(p); }
    static void print(std::ostream &os, const Position &p) { os << p; }

    template<Color Us> static Move *generate_legals(Position &p, Move *list) { return p.generate_legals<Us>(list); }
    template<Color Us> static void play(Position &p, Move m) { p.play<Us>(m); }
    template<Color Us> static void undo(Position &p, Move m) { p.undo<Us>(m); }
    template<Color Us> static bool in_check(const Position &p) { return p.in_check<Us>(); }
    static void make_null(Position &p) { board::make_null(p); }
    static void unmake_null(Position &p) { board::unmake_null(p); }

    static int from(Move m) { return m.from(); }
    static int to(Move m) { return m.to(); }
    static bool is_null(Move m) { return board::is_null(m); }
    static bool is_capture(Move m) { return board::is_capture(m); }
    static bool is_promotion(Move m) { return board::is_promotion(m); }
    static bool is_queen_promotion(Move m) { return board::is_promotion(m) && board::promotion_type(m) == QUEEN; }
    static bool resets_halfmove(const Position &p, Move m) {
        return board::is_capture(m) || type_of(p.at(m.from())) == PAWN;
    }
    static uint16_t move_code(Move m) { return uint16_t(m.to_from()); }
    static Move move_from_code(uint16_t code) { return Move(code); }
    static std::string move_to_uci(Move m) { return board::move_to_uci(m); }
    static Move parse_uci(Position &p, const std::string &uci) { return board::parse_uci(p, uci); }

    static int captured_value(const Position &p, Move m) {
        PieceType t = board::captured_type(p, m);
        return int(t) >= 0 ? PIECE_VALUE[t] : 0;
    }
    static int moving_value(const Position &p, Move m) { return PIECE_VALUE[type_of(p.at(m.from()))]; }
    static int promotion_value(Move m) { return PIECE_VALUE[board::promotion_type(m)]; }
    static bool see_ge(const Position &p, Move m, int threshold) { return ::see_ge(p, m, threshold); }
    static int evaluate(const Position &p) { return ::evaluate(p); }
    static bool insufficient_material(const Position &p) { return board::insufficient_material(p); }
    static bool has_non_pawn_material(const Position &p, Color c) { return board::has_non_pawn_material(p, c); }

    static bool nnue_loaded() { return nnue::loaded(); }
    static void nnue_refresh(Accumulator &acc, const Position &p) { nnue::refresh(acc, p); }
    static void nnue_update(const Accumulator &parent, Accumulator &child, const Position &before, Move m) {
        nnue::update(parent, child, before, m);
    }
    static int nnue_evaluate(const Accumulator &acc, Color stm) { return nnue::evaluate(acc, stm); }
};

static_assert(Board<StandardBoard>);

#endif //CHESS_STANDARD_BOARD_H
