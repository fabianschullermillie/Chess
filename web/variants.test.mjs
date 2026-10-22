// Checks the JavaScript toroidal rules against the engine: perft numbers must match the C++
// variant board (build/VariantTests prints the same table). Run: node web/variants.test.mjs
import { VariantGame, perft, TOROIDAL } from './variants.js';
import assert from 'node:assert/strict';

const ENGINE_PERFT = [49, 2033, 88010, 3815789]; // from tests/variant_tests.cpp

const game = new VariantGame(TOROIDAL.startFen);
// depth 4 takes about a minute; pass "full" to include it
ENGINE_PERFT.slice(0, process.argv.includes("full") ? 4 : 3).forEach((expected, i) => {
    const t = Date.now();
    const n = perft(game, i + 1);
    console.log(`depth ${i + 1}: ${n} (${Date.now() - t} ms)`);
    assert.equal(n, expected);
});
assert.equal(game.fen(), TOROIDAL.startFen);

// SAN, wrap-around captures and FEN round trip
const g = new VariantGame();
const rookTakes = g.move('a1a8');           // the a1 rook captures across the 1st/8th rank seam
assert.equal(rookTakes.san, 'Rxa8');
assert.equal(g.move('h8a8').san, 'Rxa8');  // recapture along the 8th rank across the h/a seam
assert.equal(new VariantGame(g.fen()).fen(), g.fen());
g.undo(); g.undo();
assert.equal(g.fen(), TOROIDAL.startFen);
console.log('all checks passed');
