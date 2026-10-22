// Simple 3D chess pieces built from lathe profiles and a few primitives (no model files).
// Units: one board square = 1. Each piece stands on the local x-z plane with +y up and faces +z
// (only the knight has a front).

import * as THREE from './vendor/three.module.min.js';

// Turns a profile [[radius, height], ...] from bottom to top into a solid of revolution
function lathe(profile, segments = 40) {
    return new THREE.LatheGeometry(profile.map(([r, h]) => new THREE.Vector2(r, h)), segments);
}

// The common foot of all pieces: a flat disc with a rounded rim
const BASE = [[0, 0], [0.34, 0], [0.35, 0.03], [0.34, 0.07], [0.29, 0.09], [0.27, 0.12]];

const sphere = (radius, y, sx = 1, sy = 1, sz = 1) => {
    const g = new THREE.SphereGeometry(radius, 28, 18);
    g.scale(sx, sy, sz);
    g.translate(0, y, 0);
    return g;
};

const box = (w, h, d, x, y, z) => {
    const g = new THREE.BoxGeometry(w, h, d);
    g.translate(x, y, z);
    return g;
};

function pawn() {
    return [
        lathe([...BASE, [0.2, 0.16], [0.13, 0.3], [0.1, 0.4], [0.17, 0.43], [0.17, 0.46], [0.08, 0.49], [0, 0.49]]),
        sphere(0.13, 0.58),
    ];
}

function rook() {
    const parts = [lathe([...BASE, [0.24, 0.18], [0.2, 0.26], [0.19, 0.56], [0.25, 0.6], [0.25, 0.7], [0, 0.7]])];
    // Battlements: four blocks around the top
    for (let i = 0; i < 4; i++) {
        const g = box(0.13, 0.1, 0.09, 0, 0.75, 0.19);
        g.rotateY((i * Math.PI) / 2 + Math.PI / 4);
        parts.push(g);
    }
    return parts;
}

function bishop() {
    return [
        lathe([...BASE, [0.21, 0.17], [0.13, 0.32], [0.1, 0.5], [0.17, 0.53], [0.17, 0.56], [0.08, 0.58], [0, 0.58]]),
        sphere(0.14, 0.7, 1, 1.35, 1),
        sphere(0.045, 0.92),
    ];
}

function queen() {
    const parts = [
        lathe([...BASE, [0.23, 0.17], [0.14, 0.34], [0.11, 0.58], [0.2, 0.62], [0.2, 0.66], [0.15, 0.7],
            [0.22, 0.84], [0.1, 0.8], [0, 0.82]]),
        sphere(0.06, 0.9),
    ];
    for (let i = 0; i < 8; i++) {
        const a = (i / 8) * Math.PI * 2;
        const g = sphere(0.035, 0.85);
        g.translate(Math.cos(a) * 0.2, 0, Math.sin(a) * 0.2);
        parts.push(g);
    }
    return parts;
}

function king() {
    return [
        lathe([...BASE, [0.24, 0.17], [0.15, 0.34], [0.12, 0.62], [0.21, 0.66], [0.21, 0.7], [0.15, 0.73],
            [0.2, 0.86], [0, 0.88]]),
        box(0.06, 0.22, 0.06, 0, 0.98, 0),
        box(0.18, 0.06, 0.06, 0, 1.0, 0),
    ];
}

function knight() {
    // Head and neck: a horse silhouette in the y-z plane (z = front), extruded sideways
    const s = new THREE.Shape();
    s.moveTo(-0.2, 0.1);
    s.lineTo(0.2, 0.1);
    s.quadraticCurveTo(0.1, 0.3, 0.14, 0.4);
    s.quadraticCurveTo(0.3, 0.44, 0.34, 0.54);
    s.lineTo(0.31, 0.62);
    s.quadraticCurveTo(0.16, 0.76, 0.06, 0.8);
    s.lineTo(0.04, 0.9);
    s.lineTo(-0.04, 0.8);
    s.quadraticCurveTo(-0.22, 0.66, -0.2, 0.1);
    const head = new THREE.ExtrudeGeometry(s, {
        depth: 0.16, bevelEnabled: true, bevelThickness: 0.04, bevelSize: 0.03, bevelSegments: 3, curveSegments: 16,
    });
    head.translate(0, 0, -0.08);
    head.rotateY(-Math.PI / 2); // silhouette x -> world z (front)
    return [lathe([...BASE, [0.24, 0.16], [0, 0.16]]), head];
}

const BUILDERS = { p: pawn, n: knight, b: bishop, r: rook, q: queen, k: king };

export class PieceFactory {
    constructor() {
        this.geometries = {};
        for (const [type, build] of Object.entries(BUILDERS)) this.geometries[type] = build();
        this.materials = {
            w: new THREE.MeshStandardMaterial({ color: 0xf1ede2, roughness: 0.45, metalness: 0.05 }),
            b: new THREE.MeshStandardMaterial({ color: 0x2a2a2a, roughness: 0.4, metalness: 0.15 }),
        };
        this.selected = {
            w: this.materials.w.clone(),
            b: this.materials.b.clone(),
        };
        for (const m of Object.values(this.selected)) {
            m.emissive = new THREE.Color(0x39ff14);
            m.emissiveIntensity = 0.35;
        }
    }

    // A group with the meshes of one piece
    create(type, color, selected = false) {
        const group = new THREE.Group();
        const material = (selected ? this.selected : this.materials)[color];
        for (const g of this.geometries[type]) group.add(new THREE.Mesh(g, material));
        return group;
    }
}
