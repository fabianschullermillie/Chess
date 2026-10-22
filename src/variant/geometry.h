//
// The shape of the board: its size and which edges are glued together.
//
//   standard board:  no wrapping
//   cylinder:        the a- and h-files are adjacent (wrap_files)
//   torus:           files and ranks both wrap (wrap_files + wrap_ranks)
//
// Everything that "walks" over the board (piece moves, attack detection, pawn pushes) goes through
// Geometry::step, so supporting a new topology only means changing this one function.
//

#ifndef CHESS_VARIANT_GEOMETRY_H
#define CHESS_VARIANT_GEOMETRY_H

#pragma once

#include <algorithm>
#include <cstdlib>
#include <string>

#include "types.h"

namespace variant {

struct Geometry {
    int files = 8;
    int ranks = 8;
    bool wrap_files = false;
    bool wrap_ranks = false;

    int size() const { return files * ranks; }
    int file_of(Square s) const { return s % files; }
    int rank_of(Square s) const { return s / files; }
    Square square(int file, int rank) const { return rank * files + file; }

    // The square reached from s by moving `o`, or NO_SQUARE if that leaves the board
    Square step(Square s, Offset o) const {
        int f = file_of(s) + o.df;
        int r = rank_of(s) + o.dr;
        if (wrap_files) f = (f % files + files) % files;
        if (wrap_ranks) r = (r % ranks + ranks) % ranks;
        if (f < 0 || f >= files || r < 0 || r >= ranks) return NO_SQUARE;
        return square(f, r);
    }

    // Number of king steps between two squares (taking wrapped edges into account)
    int distance(Square a, Square b) const {
        int df = std::abs(file_of(a) - file_of(b));
        int dr = std::abs(rank_of(a) - rank_of(b));
        if (wrap_files) df = std::min(df, files - df);
        if (wrap_ranks) dr = std::min(dr, ranks - dr);
        return std::max(df, dr);
    }

    // "e4" style names
    std::string name(Square s) const {
        return std::string(1, char('a' + file_of(s))) + std::to_string(rank_of(s) + 1);
    }

    // Parses a square name at the start of `text`; returns NO_SQUARE on failure and sets `length`
    // to the number of characters consumed.
    Square parse(const std::string &text, size_t pos, size_t &length) const {
        length = 0;
        if (pos >= text.size()) return NO_SQUARE;
        int f = text[pos] - 'a';
        size_t i = pos + 1;
        int r = 0;
        while (i < text.size() && text[i] >= '0' && text[i] <= '9') r = r * 10 + (text[i++] - '0');
        if (f < 0 || f >= files || r < 1 || r > ranks) return NO_SQUARE;
        length = i - pos;
        return square(f, r - 1);
    }
};

} // namespace variant

#endif //CHESS_VARIANT_GEOMETRY_H
