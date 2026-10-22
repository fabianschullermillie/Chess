// 3D view of the toroidal board with Three.js.
//
// The board is a finely subdivided plane whose vertices are moved every frame by `morph`:
//   m = 0       the flat 8x8 board, seen from straight above (pixel-aligned with the 2D board)
//   m = 0..0.5  the ranks roll up into a tube (the 1st and 8th rank meet)
//   m = 0.5..1  the tube bends into a ring (the a- and h-files meet)
// The squares and highlights are drawn into a canvas that serves as texture. Pieces start as
// pictures in that texture and grow into 3D pieces standing on the surface as the board rolls up.
// Clicking on a square or a piece selects it.

import * as THREE from './vendor/three.module.min.js';
import { OrbitControls } from './vendor/OrbitControls.js';
import { PieceFactory } from './pieces3d.js';

const SIZE = 8;               // board width and height in world units (1 unit per square)
const SEGMENTS = 192;         // plane subdivisions per side
const TEXTURE = 1024;         // texture resolution in pixels
// Final major radius / minor radius. A fat ring: the closer to 1, the closer the squares along the
// ring are to being square (they get wider the further out they are).
const RING_STRETCH = 2.0;
// How much of the difference in square size between the inside and the outside of the ring is
// evened out by giving the inner ranks a larger share of the tube's circumference (0 = none,
// 1 = all squares of a file have equal area, but the inner ones are then narrow and tall).
const EVEN_OUT = 0.7;
const FOV = 30;
const FLAT_DISTANCE = SIZE / 2 / Math.tan(THREE.MathUtils.degToRad(FOV / 2));
const TORUS_DISTANCE = 20;
const TORUS_TILT = THREE.MathUtils.degToRad(50); // camera elevation above the ring plane's normal
const DURATION = 2600;        // ms for a full transition
const PIECE_GROWTH = 0.3;     // fraction of the transition over which 3D pieces replace the 2D ones

const TAU = Math.PI * 2;
const smooth = x => x * x * (3 - 2 * x);
const clamp01 = x => Math.min(1, Math.max(0, x));

// Piece images: reuse the SVGs embedded in chessground's cburnett theme
async function loadPieceImages() {
    const css = await (await fetch('vendor/chessground.cburnett.css')).text();
    const images = {};
    const re = /piece\.(\w+)\.(white|black)\s*\{\s*background-image:\s*url\('([^']+)'\)/g;
    const loads = [];
    for (const [, role, color, url] of css.matchAll(re)) {
        const img = new Image();
        img.src = url;
        images[color[0] + role] = img;
        loads.push(img.decode().catch(() => {}));
    }
    await Promise.all(loads);
    return images;
}

const ROLE = { p: 'pawn', n: 'knight', b: 'bishop', r: 'rook', q: 'queen', k: 'king' };

// ---- The surface ------------------------------------------------------------------------------

// Shape parameters for morph value m
function surfaceParams(m) {
    const roll = smooth(clamp01(m * 2));      // ranks -> tube
    const bend = smooth(clamp01(m * 2 - 1));  // tube -> ring
    const r0 = SIZE / TAU;                    // radius of the finished tube
    return {
        roll, bend, r0,
        // Tube radius (infinite while flat) and ring radius (infinite while straight). The ring
        // is stretched towards RING_STRETCH * r0 so the finished torus has a hole.
        r: roll > 1e-4 ? SIZE / (TAU * roll) : Infinity,
        R: bend > 1e-4 ? (SIZE * (1 + (RING_STRETCH - 1) * bend)) / (TAU * bend) : Infinity,
        // Strength of the evening-out, grows with the bend
        even: EVEN_OUT * bend / RING_STRETCH,
    };
}

// Angle around the tube for the evenly spaced angle phi0. Solves
//   phi + k cos(phi) = phi0 - k        (k = 0: phi = phi0)
// which stretches the inner side of the ring (phi near +pi/2, where the distance to the ring's
// centre R - r sin(phi) is smallest) and squeezes the outer side; phi = pi stays fixed.
function evenAngle(phi0, k) {
    if (k === 0) return phi0;
    let phi = phi0;
    for (let i = 0; i < 6; i++) phi -= (phi + k * Math.cos(phi) - phi0 + k) / (1 - k * Math.sin(phi));
    return phi;
}

