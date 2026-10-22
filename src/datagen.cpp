//
// Self-play training data generation for the NNUE.
//

#include "datagen.h"

#include <fstream>
#include <iostream>
#include <random>
#include <vector>

#include "boards.h"
#include "uci.h"

using namespace std;

namespace {

const string START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
constexpr int RANDOM_PLIES = 8;
constexpr int MAX_GAME_PLIES = 400;
constexpr int WIN_ADJUDICATION = 2000; // cp, sustained for several plies

vector<Move> legal_moves(Position &p) {
    Move moves[218];
    Move *end = p.turn() == WHITE ? p.generate_legals<WHITE>(moves) : p.generate_legals<BLACK>(moves);
    return {moves, end};
}

} // namespace

void run_datagen(const string &out, int games, int nodes, uint64_t seed) {
    ofstream file(out, ios::app);
    if (!file) {
        cerr << "cannot open " << out << endl;
        return;
    }
    mt19937_64 rng(seed);
    Engine<StandardBoard> engine;
    uint64_t positions = 0;

    for (int game = 0; game < games; ++game) {
        engine.set_position(START_FEN, {});
        engine.new_game();

        // Random opening for diversity; restart if it ends the game
        bool ok = true;
        for (int i = 0; i < RANDOM_PLIES && ok; ++i) {
            auto moves = legal_moves(engine.position());
            if (moves.empty()) ok = false;
            else engine.play(moves[rng() % moves.size()]);
        }
        if (!ok || legal_moves(engine.position()).empty()) continue;

        vector<pair<string, int>> records; // fen, white-pov score
        double result = 0.5;
        int win_streak = 0, loss_streak = 0;

        for (int ply = 0; ply < MAX_GAME_PLIES; ++ply) {
            Position &p = engine.position();
            auto moves = legal_moves(p);
            if (moves.empty()) {
                result = board::in_check(p) ? (p.turn() == WHITE ? 0.0 : 1.0) : 0.5;
                break;
            }
            const GameState &g = engine.game();
            if (engine.is_draw()) {
                result = 0.5;
                break;
            }

            SearchLimits limits;
            limits.nodes = nodes;
            SearchResult<StandardBoard> r = engine.think(limits);
            int white_score = p.turn() == WHITE ? r.score : -r.score;

            // Adjudicate clearly decided games
            win_streak = white_score >= WIN_ADJUDICATION ? win_streak + 1 : 0;
            loss_streak = white_score <= -WIN_ADJUDICATION ? loss_streak + 1 : 0;
            if (win_streak >= 6) { result = 1.0; break; }
            if (loss_streak >= 6) { result = 0.0; break; }

            // Keep only quiet positions: the net should learn positional values, not tactics
            if (!board::in_check(p) && !board::is_tactical(r.best) && !is_mate_score(r.score))
                records.emplace_back(g.fen, white_score);

            engine.play(r.best);
        }

        for (auto &[fen, score]: records) file << fen << " | " << score << " | " << result << "\n";
        positions += records.size();
        if ((game + 1) % 50 == 0) {
            file.flush();
            cout << "games " << game + 1 << " positions " << positions << endl;
        }
    }
}
