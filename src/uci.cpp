//
// UCI protocol front end.
//

#include "uci.h"

#include <iostream>
#include <sstream>

#include "boards.h"
#include "EndgameDB.h"

using namespace std;

namespace {

const vector<string> BENCH_FENS = {
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
    "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
    "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
    "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
    "1rq2rk1/pb1nbp1p/2p1p1p1/3nP3/Np1PQ3/1P1B1NP1/P1R2P1P/2BR2K1 b - - 0 1",
    "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4",
    "6k1/5ppp/8/8/8/8/5PPP/3R2K1 w - - 0 1",
    "8/8/4k3/8/2p5/8/B2K4/8 w - - 0 1",
    "2r3k1/p4p2/3Rp2p/1p2P1pK/8/1P4P1/P3Q2P/1q6 b - - 0 1",
    "r1bq1rk1/ppp2ppp/2np1n2/2b1p3/2B1P3/2NP1N2/PPP2PPP/R1BQ1RK1 w - - 0 7",
};

template<class B, Color Us>
uint64_t perft_impl(typename B::Position &p, int depth) {
    typename B::Move moves[B::MAX_MOVES];
    typename B::Move *end = B::template generate_legals<Us>(p, moves);
    if (depth == 1) return uint64_t(end - moves);
    uint64_t n = 0;
    for (auto *m = moves; m != end; ++m) {
        B::template play<Us>(p, *m);
        n += perft_impl<B, ~Us>(p, depth - 1);
        B::template undo<Us>(p, *m);
    }
    return n;
}

} // namespace

template<class B>
uint64_t perft(typename B::Position &p, int depth) {
    if (depth <= 0) return 1;
    return B::turn(p) == WHITE ? perft_impl<B, WHITE>(p, depth) : perft_impl<B, BLACK>(p, depth);
}

template<class B>
Engine<B>::Engine() : pos(make_unique<Position>()) {
    set_position(B::START_FEN, {});
}

template<class B>
void Engine<B>::set_position(const string &fen, const vector<string> &moves) {
    pos = make_unique<Position>();
    state.halfmove = B::set_fen(*pos, fen);
    state.key_history.clear();
    plies_since_setup = 0;

    // Full move number from the FEN, if present
    istringstream ss(fen);
    string field;
    fullmove = 1;
    for (int i = 0; i < 6 && ss >> field; ++i)
        if (i == 5) fullmove = max(1, atoi(field.c_str()));

    for (const string &uci: moves) {
        Move m = B::parse_uci(*pos, uci);
        if (B::is_null(m)) {
            cerr << "info string illegal move " << uci << endl;
            break;
        }
        play(m);
    }
    state.fen = B::fen(*pos, state.halfmove, fullmove);
}

template<class B>
void Engine<B>::play(Move m) {
    state.key_history.push_back(B::key(*pos));
    state.halfmove = B::resets_halfmove(*pos, m) ? 0 : state.halfmove + 1;
    if (B::turn(*pos) == BLACK) ++fullmove;

    if (B::turn(*pos) == WHITE) B::template play<WHITE>(*pos, m);
    else B::template play<BLACK>(*pos, m);

    // surge keeps a fixed 256-entry undo history; rebuild the board before it can overflow
    if (++plies_since_setup > 200) {
        string fen = B::fen(*pos, state.halfmove, fullmove);
        pos = make_unique<Position>();
        B::set_fen(*pos, fen);
        plies_since_setup = 0;
    }
    state.fen = B::fen(*pos, state.halfmove, fullmove);
}

template<class B>
bool Engine<B>::is_draw() const {
    if (state.halfmove >= 100 || B::insufficient_material(*pos)) return true;
    const uint64_t k = B::key(*pos);
    int n = 0, limit = min<int>(state.halfmove, int(state.key_history.size()));
    for (int i = 1; i <= limit; ++i)
        if (state.key_history[state.key_history.size() - i] == k) ++n;
    return n >= 2;
}

template<class B>
bool Engine<B>::book_or_tablebase_move(Move &m) {
    if constexpr (B::HAS_BOOK) {
        if (own_book) {
            if (!book.loaded()) {
                if (book.load_from_csv(book_file)) cout << "info string loaded " << book.size() << " book positions" << endl;
                else {
                    cout << "info string could not open book " << book_file << endl;
                    own_book = false;
                }
            }
            if (book.loaded() && book.probe(*pos, m)) return true;
        }
        EndgameDB::WDL wdl;
        return endgame_db.probe_root(*pos, state.halfmove, m, wdl);
    }
    return false;
}

