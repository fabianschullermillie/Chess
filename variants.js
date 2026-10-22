// Rules of chess variants for the web demo, mirroring the engine's variant board (src/variant):
// a variant is data (board shape, how pieces move, pawn rules, start position) and the move
// generator is written once against that description.
//
// VariantGame implements the part of chess.js's API the page uses (fen, moves, move, undo, get,
// game-over checks), so the UI code is the same for orthodox and variant games.
// Castling is not implemented here, since toroidal chess has none.

const ORTHOGONAL = [[1, 0], [-1, 0], [0, 1], [0, -1]];
const DIAGONAL = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
const ALL_DIRECTIONS = [...ORTHOGONAL, ...DIAGONAL];
const KNIGHT_JUMPS = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];

const ORTHODOX_PIECES = {
    p: { pawn: true },
    n: { leaps: KNIGHT_JUMPS, minor: true },
    b: { slides: DIAGONAL, minor: true },
    r: { slides: ORTHOGONAL },
    q: { slides: ALL_DIRECTIONS },
    k: { leaps: ALL_DIRECTIONS, royal: true },
};

// Toroidal chess: the a- and h-files are adjacent, and so are the 1st and 8th ranks.
// Setup from Wikipedia's "Cylinder chess" article; no castling; only pawns on the 2nd / 7th rank
// may double step; pawns promote on the last rank (they never cross the 8th/1st rank seam).
export const TOROIDAL = {
    name: 'toroidal',
    title: 'Toroidal chess',
    files: 8,
    ranks: 8,
    wrapFiles: true,
    wrapRanks: true,
    pieces: ORTHODOX_PIECES,
    doubleStepRank: { w: 1, b: 6 },
    promotionRank: { w: 7, b: 0 },
    promotions: ['q', 'r', 'b', 'n'],
    startFen: 'r1b2b1r/pp4pp/n1pqkp1n/3pp3/3PP3/N1PQKP1N/PP4PP/R1B2B1R w - - 0 1',
};

const other = c => (c === 'w' ? 'b' : 'w');

export class VariantGame {
    constructor(fen, rules = TOROIDAL) {
        this.rules = rules;
        this.load(fen ?? rules.startFen);
    }

    // ---- Board geometry -------------------------------------------------------------------

    square(file, rank) { return rank * this.rules.files + file; }
    fileOf(sq) { return sq % this.rules.files; }
    rankOf(sq) { return Math.floor(sq / this.rules.files); }
    name(sq) { return String.fromCharCode(97 + this.fileOf(sq)) + (this.rankOf(sq) + 1); }

    parseSquare(text) {
        const m = /^([a-z])(\d+)$/.exec(text ?? '');
        if (!m) return -1;
        const f = m[1].charCodeAt(0) - 97, r = +m[2] - 1;
        return f < this.rules.files && r >= 0 && r < this.rules.ranks ? this.square(f, r) : -1;
    }

    // Square reached by moving (df, dr) from sq, or -1 if that leaves the board
    step(sq, df, dr) {
        const { files, ranks, wrapFiles, wrapRanks } = this.rules;
        let f = this.fileOf(sq) + df, r = this.rankOf(sq) + dr;
        if (wrapFiles) f = ((f % files) + files) % files;
        if (wrapRanks) r = ((r % ranks) + ranks) % ranks;
        return f < 0 || f >= files || r < 0 || r >= ranks ? -1 : this.square(f, r);
    }

    // Squares along a direction, stopping at the edge or before returning to the start
    ray(sq, df, dr) {
        const out = [];
        for (let t = this.step(sq, df, dr); t !== -1 && t !== sq && !out.includes(t); t = this.step(t, df, dr))
            out.push(t);
        return out;
    }

    forward(color) { return color === 'w' ? 1 : -1; }

    // ---- FEN ------------------------------------------------------------------------------

