import { Chessground } from './vendor/chessground.js';
import { Chess } from './vendor/chess.js';
import { VariantGame, TOROIDAL } from './variants.js';
import { TorusView } from './torus3d.js';

const $ = id => document.getElementById(id);
// For optional elements (the engine statistics panel is currently disabled in index.html)
const setText = (id, text) => { const el = $(id); if (el) el.textContent = text; };
const STANDARD_FEN = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

// ---------------------------------------------------------------------------------------------
// Variants: orthodox chess uses chess.js, toroidal chess our own rules (variants.js) with the same API

function variant() { return $('variant').value; }
function isToroidal() { return variant() === 'toroidal'; }
function startFenOf(v = variant()) { return v === 'toroidal' ? TOROIDAL.startFen : STANDARD_FEN; }
function makeGame(fen = startFenOf()) { return isToroidal() ? new VariantGame(fen, TOROIDAL) : new Chess(fen); }
const MAX_THREADS = Math.min(navigator.hardwareConcurrency || 4, 32); // matches PTHREAD_POOL_SIZE

// ---------------------------------------------------------------------------------------------
// Engine

let worker = null;
let engineReady = false;
// Every "go" produces exactly one "bestmove", in order; stale results are ignored by tag.
const goQueue = [];
let searchTag = 0;
let onBestMove = null;

function uci(cmd) { worker.postMessage(cmd); }

function startEngine() {
    if (!window.crossOriginIsolated || typeof SharedArrayBuffer === 'undefined') {
        setStatus('This page needs cross-origin isolation for multithreading. Reload the page once; ' +
            'if it still fails, your browser does not support SharedArrayBuffer.', true);
        return;
    }
    worker = new Worker('engine-worker.js');
    worker.onmessage = e => handleLine(e.data);
    worker.onerror = e => setStatus('Engine failed to start: ' + e.message, true);
    uci('uci');
    applyOptions();
    uci('isready');
}

function applyOptions() {
    uci('setoption name UCI_Variant value ' + variant());
    uci('setoption name Threads value ' + $('threads').value);
    uci('setoption name Hash value ' + $('hash').value);
    uci('setoption name UseNNUE value ' + $('nnue').value);
    setText('threads-info', `${evalName()}, ${$('threads').value} thread${$('threads').value === '1' ? '' : 's'}, ${$('hash').value} MB`);
}

function evalName() {
    if (isToroidal()) return 'Classical';
    return $('nnue').value === 'true' ? 'NNUE' : 'Classical';
}

function handleLine(line) {
    if (line.startsWith('wombat error')) return setStatus(line, true);
    if (line === 'readyok' && !engineReady) {
        engineReady = true;
        update();
        return;
    }
    if (line.startsWith('info ') && line.includes(' pv ')) return showInfo(line);
    if (line.startsWith('bestmove')) {
        const tag = goQueue.shift();
        if (tag === searchTag && onBestMove) {
            const cb = onBestMove;
            onBestMove = null;
            cb(line.split(' ')[1]);
        }
    }
}

function search(goArgs, callback) {
    stopSearch();
    searchTag++;
    goQueue.push(searchTag);
    onBestMove = callback;
    thinkingFen = game.fen();
    clearInfo();
    uci(`position fen ${startFen} moves ${history.map(m => m.lan).join(' ')}`.trim());
    uci('go ' + goArgs);
}

function stopSearch() {
    if (goQueue.length && goQueue[goQueue.length - 1] === searchTag) uci('stop');
    searchTag++;
    onBestMove = null;
}

// ---------------------------------------------------------------------------------------------
// Engine info display

let thinkingFen = null;

function clearInfo() {
    for (const id of ['st-depth', 'st-score', 'st-nps', 'st-nodes']) setText(id, '–');
    setText('pv', '');
}

