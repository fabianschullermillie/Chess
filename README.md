# Wombat
**Play toroidal chess against it in the browser: https://fabianschullermillie.github.io/Chess/**
(with a 3D view that rolls the board up into a torus)

- UCI chess engine in C++, also plays toroidal chess (`setoption name UCI_Variant value toroidal`)
- PVS with aspiration windows, lock-free transposition table, Lazy SMP
- Null move, reverse futility, futility, late move pruning/reductions, SEE pruning
- NNUE evaluation ((768→256)x2→1, SCReLU, incrementally updated), trained on self-play data;
  the tapered hand-crafted evaluation remains as fallback (`UseNNUE false`)
- Beats Stockfish at UCI_Elo 1800 200-0 (10+0.1), see `benchmarks/` and `Changes.md`

### Build & Run
```
git submodule update --init lib/surge lib/Fathom
cmake -S . -B build-rel -DCMAKE_BUILD_TYPE=Release && cmake --build build-rel -j
./build-rel/Wombat          # UCI mode (use with any chess GUI)
./build-rel/Wombat play     # play against it in the terminal
./build-rel/Wombat bench    # node count / speed check
```

### Toroidal chess and other variants
On a torus the a- and h-files are adjacent, and so are the 1st and 8th ranks. The standard setup
would start with both kings in check, so the engine uses the setup from Wikipedia's
[cylinder chess](https://en.wikipedia.org/wiki/Cylinder_chess) article:
`r1b2b1r/pp4pp/n1pqkp1n/3pp3/3PP3/N1PQKP1N/PP4PP/R1B2B1R w - - 0 1`.
No castling, only pawns on the 2nd/7th rank may double step, pawns promote on the last rank.

How it fits together:
- `src/board_api.h`: the search, move ordering and UCI engine are templates over a `Board`
  traits type (a C++20 concept), resolved at compile time. `StandardBoard` wraps surge with
  forwarding inline functions, so orthodox chess runs exactly as fast as before (`bench` node
  counts are unchanged).
- `src/variant/`: a readable, data-driven board for variants. A variant is a `Rules` object
  (geometry with optionally wrapping edges, pieces as leaps/slides, pawn and castling rules,
  start FEN); move generation, SEE and a small evaluation are written once against it. With
  orthodox rules it reproduces surge's perft numbers (`build-rel/VariantTests`).
- `web/variants.js`: the same rules in JavaScript for the web page (perft-checked against the
  engine with `node web/variants.test.mjs`), `web/torus3d.js`: the Three.js torus view.

```
./build-rel/Wombat selfplay toroidal 4 1000   # engine vs engine, 4 games at 1 s/move
./build-rel/Wombat play 3000 toroidal         # play in the terminal
./build-rel/VariantTests                      # move generator checks
```
See `games/toroidal_selfplay.txt` for some self-play games.

### Web demo
Play against Wombat in the browser: https://fabianschullermillie.github.io/Chess/ (multithreaded WebAssembly).
```
web/build.sh                 # needs Emscripten; builds build-wasm/site
python3 web/serve.py         # local test server on :8000 with the COOP/COEP headers
```
The site is deployed from the `gh-pages` branch (contents of `build-wasm/site`).

### Training the network
```
./build-rel/Wombat datagen nnue/data/d1.txt 10000 5000 1   # <out> <games> <nodes/move> <seed>, run several in parallel
python3 nnue/train.py --data "nnue/data/*.txt" --cache nnue/data/cache.npz --out nnue/wombat.nnue --epochs 20
# rebuild: nnue/wombat.nnue is embedded into the binary
```

### Third Party Libraries
- [surge](https://github.com/nkarve/surge), slightly modified (bitboards, move generation, zobrist hashing)
- [Fathom](https://github.com/jdart1/Fathom) (probing of endgame-tablebases)
