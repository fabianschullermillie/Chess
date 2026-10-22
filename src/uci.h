//
// UCI protocol front end.
//
// Engine<B> plays one board implementation (see board_api.h). Uci owns one engine per supported
// variant, created on demand, and switches between them with the UCI_Variant option.
//

#ifndef CHESS_UCI_H
#define CHESS_UCI_H

#pragma once

#include <map>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "OpeningDB.h"
#include "search.h"

// What the front end needs from an engine, independent of its board type
class EngineBase {
public:
    virtual ~EngineBase() = default;
    // Handles one command line (everything except "uci" and UCI_Variant)
    virtual void command(const std::string &line) = 0;
    virtual void stop() = 0;
};

template<class B>
class Engine : public EngineBase {
public:
    using Position = typename B::Position;
    using Move = typename B::Move;

    Engine();

    void command(const std::string &line) override;
    void stop() override {
        search.stop();
        search.wait();
    }

    // Fixed-depth search over a set of positions; prints total nodes and nps (for regression checks)
    void bench(int depth);

    void set_position(const std::string &fen, const std::vector<std::string> &moves);
    // Book / tablebase / search; blocking. Used by the interactive mode.
    Move best_move(const SearchLimits &limits, bool print_info);

    // Plain search without book / tablebases, no UCI output (used by datagen and self-play)
    SearchResult<B> think(const SearchLimits &limits) { return search.think(state, limits, false); }
    void new_game() { search.clear(); }

    Position &position() { return *pos; }
    const GameState &game() const { return state; }
    void play(Move m);

    // Repetition (threefold), fifty-move rule or insufficient material
    bool is_draw() const;

private:
    void go(std::istringstream &is);
    void setoption(std::istringstream &is);
    void uci_position(std::istringstream &is);
    bool book_or_tablebase_move(Move &m);

    Search<B> search;
    OpeningDB book;
    std::unique_ptr<Position> pos;
    GameState state;
    int fullmove = 1;
    int plies_since_setup = 0;

    bool own_book = false;
    std::string book_file = "data/my_openings_l.csv";
};

class Uci {
public:
    Uci();

    // Reads UCI commands from stdin until "quit"
    void loop();
    // Handles one command line; returns false on "quit"
    bool command(const std::string &line);

private:
    EngineBase &engine();

    std::map<std::string, std::unique_ptr<EngineBase>> engines;
    std::string variant = "chess";
    // setoption lines, replayed on engines that are created later
    std::vector<std::string> options;
};

template<class B>
uint64_t perft(typename B::Position &p, int depth);

#endif //CHESS_UCI_H
