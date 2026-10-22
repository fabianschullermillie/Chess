//
// Created by fabian on 9/19/25.
//
// Iterative deepening principal variation search with a shared transposition table (Lazy SMP).
//

#include "search.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <iostream>
#include <sstream>

#include "boards.h"
#include "movepick.h"

using namespace std;

namespace {

int LMR[64][64];

struct LmrInit {
    LmrInit() {
        for (int d = 1; d < 64; ++d)
            for (int m = 1; m < 64; ++m)
                LMR[d][m] = int(0.75 + log(d) * log(m) / 2.25);
    }
} lmr_init;

string score_to_uci(int score) {
    if (score >= MATE_IN_MAX) return "mate " + to_string((MATE - score + 1) / 2);
    if (score <= -MATE_IN_MAX) return "mate -" + to_string((MATE + score) / 2);
    return "cp " + to_string(score);
}

} // namespace

// One search thread. Each worker has its own board and heuristics; only the TT is shared.
template<class B>
class Worker {
    using Position = typename B::Position;
    using Move = typename B::Move;

public:
    Worker(Search<B> &search, int id) : search(search), id(id) { clear(); }

    void clear() {
        memset(history, 0, sizeof(history));
        memset(counter_moves, 0, sizeof(counter_moves));
    }

    void setup(const GameState &game) {
        pos = make_unique<Position>();
        B::set_fen(*pos, game.fen);
        halfmove[0] = game.halfmove;
        if constexpr (B::HAS_NNUE) {
            use_nnue = search.use_nnue && B::nnue_loaded();
            if (use_nnue) B::nnue_refresh(acc[0], *pos);
        }

        // Only the last 100 plies can ever repeat (fifty-move rule)
        size_t keep = min<size_t>(game.key_history.size(), 100);
        keys.assign(game.key_history.end() - keep, game.key_history.end());
        root_index = int(keys.size());
        keys.resize(root_index + MAX_PLY + 8);

        nodes = 0;
        seldepth = 0;
        memset(killers, 0, sizeof(killers));
        memset(stack, 0, sizeof(stack));
        memset(pv, 0, sizeof(pv));
        memset(pv_length, 0, sizeof(pv_length));
    }

    SearchResult<B> iterate(const SearchLimits &limits);

    std::atomic<uint64_t> nodes{0};

private:
    struct StackEntry {
        Move move;      // move that led to this node
        int eval;       // static evaluation (NO_SCORE when in check)
    };

    template<Color Us> int negamax(int alpha, int beta, int depth, int ply, bool allow_null);
    template<Color Us> int qsearch(int alpha, int beta, int ply);

    int root_search(int alpha, int beta, int depth) {
        return B::turn(*pos) == WHITE ? negamax<WHITE>(alpha, beta, depth, 0, false)
                                    : negamax<BLACK>(alpha, beta, depth, 0, false);
    }

    int static_evaluate(int ply) const {
        int e;
        if constexpr (B::HAS_NNUE) e = use_nnue ? B::nnue_evaluate(acc[ply], B::turn(*pos)) : B::evaluate(*pos);
        else e = B::evaluate(*pos);
        // Fade the evaluation towards a draw as the fifty-move counter grows
        return e * (200 - halfmove[ply]) / 200;
    }

    bool is_repetition(int ply) const {
        const int idx = root_index + ply;
        const uint64_t k = keys[idx];
        const int limit = min(halfmove[ply], idx);
        for (int i = 4; i <= limit; i += 2)
            if (keys[idx - i] == k) return true;
        return false;
    }

    bool is_draw(int ply) const {
        return halfmove[ply] >= 100 || B::insufficient_material(*pos) || is_repetition(ply);
    }

    bool should_stop() {
        if (search.stop_flag.load(memory_order_relaxed)) return true;
        if (id == 0 && (nodes & 1023) == 0) {
            if (can_stop && ((limit_nodes && nodes >= limit_nodes) || search.time.hard_exceeded()))
                search.stop_flag = true;
        }
        return search.stop_flag.load(memory_order_relaxed);
    }

    void update_pv(int ply, Move m) {
        pv[ply][ply] = m;
        for (int i = ply + 1; i < pv_length[ply + 1]; ++i) pv[ply][i] = pv[ply + 1][i];
        pv_length[ply] = max(pv_length[ply + 1], ply + 1);
    }

