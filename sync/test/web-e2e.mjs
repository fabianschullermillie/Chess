// Live sync between a simulated tablet (protocol client) and two browser tabs running the real web app
// with the real Excalidraw editor. Needs `wrangler dev` (8787) and `vite preview` (5173) running.
import { createRequire } from "node:module";
import WebSocket from "ws";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { encode, decode, diffOp } from "../src/protocol.ts";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "../../tools/compat/node_modules/playwright");
const SYNC = process.env.SYNC_URL ?? "http://127.0.0.1:8787";
const WEB = process.env.WEB_URL ?? "http://127.0.0.1:5173";
const SHOTS = process.env.SHOTS ?? ".";
const log = (...a) => console.log("•", ...a);

const { token } = await (await fetch(`${SYNC}/auth/dev?login=fabian`)).json();
const H = { Authorization: `Bearer ${token}` };
const noteId = "e2e" + crypto.randomUUID().replace(/-/g, "").slice(0, 12);
await fetch(`${SYNC}/api/notes/${noteId}`, { method: "PUT", headers: { ...H, "Content-Type": "application/json" }, body: JSON.stringify({ name: "Analysis – Lecture 3", folder: "Uni/Analysis" }) });

// --- a tiny real PDF (one page with a title and a box), uploaded like the tablet does
function makePdf() {
  const objs = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    null,
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
  ];
  const content = "BT /F1 32 Tf 60 760 Td (Lecture slide from a PDF) Tj ET 0.1 0.44 0.76 rg 60 450 475 250 re f";
  objs[3] = `<< /Length ${content.length} >>\nstream\n${content}\nendstream`;
  let out = "%PDF-1.4\n"; const offs = [];
  objs.forEach((o, i) => { offs.push(out.length); out += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = out.length;
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` + offs.map((o) => String(o).padStart(10, "0") + " 00000 n \n").join("");
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`;
  return Buffer.from(out, "latin1");
}
const pdf = makePdf();
const pdfSha = crypto.createHash("sha1").update(pdf).digest("hex");
await fetch(`${SYNC}/api/files/${pdfSha}`, { method: "PUT", headers: { ...H, "Content-Type": "application/pdf" }, body: pdf });

// --- simulated tablet
const tablet = new WebSocket(`${SYNC.replace("http", "ws")}/api/notes/${noteId}/ws?token=${token}`);
tablet.binaryType = "arraybuffer";
const fromServer = [];
tablet.on("message", async (d, bin) => fromServer.push({ at: performance.now(), msg: await decode(bin ? new Uint8Array(d) : d.toString()) }));
await new Promise((r) => tablet.once("open", r));
const send = async (m) => tablet.send(await encode(m));
await send({ t: "hello", since: 0 });

const base = { angle: 0, backgroundColor: "transparent", fillStyle: "solid", strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], roundness: null, seed: 7, isDeleted: false, boundElements: null, updated: 1, link: null, locked: false };
const frame = { ...base, id: "page1", type: "frame", x: 0, y: 0, width: 794, height: 1123, strokeColor: "#bbb", strokeWidth: 2, frameId: null, index: "a0", version: 1, versionNonce: 11, name: "Page 1", customData: { inkcalidrawPage: 1, background: { kind: "pdf", file: "lecture3.pdf", page: 0 } } };
const stroke = (id, y, n = 120) => ({ ...base, id, type: "freedraw", x: 120, y, width: 500, height: 60, strokeColor: "#e03131", strokeWidth: 2, frameId: "page1", index: "a" + (y % 9 + 1), version: 1, versionNonce: 100 + y,
  points: Array.from({ length: n }, (_, i) => [i * 4, Math.sin(i / 8) * 25]), pressures: Array.from({ length: n }, (_, i) => 0.3 + 0.5 * Math.sin((i / n) * Math.PI)), simulatePressure: false, lastCommittedPoint: null });

await send({ t: "push", ops: [diffOp(null, frame), diffOp(null, stroke("ink1", 560))], meta: { inkcalidraw: { pdfs: { "lecture3.pdf": pdfSha } } } });

// --- two browser tabs
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } });
const pageA = await ctx.newPage(); const pageB = await ctx.newPage();
for (const p of [pageA, pageB]) {
  p.on("pageerror", (e) => console.error("PAGEERROR", e.message));
  await p.goto(`${WEB}/#token=${token}`);
  await p.goto(`${WEB}/#/n/${noteId}`);
}
const ids = (p) => p.evaluate(() => window.__ink?.ex.getSceneElements().map((e) => e.id + ":" + e.type) ?? []);
async function waitFor(p, fn, what, ms = 8000) {
  const t0 = performance.now();
  while (performance.now() - t0 < ms) { if (await fn()) return performance.now() - t0; await p.waitForTimeout(25); }
  throw new Error(`timeout waiting for ${what}; scene=${JSON.stringify(await ids(p))}`);
}