template<class B>
typename B::Move Engine<B>::best_move(const SearchLimits &limits, bool print_info) {
    Move m;
    if (book_or_tablebase_move(m)) return m;
    return search.think(state, limits, print_info).best;
}

template<class B>
void Engine<B>::go(istringstream &is) {
    SearchLimits limits;
    string token;
    bool perft_mode = false;
    int perft_depth = 0;

    while (is >> token) {
        if (token == "wtime") is >> limits.time[WHITE];
        else if (token == "btime") is >> limits.time[BLACK];
        else if (token == "winc") is >> limits.inc[WHITE];
        else if (token == "binc") is >> limits.inc[BLACK];
        else if (token == "movestogo") is >> limits.movestogo;
        else if (token == "movetime") is >> limits.movetime;
        else if (token == "depth") is >> limits.depth;
        else if (token == "nodes") is >> limits.nodes;
        else if (token == "infinite") limits.infinite = true;
        else if (token == "perft") { perft_mode = true; is >> perft_depth; }
    }

    if (perft_mode) {
        auto start = chrono::steady_clock::now();
        uint64_t n = perft<B>(*pos, perft_depth);
        auto ms = chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now() - start).count();
        cout << "nodes " << n << " time " << ms << endl;
        return;
    }

    limits.depth = clamp(limits.depth, 1, MAX_PLY - 1);

    Move m;
    if (!limits.infinite && book_or_tablebase_move(m)) {
        cout << "bestmove " << B::move_to_uci(m) << endl;
        return;
    }
    search.start(state, limits, true);
}

// Options that don't apply to this board (e.g. the book for variants) are ignored silently: the
// front end sends every option to every engine.
template<class B>
void Engine<B>::setoption(istringstream &is) {
    string token, name, value;
    is >> token; // "name"
    while (is >> token && token != "value") name += (name.empty() ? "" : " ") + token;
    while (is >> token) value += (value.empty() ? "" : " ") + token;

    for (auto &c: name) c = char(tolower(c));

    if (name == "hash") search.set_hash(clamp(stoi(value), 1, 65536));
    else if (name == "threads") search.set_threads(clamp(stoi(value), 1, 256));
    else if (name == "move overhead") search.time.move_overhead = clamp(stoi(value), 0, 5000);
    else if (name == "usennue") search.use_nnue = value == "true";
    else if constexpr (B::HAS_BOOK) {
        if (name == "ownbook") own_book = value == "true";
        else if (name == "bookfile") book_file = value;
    }
}

template<class B>
void Engine<B>::uci_position(istringstream &is) {
    string token, fen;
    is >> token;
    if (token == "startpos") {
        fen = B::START_FEN;
        is >> token; // "moves"
    } else if (token == "fen") {
        while (is >> token && token != "moves") fen += token + " ";
    } else {
        return;
    }
    vector<string> moves;
    while (is >> token) moves.push_back(token);
    set_position(fen, moves);
}

template<class B>
void Engine<B>::bench(int depth) {
    vector<string> fens = {B::START_FEN};
    if constexpr (is_same_v<B, StandardBoard>) fens = BENCH_FENS;

    uint64_t nodes = 0;
    auto start = chrono::steady_clock::now();
    for (const string &fen: fens) {
        set_position(fen, {});
        search.clear();
        SearchLimits limits;
        limits.depth = depth;
        search.think(state, limits, false);
        nodes += search.total_nodes();
    }
    auto ms = max<int64_t>(1, chrono::duration_cast<chrono::milliseconds>(chrono::steady_clock::now() - start).count());
    cout << nodes << " nodes " << nodes * 1000 / ms << " nps" << endl;
    set_position(B::START_FEN, {});
}

