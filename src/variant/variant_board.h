//
// Adapter from the variant board to the engine's Board API (see board_api.h).
//
// VariantBoard<Tag> turns any variant into something the search can play. Tag only has to provide
// `static const variant::Variant &get()`; see toroidal.h for an example.
//

#ifndef CHESS_VARIANT_BOARD_H
#define CHESS_VARIANT_BOARD_H

#pragma once

#include <cctype>
#include <ostream>
#include <string>

#include "../board_api.h"
#include "eval.h"
#include "position.h"

template<class Tag>
struct VariantBoard {
    using Move = variant::Move;

    struct Position : variant::Position {
        Position() : variant::Position(Tag::get()) {}
    };

    static const variant::Variant &v() { return Tag::get(); }

    static inline const std::string NAME = Tag::get().rules().name;
    static inline const std::string START_FEN = Tag::get().rules().start_fen;
    static constexpr int NUM_SQUARES = variant::MAX_SQUARES;
    static constexpr int MAX_MOVES = variant::Position::MAX_MOVES;
    static constexpr bool HAS_NNUE = false;
    static constexpr bool HAS_BOOK = false;

    static int set_fen(Position &p, const std::string &fen) { return p.set_fen(fen); }
    static std::string fen(const Position &p, int halfmove, int fullmove) { return p.fen(halfmove, fullmove); }
    static Color turn(const Position &p) { return Color(int(p.turn())); }
    static uint64_t key(const Position &p) { return p.key(); }
    static void print(std::ostream &os, const Position &p) { os << static_cast<const variant::Position &>(p); }

    template<Color Us> static Move *generate_legals(Position &p, Move *list) { return p.generate_legal_moves(list); }
    template<Color Us> static void play(Position &p, Move m) { p.play(m); }
    template<Color Us> static void undo(Position &p, Move) { p.undo(); }
    template<Color Us> static bool in_check(const Position &p) { return p.in_check(variant::Color(int(Us))); }
    static void make_null(Position &p) { p.play_null(); }
    static void unmake_null(Position &p) { p.undo_null(); }

    static int from(Move m) { return m.from; }
    static int to(Move m) { return m.to; }
    static bool is_null(Move m) { return m.is_null(); }
    static bool is_capture(Move m) { return m.is_capture(); }
    static bool is_promotion(Move m) { return m.is_promotion(); }
    // Promotion types are listed best first
    static bool is_queen_promotion(Move m) {
        return m.is_promotion() && m.promotion_type() == v().rules().pawns.promotion_types.front();
    }
    static bool resets_halfmove(const Position &p, Move m) {
        return m.is_capture() || v().kind(p.at(m.from).type()).pawn;
    }
    static uint16_t move_code(Move m) { return m.code(); }
    static Move move_from_code(uint16_t code) { return Move::from_code(code); }
    static std::string move_to_uci(Move m) {
        if (m.is_null()) return "0000";
        std::string s = v().geometry().name(m.from) + v().geometry().name(m.to);
        if (m.is_promotion()) s += char(std::tolower(v().kind(m.promotion_type()).letter));
        return s;
    }
    static Move parse_uci(Position &p, const std::string &uci) { return p.parse_uci(uci); }

    static int captured_value(const Position &p, Move m) { return variant::captured_value(p, m); }
    static int moving_value(const Position &p, Move m) { return v().kind(p.at(m.from).type()).value; }
    static int promotion_value(Move m) { return v().kind(m.promotion_type()).value; }
    static bool see_ge(const Position &p, Move m, int threshold) { return variant::see_ge(p, m, threshold); }
    static int evaluate(const Position &p) { return variant::evaluate(p); }
    static bool insufficient_material(const Position &p) { return variant::insufficient_material(p); }
    static bool has_non_pawn_material(const Position &p, Color c) {
        return variant::has_non_pawn_material(p, variant::Color(int(c)));
    }
};

#endif //CHESS_VARIANT_BOARD_H