// Position of the flat-board point (x, y) on the surface described by p, written into out
function mapPoint(x, y, p, out) {
    // Stage 1: roll. Offset of the point from the tube axis: (dy, dz)
    let dy = y, dz = 0;
    if (p.r !== Infinity) {
        // While bending, the tube also turns a quarter so the lower ranks (the side at the bottom
        // of the screen) end up on top of the ring and the middle ranks on its inner side
        const phi = evenAngle(y / p.r + p.bend * Math.PI / 2, p.even);
        dy = p.r * Math.sin(phi);
        dz = p.r * (Math.cos(phi) - 1) + p.roll * p.r0; // keep the visible side near z = 0
    }
    // Stage 2: bend the tube axis (the x direction) around a centre at Y = +R, so the middle of
    // the board stays in front, facing the camera. The angle covers a full turn at bend = 1; the
    // shift moves the ring's centre to the origin.
    let X = x, Y = dy;
    if (p.R !== Infinity) {
        const theta = TAU * p.bend * x / SIZE;
        X = (p.R - dy) * Math.sin(theta);
        Y = p.R - (p.R - dy) * Math.cos(theta) - p.bend * p.bend * p.R;
    }
    out[0] = X;
    out[1] = Y;
    out[2] = dz;
    return out;
}

export class TorusView {
    constructor(container, { onSquareClick, colors }) {
        this.container = container;
        this.onSquareClick = onSquareClick;
        this.colors = colors;
        this.m = 0;
        this.state = null;

        this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
        this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
        this.canvasEl = this.renderer.domElement;
        this.canvasEl.className = 'torus-canvas';
        this.canvasEl.style.display = 'none'; // three.js sets an inline display: block
        container.append(this.canvasEl);

        this.scene = new THREE.Scene();
        this.camera = new THREE.PerspectiveCamera(FOV, 1, 0.1, 200);
        this.camera.position.set(0, 0, FLAT_DISTANCE);

        this.canvas = document.createElement('canvas');
        this.canvas.width = this.canvas.height = TEXTURE;
        this.ctx = this.canvas.getContext('2d');
        this.texture = new THREE.CanvasTexture(this.canvas);
        this.texture.colorSpace = THREE.SRGBColorSpace;
        this.texture.anisotropy = this.renderer.capabilities.getMaxAnisotropy();

        // At m = 0 the board is lit only by its emissive map (exact 2D colors); the lights fade in
        this.material = new THREE.MeshStandardMaterial({
            map: this.texture, emissiveMap: this.texture, emissive: 0xffffff,
            roughness: 0.6, metalness: 0.05, side: THREE.DoubleSide,
        });
        this.geometry = new THREE.PlaneGeometry(SIZE, SIZE, SEGMENTS, SEGMENTS);
        this.flat = Float32Array.from(this.geometry.attributes.position.array);
        this.mesh = new THREE.Mesh(this.geometry, this.material);
        this.scene.add(this.mesh);

        this.pieceFactory = new PieceFactory();
        this.pieces = new THREE.Group();
        this.scene.add(this.pieces);

        this.ambient = new THREE.HemisphereLight(0xffffff, 0x404040, 0);
        this.sun = new THREE.DirectionalLight(0xffffff, 0);
        this.sun.position.set(-6, -8, 14);
        this.scene.add(this.ambient, this.sun);

        // In the 3D view the camera orbits around the ring's axis (z). OrbitControls takes its
        // up axis from the camera when it is created; the flat top view itself uses up = y.
        this.camera.up.set(0, 0, 1);
        this.controls = new OrbitControls(this.camera, this.canvasEl);
        this.camera.up.set(0, 1, 0);
        this.controls.enableDamping = true;
        this.controls.enablePan = false;
        this.controls.minDistance = 8;
        this.controls.maxDistance = 45;
        this.controls.enabled = false;

        this.raycaster = new THREE.Raycaster();
        this.setupPointer();

        this.images = {};
        this.ready = loadPieceImages().then(images => {
            this.images = images;
            this.redraw();
        });

        new ResizeObserver(() => this.resize()).observe(container);
        this.resize();
        this.morph(0);
        this.loop();
    }