template<class B>
void Engine<B>::command(const string &line) {
    istringstream is(line);
    string token;
    if (!(is >> token)) return;

    if (token == "isready") {
        cout << "readyok" << endl;
    } else if (token == "setoption") {
        search.wait();
        setoption(is);
    } else if (token == "ucinewgame") {
        search.stop();
        search.clear();
    } else if (token == "position") {
        search.wait();
        uci_position(is);
    } else if (token == "go") {
        search.stop();
        search.wait();
        go(is);
    } else if (token == "stop") {
        search.stop();
        search.wait();
    } else if (token == "ponderhit") {
        // pondering is not supported; nothing to do
    } else if (token == "d") {
        B::print(cout, *pos);
        cout << "FEN: " << state.fen << "\nKey: " << hex << B::key(*pos) << dec << endl;
    } else if (token == "eval") {
        cout << "classical " << B::evaluate(*pos) << " (side to move)" << endl;
        if constexpr (B::HAS_NNUE)
            if (B::nnue_loaded()) cout << "nnue " << nnue::evaluate(*pos) << " (side to move)" << endl;
    } else if (token == "bench") {
        int depth = 13;
        is >> depth;
        bench(depth);
    } else {
        cout << "info string unknown command " << token << endl;
    }
}

#define INSTANTIATE_ENGINE(B) \
    template class Engine<B>; \
    template uint64_t perft<B>(B::Position &, int);
WOMBAT_FOR_EACH_BOARD(INSTANTIATE_ENGINE)

// ---------------------------------------------------------------------------------------------

namespace {

template<class B>
void add_engine_factory(map<string, unique_ptr<EngineBase> (*)()> &factories) {
    factories[B::NAME] = [] { return unique_ptr<EngineBase>(new Engine<B>()); };
}

const map<string, unique_ptr<EngineBase> (*)()> &engine_factories() {
    static const auto factories = [] {
        map<string, unique_ptr<EngineBase> (*)()> f;
#define ADD_FACTORY(B) add_engine_factory<B>(f);
        WOMBAT_FOR_EACH_BOARD(ADD_FACTORY)
        return f;
    }();
    return factories;
}

} // namespace

Uci::Uci() = default;

EngineBase &Uci::engine() {
    auto &e = engines[variant];
    if (!e) {
        e = engine_factories().at(variant)();
        for (const string &opt: options) e->command(opt);
    }
    return *e;
}

bool Uci::command(const string &line) {
    istringstream is(line);
    string token;
    if (!(is >> token)) return true;

    if (token == "uci") {
        cout << "id name Wombat\n"
             << "id author Fabian Schuller\n"
             << "option name Hash type spin default 64 min 1 max 65536\n"
             << "option name Threads type spin default 1 min 1 max 256\n"
             << "option name Move Overhead type spin default 30 min 0 max 5000\n"
             << "option name UseNNUE type check default true\n"
             << "option name EvalFile type string default <embedded>\n"
             << "option name OwnBook type check default false\n"
             << "option name BookFile type string default data/my_openings_l.csv\n"
             << "option name SyzygyPath type string default <empty>\n"
             << "option name UCI_Variant type combo default chess";
        for (auto &[name, factory]: engine_factories()) cout << " var " << name;
        cout << "\nuciok" << endl;
        return true;
    }
    if (token == "quit") {
        for (auto &[name, e]: engines) e->stop();
        return false;
    }
    if (token == "setoption") {
        string name_token, name, value;
        is >> name_token;
        while (is >> token && token != "value") name += (name.empty() ? "" : " ") + token;
        while (is >> token) value += (value.empty() ? "" : " ") + token;
        string lower = name;
        for (auto &c: lower) c = char(tolower(c));

        if (lower == "uci_variant") {
            if (!engine_factories().count(value)) {
                cout << "info string unknown variant " << value << endl;
                return true;
            }
            if (value != variant) engine().stop();
            variant = value;
            engine();
            return true;
        }
        // Global options (shared by all variants)
        if (lower == "evalfile") {
            if (nnue::load(value)) cout << "info string loaded network " << value << endl;
            else cout << "info string could not load network " << value << endl;
            return true;
        }
        if (lower == "syzygypath") {
            if (endgame_db.load(value)) cout << "info string found " << endgame_db.max_pieces() << "-piece tablebases" << endl;
            return true;
        }
        static const vector<string> known = {"hash", "threads", "move overhead", "usennue", "ownbook", "bookfile"};
        if (find(known.begin(), known.end(), lower) == known.end()) {
            cout << "info string unknown option " << name << endl;
            return true;
        }
        options.push_back(line);
        for (auto &[n, e]: engines) e->command(line);
        return true;
    }
    engine().command(line);
    return true;
}

void Uci::loop() {
    string line;
    while (getline(cin, line)) {
        if (!command(line)) break;
    }
    for (auto &[name, e]: engines) e->stop();
}
