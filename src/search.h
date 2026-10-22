//
// Created by fabian on 9/19/25.
//

#ifndef CHESS_SEARCH_H
#define CHESS_SEARCH_H

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "board_api.h"
#include "score.h"
#include "timeman.h"
#include "tt.h"

// Everything a search needs to know about the game so far
struct GameState {
    std::string fen;                  // root position
    std::vector<uint64_t> key_history; // keys of positions before the root (for repetitions)
    int halfmove = 0;                 // fifty-move counter at the root
};

template<class B>
struct SearchResult {
    typename B::Move best{};
    typename B::Move ponder{};
    int score = 0;
    int depth = 0;
};

template<class B> class Worker;

// The search is generic over the board implementation (see board_api.h). It is instantiated in
// search.cpp for every board the engine supports.
template<class B>
class Search {
public:
    Search();
    ~Search();

    void set_hash(size_t mb) { tt.resize(mb); }
    void set_threads(int n);
    void clear(); // ucinewgame

    // Starts searching in the background; bestmove is reported through on_done.
    void start(const GameState &game, const SearchLimits &limits, bool print_info = true);
    void stop() { stop_flag = true; }
    void wait();
    bool searching() const { return running; }

    // Blocking convenience wrapper (used by the interactive CLI and bench)
    SearchResult<B> think(const GameState &game, const SearchLimits &limits, bool print_info);

    uint64_t total_nodes() const;

    TranspositionTable tt;
    std::atomic<bool> stop_flag{false};
    TimeManager time;
    bool print_info = true;
    bool use_nnue = true; // falls back to the hand-crafted eval if no network is loaded

private:
    friend class Worker<B>;
    void run(GameState game, SearchLimits limits);

    std::vector<std::unique_ptr<Worker<B>>> workers;
    std::thread main_thread;
    std::atomic<bool> running{false};
    SearchResult<B> result;
};

#endif //CHESS_SEARCH_H
