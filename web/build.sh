#!/usr/bin/env bash
# Builds the WebAssembly engine and assembles the static demo site in build-wasm/site.
# Requires Emscripten (emcmake on PATH). Serve with web/serve.py to test locally.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build-wasm"
SITE="$BUILD/site"

emcmake cmake -S "$ROOT" -B "$BUILD" -DCMAKE_BUILD_TYPE=Release > /dev/null
cmake --build "$BUILD" -j "${JOBS:-4}"

rm -rf "$SITE"
mkdir -p "$SITE"
cp "$BUILD/wombat.js" "$BUILD/wombat.wasm" "$SITE/"
cp -R "$ROOT/web/index.html" "$ROOT/web/style.css" "$ROOT/web/app.js" "$ROOT/web/variants.js" "$ROOT/web/torus3d.js" "$ROOT/web/engine-worker.js" \
      "$ROOT/web/coi-serviceworker.min.js" "$ROOT/web/vendor" "$SITE/"
touch "$SITE/.nojekyll"
echo "Site ready in $SITE"
