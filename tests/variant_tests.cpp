//
// Checks for the variant board:
//  1. With orthodox rules it must produce the same perft numbers as surge on the usual test positions
//     (covers pawns, en passant, promotions, castling and pins).
//  2. Incremental zobrist keys must match keys computed from scratch, and FENs must round-trip.
//  3. Toroidal perft numbers are printed (and compared with the JavaScript implementation of the web
//     demo, see web/toroidal.test.mjs).
//
// Usage: VariantTests [toroidal-depth]
//

#include <chrono>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "../src/boards.h"

using namespace std;

namespace {

int failures = 0;

void check(bool ok, const string &what) {
    if (!ok) {
        cout << "FAIL: " << what << endl;
        ++failures;
    }
}

template<class B>
uint64_t perft(typename B::Position &p, int depth) {
    typename B::Move moves[B::MAX_MOVES];
    typename B::Move *end = B::turn(p) == WHITE ? B::template generate_legals<WHITE>(p, moves)
                                                : B::template generate_legals<BLACK>(p, moves);
    if (depth == 1) return uint64_t(end - moves);
    uint64_t n = 0;
    for (auto *m = moves; m != end; ++m) {
        if (B::turn(p) == WHITE) B::template play<WHITE>(p, *m);
        else B::template play<BLACK>(p, *m);
        n += perft<B>(p, depth - 1);
        if (B::turn(p) == BLACK) B::template undo<WHITE>(p, *m);
        else B::template undo<BLACK>(p, *m);
    }
    return n;
}

// Walks the tree and compares the incremental key / FEN with a freshly loaded position
template<class B>
void check_consistency(typename B::Position &p, int depth) {
    typename B::Position fresh;
    B::set_fen(fresh, B::fen(p, 0, 1));
    check(B::key(fresh) == B::key(p), "incremental key " + B::fen(p, 0, 1));
    check(B::fen(fresh, 0, 1) == B::fen(p, 0, 1), "fen round trip " + B::fen(p, 0, 1));
    if (depth == 0) return;

    typename B::Move moves[B::MAX_MOVES];
    auto *end = B::template generate_legals<WHITE>(p, moves);
    for (auto *m = moves; m != end; ++m) {
        string before = B::fen(p, 0, 1);
        B::template play<WHITE>(p, *m);
        check_consistency<B>(p, depth - 1);
        B::template undo<WHITE>(p, *m);
        check(B::fen(p, 0, 1) == before, "undo restores " + before);
    }
}

struct PerftCase {
    string fen;
    int depth;
};

} // namespace

int main(int argc, char **argv) {
    board::init();
    const int torus_depth = argc > 1 ? atoi(argv[1]) : 4;

    const vector<PerftCase> cases = {
        {"rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 5},
        {"r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", 4},
        {"8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1", 5},
        {"r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1", 4},
        {"rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8", 4},
        {"r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10", 4},
    };

    cout << "Variant board (orthodox rules) vs surge" << endl;
    for (const auto &c: cases) {
        StandardBoard::Position sp;
        StandardBoard::set_fen(sp, c.fen);
        GenericChessBoard::Position gp;
        GenericChessBoard::set_fen(gp, c.fen);

        auto t0 = chrono::steady_clock::now();
        uint64_t expected = perft<StandardBoard>(sp, c.depth);
        auto t1 = chrono::steady_clock::now();
        uint64_t got = perft<GenericChessBoard>(gp, c.depth);
        auto t2 = chrono::steady_clock::now();
        auto ms = [](auto a, auto b) { return chrono::duration_cast<chrono::milliseconds>(b - a).count(); };
        cout << "  depth " << c.depth << "  surge " << expected << " (" << ms(t0, t1) << " ms)  variant " << got
             << " (" << ms(t1, t2) << " ms)  " << (got == expected ? "ok" : "MISMATCH") << "  " << c.fen << endl;
        check(got == expected, "perft " + c.fen);
    }

    cout << "Key / FEN consistency" << endl;
    for (const auto &c: cases) {
        GenericChessBoard::Position gp;
        GenericChessBoard::set_fen(gp, c.fen);
        if (GenericChessBoard::turn(gp) == WHITE) check_consistency<GenericChessBoard>(gp, 2);
    }
    {
        ToroidalBoard::Position tp;
        ToroidalBoard::set_fen(tp, ToroidalBoard::START_FEN);
        check_consistency<ToroidalBoard>(tp, 3);
    }

    cout << "Toroidal perft from the start position" << endl;
    for (int d = 1; d <= torus_depth; ++d) {
        ToroidalBoard::Position tp;
        ToroidalBoard::set_fen(tp, ToroidalBoard::START_FEN);
        auto t0 = chrono::steady_clock::now();
        uint64_t n = perft<ToroidalBoard>(tp, d);
        auto ms = chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now() - t0).count();
        cout << "  depth " << d << "  " << n << " (" << ms << " ms)" << endl;
    }

    cout << (failures ? to_string(failures) + " failures" : "all checks passed") << endl;
    return failures ? 1 : 0;
}
