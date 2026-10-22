//
// A chess variant, described as plain data.
//
// To add a variant, write a function returning a Rules object (see variants.cpp): pick a geometry,
// list the pieces with their movement, configure pawns and castling, and give a start position.
// Fairy pieces only need a new PieceKind entry, e.g. an archbishop is a bishop's slides plus a
// knight's leaps.
//

#ifndef CHESS_VARIANT_RULES_H
#define CHESS_VARIANT_RULES_H

#pragma once

#include <string>
#include <vector>

#include "geometry.h"

namespace variant {

struct PieceKind {
    std::string name;
    char letter;                 // FEN letter of the white piece (black uses lowercase)
    int value;                   // centipawns, used by the evaluation, SEE and move ordering
    std::vector<Offset> leaps;   // jumps to a fixed offset (knight, king)
    std::vector<Offset> slides;  // moves any distance in a direction until blocked (rook, bishop)
    bool pawn = false;           // moves according to PawnRules instead of leaps / slides
    bool royal = false;          // must not be left in check; losing all royal moves = mate
    int mobility_weight = 0;     // evaluation bonus per reachable square
};

struct PawnRules {
    // White pawns move towards higher ranks, black pawns towards lower ranks.
    // A pawn may move two squares if it stands on its double-step rank (e.g. rank index 1 for White).
    int double_step_rank[2] = {1, 6};
    // Reaching this rank promotes
    int promotion_rank[2] = {7, 0};
    std::vector<PieceType> promotion_types;
};

struct CastlingRule {
    Color color;
    char fen_letter;  // 'K', 'Q', 'k' or 'q'
    Square king_from, king_to;
    Square rook_from, rook_to;
};

struct Rules {
    std::string name;     // also the UCI_Variant name
    Geometry geometry;
    std::vector<PieceKind> pieces;
    PawnRules pawns;
    std::vector<CastlingRule> castling;
    std::string start_fen;
    std::string description;
};

// Orthodox chess on the generic board (used to verify the move generator against surge)
const Rules &standard_rules();
// Toroidal chess: both pairs of opposite edges are glued together
const Rules &toroidal_rules();

} // namespace variant

#endif //CHESS_VARIANT_RULES_H