    void update_quiet_stats(Color us, int ply, Move best, const Move *tried, int tried_count, int depth) {
        int bonus = min(16 * depth * depth + 32 * depth, 1600);
        update_history(history[us][B::from(best)][B::to(best)], bonus);
        for (int i = 0; i < tried_count; ++i)
            if (tried[i] != best) update_history(history[us][B::from(tried[i])][B::to(tried[i])], -bonus);

        if (killers[ply][0] != best) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = best;
        }
        if (ply > 0 && !B::is_null(stack[ply - 1].move)) {
            Move prev = stack[ply - 1].move;
            counter_moves[B::from(prev)][B::to(prev)] = best;
        }
    }

    string pv_string() const {
        string s;
        for (int i = 0; i < pv_length[0]; ++i) s += B::move_to_uci(pv[0][i]) + " ";
        return s;
    }

    Search<B> &search;
    const int id;

    unique_ptr<Position> pos;
    vector<uint64_t> keys;
    int root_index = 0;
    int halfmove[MAX_PLY + 8]{};

    uint64_t limit_nodes = 0;
    bool can_stop = false;
    int seldepth = 0;

    StackEntry stack[MAX_PLY + 8]{};
    Move killers[MAX_PLY + 8][2]{};
    HistoryTable<B> history{};
    Move counter_moves[B::NUM_SQUARES][B::NUM_SQUARES]{};

    Move pv[MAX_PLY + 8][MAX_PLY + 8]{};
    bool use_nnue = false;
    typename AccumulatorOf<B>::type acc[MAX_PLY + 8]{};
    int pv_length[MAX_PLY + 8]{};
};

template<class B>
template<Color Us>
int Worker<B>::qsearch(int alpha, int beta, int ply) {
    ++nodes;
    pv_length[ply] = ply;
    if (should_stop()) return 0;
    seldepth = max(seldepth, ply);

    const uint64_t key = B::key(*pos);
    keys[root_index + ply] = key;

    if (halfmove[ply] >= 100 || B::insufficient_material(*pos)) return 0;
    if (ply >= MAX_PLY - 1) return static_evaluate(ply);

    const bool in_check = B::template in_check<Us>(*pos);
    const bool pv_node = beta - alpha > 1;

    TTData tte{};
    const bool tt_hit = search.tt.probe(key, tte);
    int tt_score = tt_hit ? score_from_tt(tte.score, ply) : NO_SCORE;
    if (tt_hit && !pv_node && tt_score != NO_SCORE &&
        (tte.bound == BOUND_EXACT ||
         (tte.bound == BOUND_LOWER && tt_score >= beta) ||
         (tte.bound == BOUND_UPPER && tt_score <= alpha)))
        return tt_score;

    int best = -INF;
    int static_eval = NO_SCORE;
    if (!in_check) {
        static_eval = tt_hit && tte.eval != NO_SCORE ? tte.eval : static_evaluate(ply);
        best = static_eval;
        // A TT score is a better estimate than the static eval when its bound allows
        if (tt_hit && ((tte.bound == BOUND_LOWER && tt_score > best) || (tte.bound == BOUND_UPPER && tt_score < best)))
            best = tt_score;
        if (best >= beta) return best;
        alpha = max(alpha, best);
    }

    MovePicker<B> mp(*pos, tt_hit ? B::move_from_code(tte.move) : Move(), nullptr, Move(), history, !in_check);
    if (mp.legal_count() == 0) return in_check ? -MATE + ply : 0;

    Move best_move;
    Move m;
    int move_score;
    const int orig_alpha = alpha;

    while (mp.next(m, move_score)) {
        if (!in_check) {
            // Losing captures cannot raise alpha here
            if (move_score < 0) continue;
            // Delta pruning: even winning this piece won't reach alpha
            if (!B::is_promotion(m) &&
                static_eval + B::captured_value(*pos, m) + 200 <= alpha)
                continue;
        }

        if constexpr (B::HAS_NNUE) if (use_nnue) B::nnue_update(acc[ply], acc[ply + 1], *pos, m);
        B::template play<Us>(*pos, m);
        halfmove[ply + 1] = 0;
        stack[ply].move = m;
        int score = -qsearch<~Us>(-beta, -alpha, ply + 1);
        B::template undo<Us>(*pos, m);

        if (search.stop_flag.load(memory_order_relaxed)) return 0;

        if (score > best) {
            best = score;
            if (score > alpha) {
                best_move = m;
                alpha = score;
                if (alpha >= beta) break;
            }
        }
    }

    Bound bound = best >= beta ? BOUND_LOWER : (best > orig_alpha ? BOUND_EXACT : BOUND_UPPER);
    search.tt.store(key, B::move_code(best_move), score_to_tt(best, ply), static_eval, 0, bound);
    return best;
}

