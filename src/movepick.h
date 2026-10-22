//
// Move ordering: scores all legal moves once, then hands them out best-first.
//

#ifndef CHESS_MOVEPICK_H
#define CHESS_MOVEPICK_H

#pragma once

#include <cstdint>
#include <cstdlib>
#include <utility>

#include "board_api.h"

// Butterfly history of quiet moves, indexed [color][from][to]
template<class B>
using HistoryTable = int16_t[2][B::NUM_SQUARES][B::NUM_SQUARES];

constexpr int HISTORY_MAX = 16384;

inline void update_history(int16_t &entry, int bonus) {
    // Gravity: keeps values bounded and lets old information decay
    entry += bonus - entry * std::abs(bonus) / HISTORY_MAX;
}

template<class B>
class MovePicker {
    using Position = typename B::Position;
    using Move = typename B::Move;

public:
    enum Stage : int {
        SCORE_TT = 30'000'000,
        SCORE_GOOD_CAPTURE = 20'000'000,
        SCORE_PROMOTION = 19'000'000,
        SCORE_KILLER1 = 18'000'000,
        SCORE_KILLER2 = 17'000'000,
        SCORE_COUNTER = 16'000'000,
        SCORE_BAD_CAPTURE = -20'000'000,
    };

    // Main search: every legal move. Quiescence (captures_only): captures and queen promotions.
    MovePicker(Position &p, Move tt_move, const Move *killers, Move counter,
               const HistoryTable<B> &history, bool captures_only) {
        Move *end = B::turn(p) == WHITE ? B::template generate_legals<WHITE>(p, moves)
                                        : B::template generate_legals<BLACK>(p, moves);
        total = int(end - moves);
        for (int i = 0; i < total; ++i) {
            Move m = moves[i];
            if (captures_only && !B::is_capture(m) && !B::is_queen_promotion(m)) continue;
            moves[count] = m;
            scores[count++] = score(p, m, tt_move, killers, counter, history);
        }
    }

    // Number of legal moves in the position (independent of the captures_only filter)
    int legal_count() const { return total; }

    bool next(Move &m, int &move_score) {
        if (cur >= count) return false;
        int best = cur;
        for (int i = cur + 1; i < count; ++i)
            if (scores[i] > scores[best]) best = i;
        std::swap(moves[cur], moves[best]);
        std::swap(scores[cur], scores[best]);
        m = moves[cur];
        move_score = scores[cur++];
        return true;
    }

private:
    int score(const Position &p, Move m, Move tt_move, const Move *killers, Move counter,
              const HistoryTable<B> &history) const {
        if (m == tt_move) return SCORE_TT;

        if (B::is_capture(m)) {
            int victim = B::captured_value(p, m);
            int attacker = B::moving_value(p, m);
            int mvv_lva = victim * 16 - attacker / 16;
            if (B::is_promotion(m)) mvv_lva += B::promotion_value(m) * 16;
            return (B::see_ge(p, m, 0) ? SCORE_GOOD_CAPTURE : SCORE_BAD_CAPTURE) + mvv_lva;
        }
        if (B::is_promotion(m)) {
            return B::is_queen_promotion(m) ? SCORE_PROMOTION : SCORE_BAD_CAPTURE - 1;
        }
        if (killers) {
            if (m == killers[0]) return SCORE_KILLER1;
            if (m == killers[1]) return SCORE_KILLER2;
        }
        if (m == counter) return SCORE_COUNTER;
        return history[B::turn(p)][B::from(m)][B::to(m)];
    }

    Move moves[B::MAX_MOVES];
    int scores[B::MAX_MOVES];
    int count = 0;
    int total = 0;
    int cur = 0;
};

#endif //CHESS_MOVEPICK_H