    resize() {
        const w = this.container.clientWidth, h = this.container.clientHeight;
        if (!w || !h) return;
        this.renderer.setSize(w, h, false);
        this.camera.aspect = w / h;
        this.camera.updateProjectionMatrix();
    }

    loop() {
        requestAnimationFrame(() => this.loop());
        if (!this.visible) return;
        if (this.controls.enabled) this.controls.update();
        this.renderer.render(this.scene, this.camera);
    }

    // ---- Geometry ----------------------------------------------------------------------------

    // Moves every vertex and every piece for morph parameter m in [0, 1]
    morph(m) {
        const before = this.pieceGrowth;
        this.m = m;
        this.params = surfaceParams(m);
        const pos = this.geometry.attributes.position.array;
        const out = [0, 0, 0];
        for (let i = 0; i < pos.length; i += 3) {
            mapPoint(this.flat[i], this.flat[i + 1], this.params, out);
            pos[i] = out[0];
            pos[i + 1] = out[1];
            pos[i + 2] = out[2];
        }
        this.geometry.attributes.position.needsUpdate = true;
        this.geometry.computeVertexNormals();
        this.geometry.computeBoundingSphere();

        this.material.emissiveIntensity = 1 - 0.7 * smooth(m);
        this.ambient.intensity = 0.9 * smooth(m);
        this.sun.intensity = 2.4 * smooth(m);

        this.placePieces();
        // The 2D piece pictures fade out while the 3D pieces grow
        if (this.pieceGrowth !== before) this.redraw();
    }

    get pieceGrowth() { return smooth(clamp01(this.m / PIECE_GROWTH)); }

    // Flat-board coordinates of the centre of a square
    squareCenter(name) {
        const f = name.charCodeAt(0) - 97, r = +name.slice(1) - 1;
        const white = this.state.orientation === 'white';
        return [white ? f - 3.5 : 3.5 - f, white ? r - 3.5 : 3.5 - r];
    }

    // Stands each 3D piece on its square: up = surface normal, front = towards the opponent,
    // scaled with the (varying) size of the square
    placePieces() {
        const growth = this.pieceGrowth;
        this.pieces.visible = growth > 0.01;
        if (!this.pieces.visible || !this.state) return;
        const p = this.params, e = 0.05;
        const a = [0, 0, 0], b = [0, 0, 0], c = [0, 0, 0];
        const P = new THREE.Vector3(), dx = new THREE.Vector3(), dy = new THREE.Vector3();
        const up = new THREE.Vector3(), front = new THREE.Vector3(), side = new THREE.Vector3();
        const basis = new THREE.Matrix4();
        for (const piece of this.pieces.children) {
            const [x, y] = this.squareCenter(piece.userData.square);
            P.fromArray(mapPoint(x, y, p, a));
            dx.fromArray(mapPoint(x + e, y, p, b)).sub(P).divideScalar(e);
            dy.fromArray(mapPoint(x, y + e, p, c)).sub(P).divideScalar(e);
            const size = Math.sqrt(dx.length() * dy.length());
            up.crossVectors(dx, dy).normalize();
            // White pieces face up the board (towards rank 8), black ones down
            const forward = (piece.userData.color === 'w') === (this.state.orientation === 'white') ? 1 : -1;
            front.copy(dy).multiplyScalar(forward).normalize();
            side.crossVectors(up, front).normalize();
            front.crossVectors(side, up);
            basis.makeBasis(side, up, front);
            piece.position.copy(P);
            piece.quaternion.setFromRotationMatrix(basis);
            const s = Math.min(1.15, size) * 0.92;
            piece.scale.set(s * (0.5 + 0.5 * growth), s * growth, s * (0.5 + 0.5 * growth));
        }
    }

    rebuildPieces() {
        this.pieces.clear();
        if (!this.state) return;
        for (const [square, piece] of this.state.pieces) {
            const g = this.pieceFactory.create(piece.type, piece.color, square === this.state.selected);
            g.userData = { square, color: piece.color };
            g.traverse(o => { o.userData.square = square; });
            this.pieces.add(g);
        }
        this.placePieces();
    }

