import { chromium } from "playwright";
import fs from "fs";
const dir = process.argv[2];
const scene = fs.readFileSync(dir + "/scene.excalidraw", "utf8");
const outlines = JSON.parse(fs.readFileSync(dir + "/outlines.json", "utf8"));
const keys = JSON.parse(fs.readFileSync(dir + "/keys.json", "utf8"));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 520 }, deviceScaleFactor: 2 });
page.on("pageerror", (e) => console.error("PAGEERROR", e.message));
await page.setContent("<!doctype html><html><body style='margin:0;background:#fff'></body></html>");
await page.addScriptTag({ path: "verify.bundle.js" });
const report = await page.evaluate(([s, o, k]) => window.runVerify(s, o, k), [scene, outlines, keys]);
console.log(JSON.stringify(report, null, 1));
const failures = [];
for (const k of ["freedrawDataPreserved","foreignCustomDataPreserved","imageElementKept","imageFilePresent","indicesUnchangedByRestore","fractionalIndexMatches","svgEmbedsImage"]) if (report[k] !== true) failures.push(k);
if (report.elementsIn !== report.elementsAfterRestore) failures.push("elements dropped by restore()");
for (const p of report.paths) if (!p.exactStringMatch) failures.push("stroke outline differs: " + p.id);
if (failures.length) { console.error("COMPAT FAILURES:", failures); process.exitCode = 1; }
await page.locator("svg").first().screenshot({ path: process.argv[3] });
// ---- paged note
if (fs.existsSync(dir + "/paged.excalidraw")) {
  const paged = fs.readFileSync(dir + "/paged.excalidraw", "utf8");
  const pr = await page.evaluate((s) => window.runPaged(s), paged);
  console.log(JSON.stringify(pr, null, 1));
  for (const [k, v] of Object.entries(pr)) if (v !== true) { console.error("COMPAT FAILURE (paged):", k); process.exitCode = 1; }
  await page.setViewportSize({ width: 700, height: 1200 });
  await page.locator("svg").first().screenshot({ path: process.argv[3].replace(".png", "-paged.png") });
}
await browser.close();