template<class B>
template<Color Us>
int Worker<B>::negamax(int alpha, int beta, int depth, int ply, bool allow_null) {
    const bool root = ply == 0;
    const bool pv_node = beta - alpha > 1;
    const bool in_check = B::template in_check<Us>(*pos);
    pv_length[ply] = ply;

    if (in_check && !root) depth++; // check extension
    if (depth <= 0) return qsearch<Us>(alpha, beta, ply);

    ++nodes;
    if (should_stop()) return 0;
    seldepth = max(seldepth, ply);

    const uint64_t key = B::key(*pos);
    keys[root_index + ply] = key;

    if (!root) {
        if (is_draw(ply)) return 0;
        if (ply >= MAX_PLY - 1) return in_check ? 0 : static_evaluate(ply);

        // Mate distance pruning
        alpha = max(alpha, -MATE + ply);
        beta = min(beta, MATE - ply - 1);
        if (alpha >= beta) return alpha;
    }

    // Transposition table
    TTData tte{};
    const bool tt_hit = search.tt.probe(key, tte);
    const int tt_score = tt_hit ? score_from_tt(tte.score, ply) : NO_SCORE;
    Move tt_move = tt_hit ? B::move_from_code(tte.move) : Move();
    if (!pv_node && tt_hit && tte.depth >= depth &&
        (tte.bound == BOUND_EXACT ||
         (tte.bound == BOUND_LOWER && tt_score >= beta) ||
         (tte.bound == BOUND_UPPER && tt_score <= alpha))) {
        return tt_score;
    }

    // Static evaluation
    int eval = NO_SCORE;
    int static_eval = NO_SCORE;
    if (!in_check) {
        static_eval = eval = tt_hit && tte.eval != NO_SCORE ? tte.eval : static_evaluate(ply);
        if (tt_hit && ((tte.bound == BOUND_LOWER && tt_score > eval) || (tte.bound == BOUND_UPPER && tt_score < eval)))
            eval = tt_score;
    }
    stack[ply].eval = static_eval;
    const bool improving = !in_check && ply >= 2 && stack[ply - 2].eval != NO_SCORE && static_eval > stack[ply - 2].eval;

    killers[ply + 1][0] = Move();
    killers[ply + 1][1] = Move();

    if (!pv_node && !in_check) {
        // Reverse futility pruning: we are so far ahead that a quiet move will keep us above beta
        if (depth <= 8 && eval - 75 * (depth - improving) >= beta && abs(beta) < MATE_IN_MAX)
            return eval;

        // Razoring: hopeless positions drop straight into quiescence
        if (depth <= 3 && eval + 250 * depth <= alpha) {
            int score = qsearch<Us>(alpha, alpha + 1, ply);
            if (score <= alpha) return score;
        }

        // Null move pruning
        if (allow_null && depth >= 3 && eval >= beta && B::has_non_pawn_material(*pos, Us)) {
            int r = 3 + depth / 4 + min((eval - beta) / 200, 3);
            if constexpr (B::HAS_NNUE) if (use_nnue) acc[ply + 1] = acc[ply];
            B::make_null(*pos);
            stack[ply].move = Move();
            halfmove[ply + 1] = 0; // no repetitions across a null move
            int score = -negamax<~Us>(-beta, -beta + 1, depth - r, ply + 1, false);
            B::unmake_null(*pos);

            if (search.stop_flag.load(memory_order_relaxed)) return 0;
            if (score >= beta) return score >= MATE_IN_MAX ? beta : score;
        }
    }

    // Internal iterative reduction: without a TT move this node is probably not worth a full search
    if (depth >= 4 && B::is_null(tt_move)) depth--;

    Move counter{};
    if (ply > 0 && !B::is_null(stack[ply - 1].move)) {
        Move prev = stack[ply - 1].move;
        counter = counter_moves[B::from(prev)][B::to(prev)];
    }

    MovePicker<B> mp(*pos, tt_move, killers[ply], counter, history, false);
    if (mp.legal_count() == 0) return in_check ? -MATE + ply : 0;

    const int orig_alpha = alpha;
    int best = -INF;
    Move best_move{};
    Move quiets_tried[64];
    int quiet_count = 0;
    int move_count = 0;
    bool skip_quiets = false;

    Move m;
    int move_score;
    while (mp.next(m, move_score)) {
        const bool quiet = !B::is_capture(m) && !B::is_promotion(m);
        if (quiet && skip_quiets) continue;

        // Pruning of moves unlikely to matter (never while we could still be getting mated)
        if (!root && best > -MATE_IN_MAX && B::has_non_pawn_material(*pos, Us)) {
            if (quiet) {
                // Late move pruning
                if (depth <= 8 && move_count >= (3 + depth * depth) / (2 - improving)) {
                    skip_quiets = true;
                    continue;
                }
                // Futility pruning
                if (!in_check && depth <= 8 && static_eval + 100 + 90 * depth <= alpha) {
                    skip_quiets = true;
                    continue;
                }
                if (depth <= 8 && !B::see_ge(*pos, m, -60 * depth)) continue;
            } else if (depth <= 6 && !B::see_ge(*pos, m, -100 * depth)) {
                continue;
            }
        }

        const bool resets = B::resets_halfmove(*pos, m);
        if constexpr (B::HAS_NNUE) if (use_nnue) B::nnue_update(acc[ply], acc[ply + 1], *pos, m);
        B::template play<Us>(*pos, m);
        ++move_count;
        stack[ply].move = m;
        halfmove[ply + 1] = resets ? 0 : halfmove[ply] + 1;
        const bool gives_check = B::template in_check<~Us>(*pos);

        int new_depth = depth - 1;
        int score = -INF;

        if (depth >= 3 && move_count > 1 + (root ? 1 : 0) && quiet) {
            // Late move reductions
            int r = LMR[min(depth, 63)][min(move_count, 63)];
            r -= pv_node;
            r += !improving;
            r -= gives_check;
            if (move_score >= MovePicker<B>::SCORE_COUNTER) r -= 1; // killer or counter move
            else r -= move_score / 8192;                         // history
            r = clamp(r, 0, new_depth - 1);

            score = -negamax<~Us>(-alpha - 1, -alpha, new_depth - r, ply + 1, true);
            if (score > alpha && r > 0)
                score = -negamax<~Us>(-alpha - 1, -alpha, new_depth, ply + 1, true);
        } else if (!pv_node || move_count > 1) {
            score = -negamax<~Us>(-alpha - 1, -alpha, new_depth, ply + 1, true);
        }

        if (pv_node && (move_count == 1 || (score > alpha && score < beta)))
            score = -negamax<~Us>(-beta, -alpha, new_depth, ply + 1, true);

        B::template undo<Us>(*pos, m);

        if (search.stop_flag.load(memory_order_relaxed)) return 0;

        if (score > best) {
            best = score;
            if (score > alpha) {
                best_move = m;
                alpha = score;
                if (pv_node) update_pv(ply, m);
                if (alpha >= beta) {
                    if (quiet) update_quiet_stats(Us, ply, m, quiets_tried, quiet_count, depth);
                    break;
                }
            }
        }
        if (quiet && quiet_count < 64) quiets_tried[quiet_count++] = m;
    }

    // Every move was pruned: fall back to a fail-low score
    if (move_count == 0) return alpha;

    Bound bound = best >= beta ? BOUND_LOWER : (best > orig_alpha ? BOUND_EXACT : BOUND_UPPER);
    search.tt.store(key, B::move_code(best_move), score_to_tt(best, ply), static_eval, depth, bound);
    return best;
}

