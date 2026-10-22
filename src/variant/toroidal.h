//
// Board types for the variants the engine plays.
//

#ifndef CHESS_VARIANT_TOROIDAL_H
#define CHESS_VARIANT_TOROIDAL_H

#pragma once

#include "variant_board.h"

struct ToroidalTag {
    static const variant::Variant &get() {
        static const variant::Variant v(variant::toroidal_rules());
        return v;
    }
};
using ToroidalBoard = VariantBoard<ToroidalTag>;

// Orthodox chess on the generic board. Not offered over UCI; used to test the variant move
// generator against surge.
struct GenericChessTag {
    static const variant::Variant &get() {
        static const variant::Variant v(variant::standard_rules());
        return v;
    }
};
using GenericChessBoard = VariantBoard<GenericChessTag>;

#endif //CHESS_VARIANT_TOROIDAL_H