await waitFor(pageA, async () => (await ids(pageA)).includes("ink1:freedraw"), "initial ink in tab A");
await waitFor(pageA, async () => (await ids(pageA)).includes("bg-page1:image"), "PDF page rendered under the frame");
log("tab A loaded note: frame, ink and the PDF page (rendered with pdf.js)");

// 1) tablet draws → both tabs
let t0 = performance.now();
await send({ t: "push", ops: [diffOp(null, stroke("ink2", 700))] });
await waitFor(pageA, async () => (await ids(pageA)).includes("ink2:freedraw"), "ink2 in A");
const tabletToWeb = performance.now() - t0;
await waitFor(pageB, async () => (await ids(pageB)).includes("ink2:freedraw"), "ink2 in B");
log(`tablet → browser: new stroke visible after ${tabletToWeb.toFixed(0)} ms`);
await pageA.waitForTimeout(400);
await pageA.screenshot({ path: `${SHOTS}/web-1-tablet-ink.png` });

// 2) tablet erases → tiny patch
const del = { ...stroke("ink1", 560), isDeleted: true, version: 2, versionNonce: 999 };
const patch = diffOp(stroke("ink1", 560), del);
t0 = performance.now();
await send({ t: "push", ops: [patch] });
await waitFor(pageA, async () => !(await ids(pageA)).includes("ink1:freedraw"), "erase in A");
log(`tablet erase (${JSON.stringify(patch).length}-byte patch) → gone in browser after ${(performance.now() - t0).toFixed(0)} ms`);

// 3) browser draws with the mouse in tab A → tablet and tab B
await pageA.evaluate(() => window.__ink.ex.setActiveTool({ type: "freedraw" }));
const box = await pageA.locator("canvas.interactive").boundingBox();
const sx = box.x + box.width * 0.35, sy = box.y + box.height * 0.6;
fromServer.length = 0;
await pageA.mouse.move(sx, sy); await pageA.mouse.down();
const drawStart = performance.now();
let firstLiveAt = null;
for (let i = 1; i <= 40; i++) {
  await pageA.mouse.move(sx + i * 8, sy + Math.sin(i / 4) * 30, { steps: 1 });
  await pageA.waitForTimeout(16);
  if (firstLiveAt === null && fromServer.some((f) => f.msg.t === "ops" && f.msg.ops.some((o) => (o.full ?? o.patch)?.type === "freedraw" || "patch" in o))) firstLiveAt = performance.now() - drawStart;
}
await pageA.mouse.up();
const upAt = performance.now();
await waitFor(pageA, async () => fromServer.some((f) => f.at > upAt - 5 && f.msg.t === "ops"), "tablet receives browser stroke");
await pageA.waitForTimeout(300);
const webOps = fromServer.filter((f) => f.msg.t === "ops").flatMap((f) => f.msg.ops);
const strokeId = webOps.find((o) => o.full?.type === "freedraw")?.id;
assert.ok(strokeId, "tablet got the browser's freedraw element");
const bytes = fromServer.filter((f) => f.msg.t === "ops").reduce((s, f) => s + JSON.stringify(f.msg).length, 0);
log(`browser → tablet: stroke streamed live while drawing (first update after ${firstLiveAt?.toFixed(0)} ms), ${webOps.length} ops, ${webOps.filter((o) => "patch" in o).length} as patches, ${(bytes / 1024).toFixed(1)} KB total`);
await waitFor(pageB, async () => (await ids(pageB)).includes(`${strokeId}:freedraw`), "browser stroke in tab B");
await pageB.waitForTimeout(400);
await pageB.screenshot({ path: `${SHOTS}/web-2-tab-b-sees-browser-and-tablet-ink.png` });

// 4) reload B: state comes back from the server (persisted in the Durable Object)
await pageB.reload();
await waitFor(pageB, async () => { const s = await ids(pageB); return s.includes(`${strokeId}:freedraw`) && s.includes("ink2:freedraw") && !s.includes("ink1:freedraw"); }, "state after reload");
log("reload: note state restored from the server");

// 5) library
await pageB.goto(`${WEB}/#/`);
await pageB.getByText("Analysis – Lecture 3").first().waitFor();
await pageB.screenshot({ path: `${SHOTS}/web-3-library.png` });
log("library lists the note under Uni/Analysis");

await browser.close(); tablet.close();
console.log("ALL WEB SYNC TESTS PASSED");
process.exit(0);
