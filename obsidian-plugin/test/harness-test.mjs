// Runs the plugin's editor in headless Chromium with an Inkcalidraw PDF-annotation note.
import esbuild from "esbuild";
import fs from "node:fs";
import { createRequire } from "node:module";
import assert from "node:assert/strict";
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_PATH ?? "../../tools/compat/node_modules/playwright");
const OUT = new URL("./out/", import.meta.url).pathname;
fs.mkdirSync(OUT, { recursive: true });

await esbuild.build({
  entryPoints: [new URL("./harness.tsx", import.meta.url).pathname], bundle: true, format: "iife", outfile: OUT + "harness.js",
  conditions: ["production"], loader: { ".woff2": "dataurl", ".ttf": "dataurl" }, logLevel: "error",
  define: { "process.env.NODE_ENV": '"production"', "process.env.IS_PREACT": '"false"', "import.meta.env": '{"DEV":false,"PROD":true,"MODE":"production"}' },
});
fs.copyFileSync(new URL("../node_modules/pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url).pathname, OUT + "pdf.worker.min.mjs");

// A small real PDF (one page: title + blue box)
function makePdf() {
  const content = "BT /F1 32 Tf 60 760 Td (Lecture 3 - page from the PDF) Tj ET 0.1 0.44 0.76 rg 60 450 475 250 re f";
  const objs = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"];
  let out = "%PDF-1.4\n"; const offs = [];
  objs.forEach((o, i) => { offs.push(out.length); out += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const x = out.length;
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n` + offs.map((o) => String(o).padStart(10, "0") + " 00000 n \n").join("") + `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${x}\n%%EOF`;
  return Buffer.from(out, "latin1");
}
// An Inkcalidraw note exactly as the tablet app writes it: PDF-backed page frame + a stroke on it + app block.
const base = { angle: 0, backgroundColor: "transparent", fillStyle: "solid", strokeStyle: "solid", roughness: 1, opacity: 100, groupIds: [], roundness: null, seed: 3, isDeleted: false, boundElements: null, updated: 1, link: null, locked: false };
const note = {
  type: "excalidraw", version: 2, source: "inkcalidraw",
  elements: [
    { ...base, id: "page1", type: "frame", x: 0, y: 0, width: 793.33, height: 1122.67, strokeColor: "#bbb", strokeWidth: 2, frameId: null, index: "a0", version: 1, versionNonce: 5, name: "Page 1",
      customData: { inkcalidrawPage: 1, background: { kind: "pdf", file: "Lecture 3.pdf", page: 0 } } },
    { ...base, id: "ink1", type: "freedraw", x: 120, y: 620, width: 520, height: 60, strokeColor: "#e03131", strokeWidth: 2, frameId: "page1", index: "a1", version: 1, versionNonce: 9,
      points: Array.from({ length: 140 }, (_, i) => [i * 3.7, Math.sin(i / 9) * 28]), pressures: Array.from({ length: 140 }, (_, i) => 0.3 + 0.5 * Math.sin((i / 140) * Math.PI)), simulatePressure: false, lastCommittedPoint: null },
  ],
  appState: { gridSize: null, viewBackgroundColor: "#ffffff", currentItemStrokeColor: "#e03131" },
  files: {},
  inkcalidraw: { version: 2, tool: "pen", pages: { enabled: true, direction: "vertical" } },
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });
page.on("pageerror", (e) => console.error("PAGEERROR", e.message));
page.on("console", (m) => m.type() === "warning" || m.type() === "error" ? console.log("CONSOLE", m.text().slice(0, 300)) : 0);
fs.writeFileSync(OUT + "index.html", `<!doctype html><html><head><meta charset="utf-8"><style>html,body,#root{height:100%;margin:0}</style></head><body><div id="root"></div>
<script>window.__note=${JSON.stringify(JSON.stringify(note))};window.__pdf="${makePdf().toString("base64")}";window.__pdfWorker="./pdf.worker.min.mjs";</script>
<script src="harness.js"></script></body></html>`.replace("</head>", '<link rel="stylesheet" href="harness.css"></head>'));
// serve over http (pdf.js uses a module worker, which file:// pages can't load)
const http = await import("node:http");
const types = { ".html": "text/html", ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css" };
const server = http.createServer((req, res) => {
  const f = OUT + decodeURIComponent(req.url.split("?")[0]).replace(/^\//, "") ;
  if (!fs.existsSync(f)) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { "Content-Type": types[f.slice(f.lastIndexOf("."))] ?? "application/octet-stream" }); fs.createReadStream(f).pipe(res);
}).listen(0);
await new Promise((r) => server.once("listening", r));
await page.goto(`http://127.0.0.1:${server.address().port}/index.html`);
const ids = () => page.evaluate(() => (window.__inkScene?.getSceneElements() ?? []).map((e) => e.id));
for (let i = 0; i < 100 && !(await ids()).includes("bg-page1"); i++) await page.waitForTimeout(100);
assert.ok((await ids()).includes("bg-page1"), "PDF page rendered under the page frame");
await page.waitForTimeout(500);
await page.screenshot({ path: OUT + "obsidian-view.png" });
console.log("• PDF page rendered under the frame, ink on top");

// draw a stroke with the mouse → save is called with a file that keeps our data
await page.evaluate(() => window.__inkScene.setActiveTool({ type: "freedraw" }));
const box = await page.locator("canvas.interactive").boundingBox();
await page.mouse.move(box.x + 500, box.y + 300); await page.mouse.down();
for (let i = 0; i < 25; i++) await page.mouse.move(box.x + 500 + i * 8, box.y + 300 + Math.sin(i / 3) * 20);
await page.mouse.up();
await page.waitForTimeout(500);
const saved = await page.evaluate(() => window.__saved.at(-1));
assert.ok(saved, "a save happened");
const s = JSON.parse(saved);
assert.equal(s.elements.filter((e) => e.type === "freedraw").length, 2, "new stroke saved");
assert.ok(!s.elements.some((e) => e.customData?.ephemeral), "rendered PDF page is not written to the file");
assert.deepEqual(s.elements.find((e) => e.id === "page1").customData.background, { kind: "pdf", file: "Lecture 3.pdf", page: 0 }, "page pointer kept");
assert.equal(s.inkcalidraw.pages.enabled, true, "app block kept");
assert.equal(s.appState.currentItemStrokeColor, "#e03131", "appState kept");
assert.ok(!Object.keys(s.files).some((k) => k.startsWith("pdf-")), "no rendered PDF images in files");
console.log("• editing saves back: new stroke written; PDF pointer, app block and appState preserved; rendered pages not stored");
await page.screenshot({ path: OUT + "obsidian-after-edit.png" });
await browser.close(); server.close();
console.log("ALL OBSIDIAN PLUGIN TESTS PASSED");