template<class B>
SearchResult<B> Worker<B>::iterate(const SearchLimits &limits) {
    const bool main = id == 0;
    limit_nodes = main ? limits.nodes : 0;
    can_stop = false;

    SearchResult<B> result;
    int prev_score = 0;
    int stability = 0;

    for (int depth = 1; depth <= limits.depth; ++depth) {
        seldepth = 0;

        // Aspiration windows around the previous score
        int delta = 20;
        int alpha = -INF, beta = INF;
        if (depth >= 5) {
            alpha = max(prev_score - delta, -INF);
            beta = min(prev_score + delta, INF);
        }

        int score;
        while (true) {
            score = root_search(alpha, beta, depth);
            if (search.stop_flag) break;

            if (score <= alpha) {
                beta = (alpha + beta) / 2;
                alpha = max(score - delta, -INF);
            } else if (score >= beta) {
                beta = min(score + delta, INF);
            } else {
                break;
            }
            delta += delta / 2;
            if (delta > 1000) alpha = -INF, beta = INF;
        }

        if (search.stop_flag && depth > 1) break;

        Move best = pv[0][0];
        if (B::is_null(best)) break;
        stability = (best == result.best) ? stability + 1 : 0;

        result.best = best;
        result.ponder = pv_length[0] > 1 ? pv[0][1] : Move();
        result.score = score;
        result.depth = depth;
        prev_score = score;
        can_stop = true;

        if (!main) continue;

        if (search.print_info) {
            int64_t ms = max<int64_t>(1, search.time.elapsed());
            uint64_t total = search.total_nodes();
            cout << "info depth " << depth << " seldepth " << seldepth
                 << " score " << score_to_uci(score)
                 << " nodes " << total << " nps " << total * 1000 / ms
                 << " time " << ms << " hashfull " << search.tt.hashfull()
                 << " pv " << pv_string() << endl;
        }

        if (search.stop_flag) break;
        // A forced mate has been found: no need to keep searching
        if (!limits.infinite && is_mate_score(score) && depth >= 10 && abs(score) >= MATE - depth) break;
        // Don't start an iteration we likely can't finish; spend more time while the best move keeps changing
        double scale = stability >= 4 ? 0.5 : (stability >= 2 ? 0.7 : 1.1);
        if (search.time.soft_exceeded(scale)) break;
    }
    return result;
}