    // Camera position for morph parameter m (top view -> tilted view of the ring)
    cameraFor(m) {
        const t = smooth(m);
        const dist = FLAT_DISTANCE + (TORUS_DISTANCE - FLAT_DISTANCE) * t;
        const tilt = TORUS_TILT * t;
        return new THREE.Vector3(0, -dist * Math.sin(tilt), dist * Math.cos(tilt));
    }

    // ---- Transitions ---------------------------------------------------------------------------

    get visible() { return this.container.classList.contains('show-3d'); }

    setVisible(on) {
        this.container.classList.toggle('show-3d', on);
        this.canvasEl.style.display = on ? 'block' : 'none';
    }

    // Flat -> torus. Resolves when the animation is done.
    async show() {
        await this.ready;
        this.camera.position.set(0, 0, FLAT_DISTANCE);
        this.camera.up.set(0, 1, 0);
        this.camera.lookAt(0, 0, 0);
        this.controls.target.set(0, 0, 0);
        this.morph(0);
        this.redraw();
        this.setVisible(true);
        await this.animate(0, 1, this.camera.position.clone(), this.cameraFor(1));
        // Same screen orientation as up = y at this camera position, so this causes no jump
        this.camera.up.set(0, 0, 1);
        this.camera.lookAt(0, 0, 0);
        this.controls.enabled = true;
    }

    // Torus -> flat, then hands back to the 2D board
    async hide() {
        this.controls.enabled = false;
        await this.orbitTo(this.cameraFor(1), 700);
        this.camera.up.set(0, 1, 0);
        await this.animate(1, 0, this.camera.position.clone(), this.cameraFor(0));
        this.setVisible(false);
    }

    // Swings the camera (up = z) back to `target` along a sphere around the ring's axis
    orbitTo(target, duration) {
        const toSpherical = v => {
            const radius = v.length();
            return { radius, polar: Math.acos(v.z / radius), azimuth: Math.atan2(v.y, v.x) };
        };
        const a = toSpherical(this.camera.position.clone()), b = toSpherical(target);
        let dAz = b.azimuth - a.azimuth;
        dAz = Math.atan2(Math.sin(dAz), Math.cos(dAz)); // shortest way round
        return new Promise(resolve => {
            const start = performance.now();
            const tick = now => {
                const k = smooth(clamp01((now - start) / duration));
                const radius = a.radius + (b.radius - a.radius) * k;
                const polar = a.polar + (b.polar - a.polar) * k;
                const azimuth = a.azimuth + dAz * k;
                this.camera.position.set(radius * Math.sin(polar) * Math.cos(azimuth),
                    radius * Math.sin(polar) * Math.sin(azimuth), radius * Math.cos(polar));
                this.camera.lookAt(0, 0, 0);
                if (k < 1) requestAnimationFrame(tick);
                else resolve();
            };
            requestAnimationFrame(tick);
        });
    }

    animate(from, to, camFrom, camTo) {
        return new Promise(resolve => {
            const start = performance.now();
            const tick = now => {
                const k = clamp01((now - start) / DURATION);
                this.morph(from + (to - from) * k);
                this.camera.position.lerpVectors(camFrom, camTo, smooth(k));
                this.camera.lookAt(0, 0, 0);
                if (k < 1) requestAnimationFrame(tick);
                else resolve();
            };
            requestAnimationFrame(tick);
        });
    }

    // ---- Board picture ---------------------------------------------------------------------------

    // state: { pieces: Map(square -> {type, color}), orientation, lastMove: [from, to], check, selected, dests }
    update(state) {
        this.state = state;
        this.rebuildPieces();
        this.redraw();
    }

    squareRect(name) {
        const s = TEXTURE / 8;
        const f = name.charCodeAt(0) - 97, r = +name.slice(1) - 1;
        const white = this.state.orientation === 'white';
        const col = white ? f : 7 - f, row = white ? 7 - r : r;
        return [col * s, row * s, s];
    }

