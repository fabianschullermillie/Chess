#include <iostream>
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
        play_cli<StandardBoard>(argc > 2 ? stoll(argv[2]) : 3000);
    } else {
        Uci().loop();
    }
    return 0;
}