function showInfo(line) {
    if (thinkingFen !== game.fen()) return;
    const tok = line.split(' ');
    const get = key => tok[tok.indexOf(key) + 1];
    const whiteToMove = game.turn() === 'w';

    let scoreText, whiteCp;
    const si = tok.indexOf('score');
    if (tok[si + 1] === 'mate') {
        const m = +tok[si + 2];
        const whiteMate = (m > 0) === whiteToMove;
        scoreText = (whiteMate ? '+' : '-') + 'M' + Math.abs(m);
        whiteCp = whiteMate ? 10000 : -10000;
    } else {
        const cp = +tok[si + 2];
        whiteCp = whiteToMove ? cp : -cp;
        scoreText = (whiteCp > 0 ? '+' : '') + (whiteCp / 100).toFixed(2);
    }

    setText('st-depth', get('depth') + '/' + get('seldepth'));
    setText('st-score', scoreText);
    setText('st-nps', formatCount(+get('nps')) + 'n/s');
    setText('st-nodes', formatCount(+get('nodes')));
    if ($('pv')) setText('pv', pvToSan(tok.slice(tok.indexOf('pv') + 1)));
    setEvalBar(whiteCp, scoreText);
}

function formatCount(n) {
    if (n >= 1e9) return (n / 1e9).toFixed(1) + 'G';
    if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(0) + 'k';
    return String(n);
}

function pvToSan(moves) {
    const g = makeGame(game.fen());
    const out = [];
    for (const m of moves) {
        const moveNo = g.moveNumber();
        let san;
        try { san = g.move(m).san; } catch { break; }
        if (g.turn() === 'b') out.push(moveNo + '. ' + san);
        else out.push((out.length ? '' : moveNo + '… ') + san);
    }
    return out.join(' ');
}

function setEvalBar(whiteCp, label) {
    // Logistic mapping, like lichess
    const white = 50 + 50 * (2 / (1 + Math.exp(-0.004 * whiteCp)) - 1);
    $('evalbar-fill').style.height = white + '%';
    $('evalbar-label').textContent = label.replace(/^\+/, '').replace(/\.(\d)\d$/, '.$1');
}

// ---------------------------------------------------------------------------------------------
// Game state

let game = new Chess();
let startFen = STANDARD_FEN;
let history = []; // chess.js move objects
let humanColor = 'white';
let orientation = 'white';

const board = Chessground($('board'), {
    fen: STANDARD_FEN,
    animation: { duration: 180 },
    movable: { free: false, showDests: true, events: { after: onUserMove } },
    premovable: { enabled: false },
    draggable: { showGhost: true },
    highlight: { lastMove: true, check: true },
});

function mode() { return $('mode').value; }

function dests() {
    const d = new Map();
    for (const m of game.moves({ verbose: true })) {
        if (!d.has(m.from)) d.set(m.from, []);
        d.get(m.from).push(m.to);
    }
    return d;
}

function humanToMove() {
    if (game.isGameOver()) return false;
    if (mode() === 'analyze') return true;
    return (game.turn() === 'w') === (humanColor === 'white');
}

function update() {
    const turnColor = game.turn() === 'w' ? 'white' : 'black';
    const last = history[history.length - 1];
    board.set({
        fen: game.fen(),
        turnColor,
        orientation,
        check: game.inCheck(),
        lastMove: last ? [last.from, last.to] : undefined,
        movable: {
            color: humanToMove() ? turnColor : undefined,
            dests: humanToMove() ? dests() : new Map(),
        },
    });
    $('evalbar').classList.toggle('flipped', orientation === 'black');
    $('fen').value = game.fen();
    if (selected3d && !(humanToMove() && dests().has(selected3d))) selected3d = null;
    updateTorus();
    renderMoves();
    renderPlayers();

    if (!engineReady) return;

    if (game.isGameOver()) {
        stopSearch();
        setStatus(gameOverText());
    } else if (mode() === 'analyze') {
        setStatus('Analysing… move for either side.');
        search('infinite', null);
    } else if (!humanToMove()) {
        setStatus('Wombat is thinking…');
        search('movetime ' + $('movetime').value, uciMove => {
            playMove({ from: uciMove.slice(0, 2), to: uciMove.slice(2, 4), promotion: uciMove[4] });
        });
    } else {
        setStatus('Your move.');
    }
}

