//
// All board implementations the engine is built with. The search and the UCI engine are explicitly
// instantiated once per entry of WOMBAT_FOR_EACH_BOARD.
//

#ifndef CHESS_BOARDS_H
#define CHESS_BOARDS_H

#pragma once

#include "standard_board.h"

#define WOMBAT_FOR_EACH_BOARD(X) X(StandardBoard)

#endif //CHESS_BOARDS_H