    redraw() {
        if (!this.state) return;
        const ctx = this.ctx, st = this.state, s = TEXTURE / 8;
        const files = 'abcdefgh';
        for (let f = 0; f < 8; f++) {
            for (let r = 0; r < 8; r++) {
                const name = files[f] + (r + 1);
                const [x, y] = this.squareRect(name);
                ctx.fillStyle = (f + r) % 2 === 0 ? this.colors.dark : this.colors.light;
                ctx.fillRect(x, y, s, s);
                if (st.lastMove && st.lastMove.includes(name)) {
                    ctx.fillStyle = 'rgba(0,0,0,0.22)';
                    ctx.fillRect(x, y, s, s);
                }
                if (st.selected === name) {
                    ctx.fillStyle = this.colors.neonFaint;
                    ctx.fillRect(x, y, s, s);
                    ctx.strokeStyle = this.colors.neon;
                    ctx.lineWidth = 6;
                    ctx.strokeRect(x + 3, y + 3, s - 6, s - 6);
                }
                // Square names help to follow the wrap-around on the torus
                ctx.fillStyle = (f + r) % 2 === 0 ? 'rgba(255,255,255,0.55)' : 'rgba(0,0,0,0.45)';
                ctx.font = `600 ${s * 0.15}px ui-monospace, Menlo, Consolas, monospace`;
                ctx.textAlign = 'left';
                ctx.textBaseline = 'top';
                ctx.fillText(name, x + s * 0.06, y + s * 0.05);
            }
        }
        const pictureAlpha = 1 - this.pieceGrowth;
        for (const [name, piece] of st.pieces) {
            const [x, y] = this.squareRect(name);
            if (st.check && piece.type === 'k' && piece.color === st.check) {
                const g = ctx.createRadialGradient(x + s / 2, y + s / 2, 0, x + s / 2, y + s / 2, s * 0.6);
                g.addColorStop(0, 'rgba(255,0,0,0.9)');
                g.addColorStop(1, 'rgba(255,0,0,0)');
                ctx.fillStyle = g;
                ctx.fillRect(x, y, s, s);
            }
            const img = this.images[piece.color + ROLE[piece.type]];
            if (img && img.complete && pictureAlpha > 0.01) {
                ctx.globalAlpha = pictureAlpha;
                ctx.drawImage(img, x, y, s, s);
                ctx.globalAlpha = 1;
            }
        }
        for (const name of st.dests ?? []) {
            const [x, y] = this.squareRect(name);
            if (st.pieces.has(name)) {
                ctx.lineWidth = 8;
                ctx.strokeStyle = this.colors.neon;
                ctx.strokeRect(x + 4, y + 4, s - 8, s - 8);
            } else {
                ctx.fillStyle = this.colors.neon;
                ctx.strokeStyle = 'rgba(0,0,0,0.55)';
                ctx.lineWidth = 3;
                ctx.beginPath();
                ctx.arc(x + s / 2, y + s / 2, s * 0.14, 0, Math.PI * 2);
                ctx.fill();
                ctx.stroke();
            }
        }
        this.texture.needsUpdate = true;
    }

    // ---- Picking -----------------------------------------------------------------------------------

    setupPointer() {
        const el = this.canvasEl;
        let down = null;
        el.addEventListener('pointerdown', e => { down = [e.clientX, e.clientY]; });
        el.addEventListener('pointerup', e => {
            if (!down || Math.hypot(e.clientX - down[0], e.clientY - down[1]) > 6) return;
            down = null;
            if (this.m < 0.999 || !this.state) return;
            const rect = el.getBoundingClientRect();
            const ndc = new THREE.Vector2(((e.clientX - rect.left) / rect.width) * 2 - 1,
                -((e.clientY - rect.top) / rect.height) * 2 + 1);
            this.raycaster.setFromCamera(ndc, this.camera);
            const hit = this.raycaster.intersectObjects([this.mesh, this.pieces], true)[0];
            if (!hit) return;
            if (hit.object.userData.square) return this.onSquareClick(hit.object.userData.square);
            const col = Math.min(7, Math.floor(hit.uv.x * 8));
            const row = Math.min(7, Math.floor((1 - hit.uv.y) * 8));
            const white = this.state.orientation === 'white';
            const f = white ? col : 7 - col, r = white ? 7 - row : row;
            this.onSquareClick('abcdefgh'[f] + (r + 1));
        });
    }
}