    load(fen) {
        const { files, ranks, pieces } = this.rules;
        const [placement, turn = 'w', , ep = '-', halfmove = '0', fullmove = '1'] = fen.trim().split(/\s+/);
        const rows = placement.split('/');
        if (rows.length !== ranks) throw new Error(`expected ${ranks} ranks`);
        this.board = new Array(files * ranks).fill(null);
        rows.forEach((row, i) => {
            const rank = ranks - 1 - i;
            let file = 0;
            for (const m of row.matchAll(/(\d+)|([a-zA-Z])/g)) {
                if (m[1]) { file += +m[1]; continue; }
                const type = m[2].toLowerCase();
                if (!pieces[type]) throw new Error('unknown piece ' + m[2]);
                if (file >= files) throw new Error('rank too long');
                this.board[this.square(file++, rank)] = { type, color: m[2] === type ? 'b' : 'w' };
            }
            if (file !== files) throw new Error(`rank ${rank + 1} has ${file} squares`);
        });
        for (const c of ['w', 'b'])
            if (this.board.filter(p => p && p.type === 'k' && p.color === c).length !== 1)
                throw new Error('each side needs exactly one king');
        if (turn !== 'w' && turn !== 'b') throw new Error('bad side to move');
        this.side = turn;
        this.halfmove = parseInt(halfmove, 10) || 0;
        this.fullmove = parseInt(fullmove, 10) || 1;
        this.ep = -1;
        const epSq = this.parseSquare(ep);
        if (epSq !== -1 && this.pawnCanCapture(epSq, this.side)) this.ep = epSq;
        this.stack = [];
        this.keys = [this.key()];
        this.playedMoves = [];
        if (this.isAttacked(this.kingSquare(other(this.side)), this.side))
            throw new Error('the side not to move is in check');
    }

    fen() {
        const { files, ranks } = this.rules;
        const rows = [];
        for (let r = ranks - 1; r >= 0; r--) {
            let row = '', empty = 0;
            for (let f = 0; f < files; f++) {
                const p = this.board[this.square(f, r)];
                if (!p) { empty++; continue; }
                if (empty) row += empty;
                empty = 0;
                row += p.color === 'w' ? p.type.toUpperCase() : p.type;
            }
            rows.push(row + (empty ? empty : ''));
        }
        const ep = this.ep === -1 ? '-' : this.name(this.ep);
        return `${rows.join('/')} ${this.side} - ${ep} ${this.halfmove} ${this.fullmove}`;
    }

    // Identifies a position for repetition detection
    key() { return this.fen().split(' ').slice(0, 4).join(' '); }

    // ---- Attacks ---------------------------------------------------------------------------

    kingSquare(color) {
        return this.board.findIndex(p => p && p.color === color && this.rules.pieces[p.type].royal);
    }

    // Is sq attacked by a piece of color `by`? Walks outwards from sq with every movement
    // pattern reversed.
    isAttacked(sq, by) {
        const has = (s, type) => {
            const p = this.board[s];
            return p && p.color === by && p.type === type;
        };
        for (const [type, kind] of Object.entries(this.rules.pieces)) {
            if (kind.pawn) {
                for (const df of [-1, 1]) {
                    const from = this.step(sq, -df, -this.forward(by));
                    if (from !== -1 && has(from, type)) return true;
                }
                continue;
            }
            for (const [df, dr] of kind.leaps ?? []) {
                const from = this.step(sq, -df, -dr);
                if (from !== -1 && has(from, type)) return true;
            }
            for (const [df, dr] of kind.slides ?? []) {
                for (const from of this.ray(sq, -df, -dr)) {
                    if (!this.board[from]) continue;
                    if (has(from, type)) return true;
                    break;
                }
            }
        }
        return false;
    }

    // Can a pawn of `color` capture onto sq (used for en passant)?
    pawnCanCapture(sq, color) {
        for (const df of [-1, 1]) {
            const from = this.step(sq, -df, -this.forward(color));
            const p = from === -1 ? null : this.board[from];
            if (p && p.color === color && this.rules.pieces[p.type].pawn) return true;
        }
        return false;
    }

    inCheck() { return this.isAttacked(this.kingSquare(this.side), other(this.side)); }

    // ---- Move generation -------------------------------------------------------------------

