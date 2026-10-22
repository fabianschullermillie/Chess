//
// Position of a variant game: a mailbox board plus the state needed to undo moves.
//

#ifndef CHESS_VARIANT_POSITION_H
#define CHESS_VARIANT_POSITION_H

#pragma once

#include <array>
#include <ostream>
#include <string>
#include <vector>

#include "variant.h"

namespace variant {

class Position {
public:
    explicit Position(const Variant &v);

    // Loads a FEN (castling and en passant fields included). Returns the halfmove clock.
    int set_fen(const std::string &fen);
    std::string fen(int halfmove = 0, int fullmove = 1) const;

    const Variant &variant() const { return *v; }
    const Geometry &geometry() const { return v->geometry(); }
    Piece at(Square s) const { return board[s]; }
    Color turn() const { return side; }
    uint64_t key() const { return hash; }
    Square en_passant() const { return ep_square; }
    unsigned castling_rights() const { return castling; }
    int count(Color c, PieceType t) const { return counts[c][t]; }
    // Square of the royal piece of color c, NO_SQUARE if it has none
    Square royal_square(Color c) const { return royal[c]; }

    // Moves are assumed to be legal (as produced by generate_legal_moves)
    void play(Move m);
    void undo();
    // Passes the turn (used by null move pruning)
    void play_null();
    void undo_null();

    bool is_attacked(Square s, Color by) const;
    bool in_check(Color c) const { return royal[c] != NO_SQUARE && is_attacked(royal[c], ~c); }
    bool in_check() const { return in_check(side); }

    // Pseudo-legal moves, then only those that don't leave the own royal piece in check.
    // Returns the end of the written range; `list` needs room for MAX_MOVES moves.
    static constexpr int MAX_MOVES = 512;
    Move *generate_pseudo_legal_moves(Move *list) const;
    Move *generate_legal_moves(Move *list);

    std::string move_to_uci(Move m) const;
    // Legal move matching a UCI string, or a null move
    Move parse_uci(const std::string &uci);

    friend std::ostream &operator<<(std::ostream &os, const Position &p);

private:
    struct Undo {
        Move move;
        Piece moved;
        Piece captured;
        Square captured_on;
        unsigned castling;
        Square ep_square;
        uint64_t hash;
    };

    void put(Piece p, Square s);
    Piece remove(Square s);
    void set_en_passant(Square s);
    void set_castling(unsigned rights);

    Move *add_pawn_moves(Square from, Move *list) const;
    Move *add_piece_moves(Square from, Move *list) const;
    Move *add_castling_moves(Move *list) const;

    const Variant *v;
    std::array<Piece, MAX_SQUARES> board{};
    Color side = WHITE;
    unsigned castling = 0;       // bit i: castling rule i is still allowed
    Square ep_square = NO_SQUARE; // square a pawn skipped with a double step, if it can be captured
    uint64_t hash = 0;
    std::array<Square, 2> royal{NO_SQUARE, NO_SQUARE};
    int counts[2][MAX_PIECE_TYPES]{};
    std::vector<Undo> history;
};

} // namespace variant

#endif //CHESS_VARIANT_POSITION_H
