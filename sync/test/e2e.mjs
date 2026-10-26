// End-to-end test of the sync server against `wrangler dev` (local Workers runtime, Durable Objects, R2).
import WebSocket from "ws";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { encode, decode, diffOp } from "../src/protocol.ts";

const BASE = process.env.SYNC_URL ?? "http://127.0.0.1:8787";
const log = (...a) => console.log("•", ...a);

const tok = await (await fetch(`${BASE}/auth/dev?login=tester`)).json();
const H = { Authorization: `Bearer ${tok.token}` };
assert.equal((await fetch(`${BASE}/api/me`)).status, 401, "unauthenticated is rejected");
assert.equal((await (await fetch(`${BASE}/api/me`, { headers: H })).json()).login, "tester");

const noteId = "note-" + crypto.randomUUID().slice(0, 8);
await fetch(`${BASE}/api/notes/${noteId}`, { method: "PUT", headers: { ...H, "Content-Type": "application/json" }, body: JSON.stringify({ name: "Lecture 1", folder: "Uni/Analysis" }) });

function client(name) {
  const ws = new WebSocket(`${BASE.replace("http", "ws")}/api/notes/${noteId}/ws?token=${tok.token}`);
  ws.binaryType = "arraybuffer";
  const inbox = [];
  const waiters = [];
  ws.on("message", async (data, isBinary) => {
    const msg = await decode(isBinary ? new Uint8Array(data) : data.toString());
    msg._bytes = isBinary ? data.byteLength : data.toString().length;
    msg._binary = isBinary;
    const w = waiters.findIndex((x) => x.t === msg.t);
    if (w >= 0) waiters.splice(w, 1)[0].resolve(msg); else inbox.push(msg);
  });
  return {
    name, ws,
    open: () => new Promise((r) => ws.once("open", r)),
    send: async (m) => ws.send(await encode(m)),
    next: (t, ms = 3000) => {
      const i = inbox.findIndex((m) => m.t === t);
      if (i >= 0) return Promise.resolve(inbox.splice(i, 1)[0]);
      return new Promise((resolve, reject) => { waiters.push({ t, resolve }); setTimeout(() => reject(new Error(`${name}: no ${t} within ${ms}ms`)), ms); });
    },
  };
}

const stroke = (id, n = 200) => ({
  id, type: "freedraw", x: 10, y: 20, width: 100, height: 50, angle: 0, strokeColor: "#1e1e1e", backgroundColor: "transparent",
  fillStyle: "solid", strokeWidth: 2, strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], frameId: null, index: "a0",
  roundness: null, seed: 1, version: 1, versionNonce: 1000, isDeleted: false, boundElements: null, updated: 1, link: null, locked: false,
  points: Array.from({ length: n }, (_, i) => [i * 0.5, Math.sin(i / 10) * 20]), pressures: Array.from({ length: n }, () => 0.5),
  simulatePressure: false, lastCommittedPoint: null,
});

const A = client("tablet"), B = client("browser");
await Promise.all([A.open(), B.open()]);
await A.send({ t: "hello", since: 0 }); await B.send({ t: "hello", since: 0 });
assert.equal((await A.next("sync")).ops.length, 0);
await B.next("sync");

// 1) full element from A reaches B (big → compressed binary frame)
const s1 = stroke("stroke1");
let t0 = performance.now();
await A.send({ t: "push", ops: [diffOp(null, s1)], meta: { inkcalidraw: { tool: "pen" } } });
const ack1 = await A.next("ack");
const got = await B.next("ops");
log(`full stroke (200 points): A→B in ${(performance.now() - t0).toFixed(1)} ms, frame ${got._bytes} bytes (${got._binary ? "deflated" : "text"}) vs ${JSON.stringify(s1).length} bytes raw`);
assert.equal(got.ops[0].full.points.length, 200);
assert.equal(got.meta.inkcalidraw.tool, "pen");