    pseudoLegalMoves() {
        const moves = [];
        const us = this.side;
        const add = (from, to, flags = {}) => {
            const target = this.board[to];
            const capture = !!target || !!flags.enPassant;
            if (this.rules.pieces[this.board[from].type].pawn && this.rankOf(to) === this.rules.promotionRank[us]) {
                for (const promotion of this.rules.promotions) moves.push({ from, to, capture, promotion, ...flags });
                return;
            }
            moves.push({ from, to, capture, ...flags });
        };

        this.board.forEach((piece, from) => {
            if (!piece || piece.color !== us) return;
            const kind = this.rules.pieces[piece.type];
            if (kind.pawn) {
                const one = this.step(from, 0, this.forward(us));
                if (one !== -1 && !this.board[one]) {
                    add(from, one);
                    const two = this.step(one, 0, this.forward(us));
                    if (this.rankOf(from) === this.rules.doubleStepRank[us] && two !== -1 && !this.board[two])
                        add(from, two, { doubleStep: true });
                }
                for (const df of [-1, 1]) {
                    const to = this.step(from, df, this.forward(us));
                    if (to === -1) continue;
                    if (this.board[to] && this.board[to].color !== us) add(from, to);
                    else if (to === this.ep) add(from, to, { enPassant: true });
                }
                return;
            }
            // On a wrapping board a slider can reach a square along two rays: report it once
            const seen = new Set();
            const tryTarget = to => {
                if (seen.has(to)) return;
                seen.add(to);
                if (!this.board[to] || this.board[to].color !== us) add(from, to);
            };
            for (const [df, dr] of kind.leaps ?? []) {
                const to = this.step(from, df, dr);
                if (to !== -1 && to !== from) tryTarget(to);
            }
            for (const [df, dr] of kind.slides ?? []) {
                for (const to of this.ray(from, df, dr)) {
                    tryTarget(to);
                    if (this.board[to]) break;
                }
            }
        });
        return moves;
    }

    makeMove(m) {
        const us = this.side;
        const undo = { m, side: us, ep: this.ep, halfmove: this.halfmove, fullmove: this.fullmove };
        undo.captured = m.enPassant ? this.board[this.step(m.to, 0, -this.forward(us))] : this.board[m.to];
        undo.capturedOn = m.enPassant ? this.step(m.to, 0, -this.forward(us)) : m.to;
        undo.moved = this.board[m.from];

        if (m.enPassant) this.board[undo.capturedOn] = null;
        this.board[m.to] = m.promotion ? { type: m.promotion, color: us } : this.board[m.from];
        this.board[m.from] = null;

        this.halfmove = m.capture || this.rules.pieces[undo.moved.type].pawn ? 0 : this.halfmove + 1;
        if (us === 'b') this.fullmove++;
        this.side = other(us);
        this.ep = -1;
        if (m.doubleStep) {
            const skipped = this.step(m.from, 0, this.forward(us));
            if (this.pawnCanCapture(skipped, this.side)) this.ep = skipped;
        }
        this.stack.push(undo);
    }

    unmakeMove() {
        const u = this.stack.pop();
        const m = u.m;
        this.board[m.from] = u.moved;
        this.board[m.to] = null;
        if (u.captured) this.board[u.capturedOn] = u.captured;
        this.side = u.side;
        this.ep = u.ep;
        this.halfmove = u.halfmove;
        this.fullmove = u.fullmove;
    }

    legalMoves() {
        return this.pseudoLegalMoves().filter(m => {
            this.makeMove(m);
            const ok = !this.isAttacked(this.kingSquare(other(this.side)), this.side);
            this.unmakeMove();
            return ok;
        });
    }

    // ---- chess.js-like interface -------------------------------------------------------------

    turn() { return this.side; }
    moveNumber() { return this.fullmove; }

    get(name) {
        const sq = this.parseSquare(name);
        return sq === -1 ? null : this.board[sq];
    }

    moves({ verbose = false } = {}) {
        const list = this.legalMoves().map(m => this.describe(m));
        return verbose ? list : list.map(m => m.san);
    }

    lan(m) { return this.name(m.from) + this.name(m.to) + (m.promotion ?? ''); }

