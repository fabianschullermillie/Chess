//
// Created by fabian on 9/20/25.
//

#include "tt.h"

#include <algorithm>
#include <cstring>

#include "score.h"

void TranspositionTable::resize(size_t mb) {
    size_t count = std::max<size_t>(1, mb * 1024 * 1024 / sizeof(Bucket));
    buckets.assign(count, Bucket{});
    buckets.shrink_to_fit();
    generation = 0;
}

void TranspositionTable::clear() {
    std::memset(buckets.data(), 0, buckets.size() * sizeof(Bucket));
    generation = 0;
}

// data layout: move(16) | score(16) | eval(16) | depth(8) | bound(2) | generation(6)
static inline uint64_t pack(uint16_t move, int score, int eval, int depth, Bound bound, uint8_t gen) {
    return uint64_t(move)
           | uint64_t(uint16_t(int16_t(score))) << 16
           | uint64_t(uint16_t(int16_t(eval))) << 32
           | uint64_t(uint8_t(depth)) << 48
           | uint64_t(bound) << 56
           | uint64_t(gen) << 58;
}

static inline int depth_of(uint64_t d) { return int((d >> 48) & 0xFF); }
static inline uint8_t gen_of(uint64_t d) { return uint8_t(d >> 58); }

bool TranspositionTable::probe(uint64_t key, TTData &out) const {
    const Bucket &b = buckets[index(key)];
    for (const Entry &e: b.entries) {
        uint64_t data = e.data;
        if ((e.check ^ data) != key || data == 0) continue;
        out.move = uint16_t(data & 0xFFFF);
        out.score = int16_t((data >> 16) & 0xFFFF);
        out.eval = int16_t((data >> 32) & 0xFFFF);
        out.depth = depth_of(data);
        out.bound = Bound((data >> 56) & 3);
        return true;
    }
    return false;
}

void TranspositionTable::store(uint64_t key, uint16_t move, int score, int eval, int depth, Bound bound) {
    Bucket &b = buckets[index(key)];
    Entry *replace = &b.entries[0];
    int worst = 1 << 30;

    for (Entry &e: b.entries) {
        uint64_t data = e.data;
        if ((e.check ^ data) == key) {
            // Same position: keep the old best move if we have none, and don't overwrite
            // deeper exact information with a shallow bound
            if (move == 0) move = uint16_t(data & 0xFFFF);
            if (bound != BOUND_EXACT && depth + 3 < depth_of(data) && gen_of(data) == generation) return;
            replace = &e;
            worst = -1;
            break;
        }
        // Prefer replacing empty, old, or shallow entries
        int age = (generation - gen_of(data)) & 0x3F;
        int value = data == 0 ? -1000 : depth_of(data) - 8 * age;
        if (value < worst) {
            worst = value;
            replace = &e;
        }
    }

    uint64_t data = pack(move, score, eval, depth, bound, generation);
    replace->data = data;
    replace->check = key ^ data;
}

int TranspositionTable::hashfull() const {
    int used = 0;
    size_t n = std::min<size_t>(buckets.size(), 250);
    for (size_t i = 0; i < n; ++i)
        for (const Entry &e: buckets[i].entries)
            if (e.data && gen_of(e.data) == generation) ++used;
    return int(used * 1000 / (n * BUCKET_SIZE));
}

int score_to_tt(int score, int ply) {
    if (score >= MATE_IN_MAX) return score + ply;
    if (score <= -MATE_IN_MAX) return score - ply;
    return score;
}

int score_from_tt(int score, int ply) {
    if (score >= MATE_IN_MAX) return score - ply;
    if (score <= -MATE_IN_MAX) return score + ply;
    return score;
}