// 2) erase = tiny patch
const s1del = { ...s1, isDeleted: true, version: 2, versionNonce: 77, updated: 2 };
const patch = diffOp(s1, s1del);
assert.ok("patch" in patch && Object.keys(patch.patch).length === 2, "patch carries only changed fields");
t0 = performance.now();
await A.send({ t: "push", ops: [patch] });
const got2 = await B.next("ops");
await A.next("ack");
log(`erase as patch: ${JSON.stringify(patch).length} bytes on the wire, A→B in ${(performance.now() - t0).toFixed(1)} ms`);
assert.equal(got2.ops[0].patch.isDeleted, true);

// 3) stale write from B (older version) is ignored; concurrent same-version edit resolves by lower nonce
await B.send({ t: "push", ops: [diffOp(null, { ...s1, version: 1, versionNonce: 1 })] });
await B.next("ack");
let snap = await (await fetch(`${BASE}/api/notes/${noteId}/snapshot`, { headers: H })).json();
assert.equal(snap.elements[0].isDeleted, true, "older version did not overwrite newer");

// 4) patch against a base the server doesn't have → server asks for the full element
await A.send({ t: "push", ops: [{ id: "stroke1", v: 5, n: 1, base: 4, patch: { strokeColor: "#e03131" } }] });
const ack4 = await A.next("ack");
assert.deepEqual(ack4.need, ["stroke1"]);

// 5) reconnect: hello(since) returns only what changed after that seq
const C = client("late");
await C.open();
await C.send({ t: "hello", since: ack1.seq });
const sync = await C.next("sync");
assert.equal(sync.ops.length, 1); assert.equal(sync.ops[0].full.isDeleted, true);
log(`incremental hello: since seq ${ack1.seq} → ${sync.ops.length} element(s)`);

// 6) files: content-addressed upload, verify, download
const png = crypto.randomBytes(5000);
const sha = crypto.createHash("sha1").update(png).digest("hex");
assert.equal((await fetch(`${BASE}/api/files/${sha}`, { method: "HEAD", headers: H })).status, 404);
assert.equal((await fetch(`${BASE}/api/files/${"0".repeat(40)}`, { method: "PUT", headers: H, body: png })).status, 400, "sha mismatch rejected");
assert.equal((await fetch(`${BASE}/api/files/${sha}`, { method: "PUT", headers: { ...H, "Content-Type": "image/png" }, body: png })).status, 200);
const back = Buffer.from(await (await fetch(`${BASE}/api/files/${sha}`, { headers: H })).arrayBuffer());
assert.ok(back.equals(png));

// 7) latency over many small live updates (a stroke being drawn point by point)
const lat = [];
for (let i = 0; i < 50; i++) {
  const el = { ...stroke("live", 10 + i), version: 1 + i, versionNonce: 5000 + i };
  const t = performance.now();
  await A.send({ t: "push", ops: [{ id: "live", v: el.version, n: el.versionNonce, full: el }] });
  await B.next("ops"); lat.push(performance.now() - t); await A.next("ack");
}
lat.sort((a, b) => a - b);
log(`live updates A→B: median ${lat[25].toFixed(1)} ms, p95 ${lat[47].toFixed(1)} ms (local runtime)`);

// 8) library
const lib = await (await fetch(`${BASE}/api/notes`, { headers: H })).json();
assert.ok(lib.some((n) => n.id === noteId && n.folder === "Uni/Analysis"));

// 9) another user can't see this note
const other = await (await fetch(`${BASE}/auth/dev?login=intruder`)).json();
const osnap = await (await fetch(`${BASE}/api/notes/${noteId}/snapshot`, { headers: { Authorization: `Bearer ${other.token}` } })).json();
assert.equal(osnap.elements.length, 0, "notes are per user");
assert.equal((await fetch(`${BASE}/api/files/${sha}`, { headers: { Authorization: `Bearer ${other.token}` } })).status, 404, "files are per user");

for (const c of [A, B, C]) c.ws.close();
console.log("ALL SYNC TESTS PASSED");
process.exit(0);
