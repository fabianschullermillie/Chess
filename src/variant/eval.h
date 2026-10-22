//
// Evaluation and static exchange evaluation for the variant board. Both only use the generic
// piece descriptions, so they work for any variant (if not equally well for all of them).
//

#ifndef CHESS_VARIANT_EVAL_H
#define CHESS_VARIANT_EVAL_H

#pragma once

#include "position.h"

namespace variant {

// Centipawns from the side to move's point of view
int evaluate(const Position &p);

// Value of the piece m captures (0 for non-captures)
int captured_value(const Position &p, Move m);
// True if the capture sequence started by m on its target square wins at least `threshold`
bool see_ge(const Position &p, Move m, int threshold);

// Neither side can possibly mate (only royals, plus at most one minor piece)
bool insufficient_material(const Position &p);
bool has_non_pawn_material(const Position &p, Color c);

} // namespace variant

#endif //CHESS_VARIANT_EVAL_H
