//
// Fixed-size, lock-free transposition table shared between search threads.
// Each entry stores (key ^ data) next to data, so torn writes from concurrent threads
// are detected on probe instead of returning garbage.
//

#ifndef CHESS_TT_H
#define CHESS_TT_H

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

enum Bound : uint8_t {
    BOUND_NONE = 0,
    BOUND_UPPER = 1, // fail-low: score is an upper bound
    BOUND_LOWER = 2, // fail-high: score is a lower bound
    BOUND_EXACT = 3
};

struct TTData {
    uint16_t move; // board-specific compact move (Board::move_code), 0 = none
    int score;
    int eval;
    int depth;
    Bound bound;
};

class TranspositionTable {
public:
    explicit TranspositionTable(size_t mb = 64) { resize(mb); }

    void resize(size_t mb);
    void clear();
    void new_search() { generation = (generation + 1) & 0x3F; }

    bool probe(uint64_t key, TTData &out) const;
    void store(uint64_t key, uint16_t move, int score, int eval, int depth, Bound bound);

    void prefetch(uint64_t key) const { __builtin_prefetch(&buckets[index(key)]); }

    // Permille of the table filled with entries from the current search
    int hashfull() const;

private:
    struct Entry {
        uint64_t check; // key ^ data
        uint64_t data;
    };
    static constexpr int BUCKET_SIZE = 4;
    struct alignas(64) Bucket {
        Entry entries[BUCKET_SIZE];
    };

    size_t index(uint64_t key) const { return static_cast<size_t>((static_cast<__uint128_t>(key) * buckets.size()) >> 64); }

    std::vector<Bucket> buckets;
    uint8_t generation = 0;
};

// Mate scores are stored relative to the node, not the root
int score_to_tt(int score, int ply);
int score_from_tt(int score, int ply);

#endif //CHESS_TT_H
