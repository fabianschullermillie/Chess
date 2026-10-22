#include <iostream>
#include <random>
#include <string>

#include "src/boards.h"
#include "src/datagen.h"
#include "src/nnue.h"
#include "src/uci.h"

using namespace std;

// Human (White) vs engine (Black) in the terminal
template<class B>
static void play_cli(int64_t movetime_ms) {
    Engine<B> engine;
    while (true) {
        auto &p = engine.position();
        B::print(cout, p);
        cout << "\n";

        typename B::Move moves[B::MAX_MOVES];
        auto *end = B::turn(p) == WHITE ? B::template generate_legals<WHITE>(p, moves)
                                        : B::template generate_legals<BLACK>(p, moves);
        if (end == moves) {
            bool check = B::turn(p) == WHITE ? B::template in_check<WHITE>(p) : B::template in_check<BLACK>(p);
            cout << (check ? "Checkmate!" : "Stalemate!") << "\n";
            break;
        }
        if (engine.is_draw()) {
            cout << "Draw!\n";
            break;
        }

        if (B::turn(p) == WHITE) {
            cout << "Enter your move (e.g. e2e4, quit to exit): ";
            string input;
            if (!(cin >> input) || input == "quit") break;
            auto m = B::parse_uci(p, input);
            if (B::is_null(m)) {
                cout << "Invalid move, try again.\n";
                continue;
            }
            engine.play(m);
        } else {
            cout << "Wombat is thinking...\n";
            SearchLimits limits;
            limits.movetime = movetime_ms;
            auto m = engine.best_move(limits, false);
            cout << "Wombat plays: " << B::move_to_uci(m) << "\n";
            engine.play(m);
        }
    }
    cout << "Final FEN: " << engine.game().fen << "\n";
}

// Engine vs engine. The first `random_plies` moves are random so the games differ.
template<class B>
static void selfplay(int games, int64_t movetime_ms, uint64_t seed, int random_plies = 2) {
    mt19937_64 rng(seed);
    int score[3] = {0, 0, 0}; // white wins, draws, black wins
    for (int g = 1; g <= games; ++g) {
        Engine<B> engine;
        string moves, result, reason;
        for (int ply = 0;; ++ply) {
            auto &p = engine.position();
            typename B::Move list[B::MAX_MOVES];
            auto *end = B::turn(p) == WHITE ? B::template generate_legals<WHITE>(p, list)
                                            : B::template generate_legals<BLACK>(p, list);
            const bool check = B::turn(p) == WHITE ? B::template in_check<WHITE>(p) : B::template in_check<BLACK>(p);
            if (end == list) {
                result = !check ? "1/2-1/2" : B::turn(p) == WHITE ? "0-1" : "1-0";
                reason = check ? "checkmate" : "stalemate";
                break;
            }
            if (engine.is_draw()) {
                result = "1/2-1/2", reason = "repetition / fifty moves / material";
                break;
            }
            if (ply >= 400) {
                result = "1/2-1/2", reason = "move limit";
                break;
            }
            typename B::Move m;
            if (ply < random_plies) {
                m = list[rng() % (end - list)];
            } else {
                SearchLimits limits;
                limits.movetime = movetime_ms;
                m = engine.think(limits).best;
            }
            if (ply % 2 == 0) moves += to_string(ply / 2 + 1) + ". ";
            moves += B::move_to_uci(m) + " ";
            engine.play(m);
        }
        score[result == "1-0" ? 0 : result == "0-1" ? 2 : 1]++;
        cout << "Game " << g << ": " << moves << result << " (" << reason << ")\nFinal FEN: " << engine.game().fen
             << "\n" << endl;
    }
    cout << "White wins " << score[0] << ", draws " << score[1] << ", Black wins " << score[2] << endl;
}

int main(int argc, char **argv) {
    board::init();
    if (!nnue::load_default()) cerr << "info string no embedded network, using the classical evaluation" << endl;

    string mode = argc > 1 ? argv[1] : "";
    if (mode == "bench") {
        Engine<StandardBoard>().bench(argc > 2 ? stoi(argv[2]) : 13);
    } else if (mode == "datagen") {
        // Wombat datagen <out-file> <games> <nodes-per-move> <seed>
        if (argc < 6) {
            cerr << "usage: Wombat datagen <out-file> <games> <nodes> <seed>\n";
            return 1;
        }
        run_datagen(argv[2], stoi(argv[3]), stoi(argv[4]), stoull(argv[5]));
    } else if (mode == "play") {
        // Wombat play [movetime-ms] [variant]
        int64_t ms = argc > 2 ? stoll(argv[2]) : 3000;
        if (argc > 3 && argv[3] == ToroidalBoard::NAME) play_cli<ToroidalBoard>(ms);
        else play_cli<StandardBoard>(ms);
    } else if (mode == "selfplay") {
        // Wombat selfplay <variant> <games> <movetime-ms> [seed]
        string variant = argc > 2 ? argv[2] : "toroidal";
        int games = argc > 3 ? stoi(argv[3]) : 2;
        int64_t ms = argc > 4 ? stoll(argv[4]) : 1000;
        uint64_t seed = argc > 5 ? stoull(argv[5]) : 1;
        if (variant == ToroidalBoard::NAME) selfplay<ToroidalBoard>(games, ms, seed);
        else selfplay<StandardBoard>(games, ms, seed);
    } else {
        Uci().loop();
    }
    return 0;
}