    // Standard algebraic notation, e.g. "Nbd2", "exd5", "a8=Q+"
    san(m, legal) {
        const piece = this.board[m.from];
        let s = '';
        if (this.rules.pieces[piece.type].pawn) {
            if (m.capture) s += this.name(m.from)[0];
        } else {
            s += piece.type.toUpperCase();
            const rivals = legal.filter(o => o.to === m.to && o.from !== m.from && this.board[o.from].type === piece.type);
            if (rivals.length) {
                const sameFile = rivals.some(o => this.fileOf(o.from) === this.fileOf(m.from));
                const sameRank = rivals.some(o => this.rankOf(o.from) === this.rankOf(m.from));
                if (!sameFile) s += this.name(m.from)[0];
                else if (!sameRank) s += this.name(m.from).slice(1);
                else s += this.name(m.from);
            }
        }
        if (m.capture) s += 'x';
        s += this.name(m.to);
        if (m.promotion) s += '=' + m.promotion.toUpperCase();
        this.makeMove(m);
        if (this.inCheck()) s += this.legalMoves().length ? '+' : '#';
        this.unmakeMove();
        return s;
    }

    describe(m, legal = this.legalMoves()) {
        const piece = this.board[m.from];
        return {
            from: this.name(m.from),
            to: this.name(m.to),
            promotion: m.promotion,
            piece: piece.type,
            color: piece.color,
            captured: m.capture ? (m.enPassant ? 'p' : this.board[m.to].type) : undefined,
            san: this.san(m, legal),
            lan: this.lan(m),
            raw: m,
        };
    }

    // Accepts {from, to, promotion}, a UCI string ("e2e4", "a7a8q") or a SAN string
    move(input) {
        const legal = this.legalMoves();
        let m;
        if (typeof input === 'string') {
            m = legal.find(l => this.lan(l) === input) ?? legal.find(l => this.san(l, legal) === input);
        } else {
            const from = this.parseSquare(input.from), to = this.parseSquare(input.to);
            m = legal.find(l => l.from === from && l.to === to && (l.promotion ?? undefined) === (input.promotion || undefined));
        }
        if (!m) throw new Error('illegal move ' + JSON.stringify(input));
        const info = this.describe(m, legal);
        this.makeMove(m);
        this.keys.push(this.key());
        this.playedMoves.push(info);
        return info;
    }

    undo() {
        if (!this.stack.length) return null;
        this.unmakeMove();
        this.keys.pop();
        return this.playedMoves.pop();
    }

    history() { return this.playedMoves.map(m => m.san); }

    isCheckmate() { return this.inCheck() && this.legalMoves().length === 0; }
    isStalemate() { return !this.inCheck() && this.legalMoves().length === 0; }
    isThreefoldRepetition() {
        const k = this.keys[this.keys.length - 1];
        return this.keys.filter(x => x === k).length >= 3;
    }
    isInsufficientMaterial() {
        const rest = this.board.filter(p => p && !this.rules.pieces[p.type].royal);
        return rest.length === 0 || (rest.length === 1 && !!this.rules.pieces[rest[0].type].minor);
    }
    isDraw() {
        return this.halfmove >= 100 || this.isStalemate() || this.isThreefoldRepetition() || this.isInsufficientMaterial();
    }
    isGameOver() { return this.isCheckmate() || this.isDraw(); }

    // PGN of the game since `startFen`
    pgn(startFen) {
        const g = new VariantGame(startFen, this.rules);
        const tokens = [];
        for (const m of this.playedMoves) {
            if (g.turn() === 'w') tokens.push(g.moveNumber() + '.');
            else if (!tokens.length) tokens.push(g.moveNumber() + '...');
            tokens.push(m.san);
            g.move(m.lan);
        }
        const result = this.isCheckmate() ? (this.turn() === 'w' ? '0-1' : '1-0') : this.isDraw() ? '1/2-1/2' : '*';
        return `[Variant "${this.rules.title}"]\n[SetUp "1"]\n[FEN "${startFen}"]\n[Result "${result}"]\n\n${tokens.join(' ')} ${result}\n`;
    }
}

// Number of leaf nodes of the legal move tree (for testing against the engine)
export function perft(game, depth) {
    const moves = game.legalMoves();
    if (depth === 1) return moves.length;
    let n = 0;
    for (const m of moves) {
        game.makeMove(m);
        n += perft(game, depth - 1);
        game.unmakeMove();
    }
    return n;
}