function gameOverText() {
    if (game.isCheckmate()) return `Checkmate — ${game.turn() === 'w' ? 'Black' : 'White'} wins.`;
    if (game.isStalemate()) return 'Draw by stalemate.';
    if (game.isThreefoldRepetition()) return 'Draw by threefold repetition.';
    if (game.isInsufficientMaterial()) return 'Draw by insufficient material.';
    return 'Draw by the fifty-move rule.';
}

function playMove(m) {
    const move = game.move(m);
    history.push(move);
    update();
}

function onUserMove(from, to) {
    const piece = game.get(from);
    const promo = piece && piece.type === 'p' && (to[1] === '8' || to[1] === '1');
    if (promo) askPromotion(piece.color, p => playMove({ from, to, promotion: p }));
    else playMove({ from, to });
}

function askPromotion(color, done) {
    const box = $('promotion');
    const glyphs = color === 'w' ? { q: '♕', r: '♖', b: '♗', n: '♘' } : { q: '♛', r: '♜', b: '♝', n: '♞' };
    box.replaceChildren(...Object.entries(glyphs).map(([p, g]) => {
        const b = document.createElement('button');
        b.textContent = g;
        b.style.fontSize = 'min(8vw, 52px)';
        b.onclick = () => { box.hidden = true; done(p); };
        return b;
    }));
    box.hidden = false;
}

function renderMoves() {
    const ol = $('moves');
    ol.replaceChildren();
    const start = makeGame(startFen);
    const firstBlack = start.turn() === 'b';
    const plies = history.map(m => m.san);
    if (firstBlack) plies.unshift('…');
    ol.start = start.moveNumber();
    for (let i = 0; i < plies.length; i += 2) {
        const li = document.createElement('li');
        for (const san of plies.slice(i, i + 2)) {
            const s = document.createElement('span');
            s.textContent = san;
            li.append(s);
        }
        ol.append(li);
    }
    ol.scrollTop = ol.scrollHeight;
}

function renderPlayers() {
    const engineName = `Wombat (${evalName()}, ${$('threads').value}T, ${(+$('movetime').value / 1000)} s/move)`;
    const names = mode() === 'analyze'
        ? { white: 'White', black: 'Black' }
        : { [humanColor]: 'You', [humanColor === 'white' ? 'black' : 'white']: engineName };
    const top = orientation === 'white' ? 'black' : 'white';
    $('player-top').textContent = names[top];
    $('player-bottom').textContent = names[orientation];
}

// The status line was removed from the page; messages only go to the console.
function setStatus(text, error = false) {
    (error ? console.error : console.log)('[wombat] ' + text);
}

function newGame(color, fen = startFenOf()) {
    stopSearch();
    game = makeGame(fen);
    startFen = fen;
    history = [];
    humanColor = color;
    orientation = color;
    $('promotion').hidden = true;
    selected3d = null;
    clearInfo();
    setEvalBar(0, '0.0');
    if (engineReady) uci('ucinewgame');
    update();
}

function undo() {
    stopSearch();
    // In play mode take back to the last position where it is the human's turn
    do {
        if (!history.length) break;
        game.undo();
        history.pop();
    } while (mode() === 'play' && (game.turn() === 'w') !== (humanColor === 'white'));
    update();
}

// ---------------------------------------------------------------------------------------------
// Controls

for (let t = 1; t <= MAX_THREADS; t++) $('threads').add(new Option(String(t), String(t)));
$('threads').value = String(Math.min(MAX_THREADS, 4));