// ---------------------------------------------------------------------------------------------

template<class B>
Search<B>::Search() { set_threads(1); }

template<class B>
Search<B>::~Search() {
    stop();
    wait();
}

template<class B>
void Search<B>::set_threads(int n) {
    wait();
    workers.clear();
    for (int i = 0; i < max(1, n); ++i) workers.push_back(make_unique<Worker<B>>(*this, i));
}

template<class B>
void Search<B>::clear() {
    wait();
    tt.clear();
    for (auto &w: workers) w->clear();
}

template<class B>
uint64_t Search<B>::total_nodes() const {
    uint64_t n = 0;
    for (auto &w: workers) n += w->nodes.load(memory_order_relaxed);
    return n;
}

template<class B>
void Search<B>::start(const GameState &game, const SearchLimits &limits, bool print) {
    wait();
    print_info = print;
    stop_flag = false;
    running = true;
    main_thread = thread(&Search<B>::run, this, game, limits);
}

template<class B>
void Search<B>::wait() {
    if (main_thread.joinable()) main_thread.join();
}

template<class B>
SearchResult<B> Search<B>::think(const GameState &game, const SearchLimits &limits, bool print) {
    start(game, limits, print);
    wait();
    return result;
}

template<class B>
void Search<B>::run(GameState game, SearchLimits limits) {
    for (auto &w: workers) w->setup(game);

    auto root = make_unique<typename B::Position>();
    B::set_fen(*root, game.fen);
    time.start(limits, B::turn(*root));
    tt.new_search();

    // Helper threads search the same position, sharing results only through the TT
    vector<thread> helpers;
    for (size_t i = 1; i < workers.size(); ++i) {
        helpers.emplace_back([this, i, limits] {
            SearchLimits helper_limits = limits;
            helper_limits.depth = MAX_PLY - 1;
            workers[i]->iterate(helper_limits);
        });
    }

    result = workers[0]->iterate(limits);

    // With "go infinite" we must not report a move before being told to stop
    while (limits.infinite && !stop_flag) this_thread::sleep_for(chrono::milliseconds(1));
    stop_flag = true;
    for (auto &t: helpers) t.join();

    // Fallback if not even depth 1 finished
    if (B::is_null(result.best)) {
        typename B::Move moves[B::MAX_MOVES];
        typename B::Move *end = B::turn(*root) == WHITE ? B::template generate_legals<WHITE>(*root, moves)
                                                        : B::template generate_legals<BLACK>(*root, moves);
        if (end != moves) result.best = moves[0];
    }

    if (print_info) {
        cout << "bestmove " << B::move_to_uci(result.best);
        if (!B::is_null(result.ponder)) cout << " ponder " << B::move_to_uci(result.ponder);
        cout << endl;
    }
    running = false;
}

#define INSTANTIATE_SEARCH(B) template class Search<B>;
WOMBAT_FOR_EACH_BOARD(INSTANTIATE_SEARCH)