$('new-white').onclick = () => { $('mode').value = 'play'; newGame('white'); };
$('new-black').onclick = () => { $('mode').value = 'play'; newGame('black'); };
$('undo').onclick = undo;
$('flip').onclick = () => {
    orientation = orientation === 'white' ? 'black' : 'white';
    board.set({ orientation });
    $('evalbar').classList.toggle('flipped', orientation === 'black');
    renderPlayers();
};
$('mode').onchange = () => { stopSearch(); update(); };
$('variant').onchange = () => setVariant(variant());
$('movetime').onchange = renderPlayers;
for (const id of ['threads', 'hash', 'nnue']) $(id).onchange = () => {
    stopSearch();
    applyOptions();
    if (id === 'nnue') uci('ucinewgame'); // don't reuse TT scores from the other evaluation
    renderPlayers();
    update();
};
$('fen-form').onsubmit = e => {
    e.preventDefault();
    const fen = $('fen').value.trim();
    let g;
    try {
        g = makeGame(fen);
    } catch (err) {
        return setStatus('Invalid FEN: ' + err.message, true);
    }
    newGame(g.turn() === 'w' ? 'white' : 'black', fen);
};
$('copy-pgn').onclick = async () => {
    let pgn;
    if (isToroidal()) {
        pgn = game.pgn(startFen);
    } else {
        const g = new Chess(startFen);
        if (startFen !== STANDARD_FEN) {
            g.setHeader('SetUp', '1');
            g.setHeader('FEN', startFen);
        }
        for (const m of history) g.move(m.san);
        pgn = g.pgn();
    }
    await navigator.clipboard.writeText(pgn);
    $('copy-pgn').textContent = 'Copied';
    setTimeout(() => ($('copy-pgn').textContent = 'Copy PGN'), 1200);
};

// ---------------------------------------------------------------------------------------------
// Variant switching and the 3D torus view

function setVariant(v) {
    $('variant').value = v;
    if (location.hash.slice(1) !== v) setHash(v);
    document.body.classList.toggle('toroidal', v === 'toroidal');
    $('nnue-label').hidden = v === 'toroidal'; // the network was trained on orthodox chess
    $('view3d').hidden = v !== 'toroidal';
    if (v !== 'toroidal' && torus && torusShown) toggle3d();
    if (engineReady) {
        stopSearch();
        applyOptions();
        uci('ucinewgame');
    }
    newGame(humanColor);
}

function setHash(v) {
    window.history.replaceState(null, '', '#' + v);
}

let torus = null;
let torusShown = false;
let torusBusy = false;
let selected3d = null;

function cssVar(name) { return getComputedStyle(document.documentElement).getPropertyValue(name).trim(); }

function updateTorus() {
    if (!torus) return;
    const pieces = new Map();
    for (const f of 'abcdefgh') for (let r = 1; r <= 8; r++) {
        const p = game.get(f + r);
        if (p) pieces.set(f + r, p);
    }
    const last = history[history.length - 1];
    torus.update({
        pieces,
        orientation,
        lastMove: last ? [last.from, last.to] : null,
        check: game.inCheck() ? game.turn() : null,
        selected: selected3d,
        dests: selected3d ? dests().get(selected3d) ?? [] : [],
    });
}

// Click-to-move on the torus: select one of your pieces, then a highlighted square
function onTorusClick(square) {
    if (!humanToMove()) return;
    const d = dests();
    if (selected3d && (d.get(selected3d) ?? []).includes(square)) {
        const from = selected3d;
        selected3d = null;
        onUserMove(from, square);
        return;
    }
    selected3d = d.has(square) ? (selected3d === square ? null : square) : null;
    updateTorus();
}

async function toggle3d() {
    if (torusBusy) return;
    torusBusy = true;
    $('view3d').disabled = true;
    if (!torus) {
        torus = new TorusView($('board-wrap'), {
            onSquareClick: onTorusClick,
            colors: {
                light: cssVar('--sq-light'), dark: cssVar('--sq-dark'),
                neon: cssVar('--neon'), neonFaint: 'rgba(57, 255, 20, 0.28)',
            },
        });
    }
    updateTorus();
    if (torusShown) {
        $('view3d').textContent = 'Back to 2D…';
        await torus.hide();
        torusShown = false;
        $('view3d').textContent = 'Show torus (3D)';
    } else {
        $('view3d').textContent = 'Rolling up…';
        await torus.show();
        torusShown = true;
        $('view3d').textContent = 'Flatten to 2D';
    }
    $('view3d').disabled = false;
    torusBusy = false;
}

$('view3d').onclick = toggle3d;

renderPlayers();
setVariant(location.hash.slice(1) === 'chess' ? 'chess' : 'toroidal');
startEngine();
