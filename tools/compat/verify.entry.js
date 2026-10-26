import { restore, getFreeDrawSvgPath, exportToSvg } from "@excalidraw/excalidraw";
import { generateKeyBetween } from "fractional-indexing";

// Same as Excalidraw's internal outline -> path conversion (incl. its 2-decimal truncation)
const med = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
const TRUNC = /(\s?[A-Z]?,?-?[0-9]*\.[0-9]{0,2})(([0-9]|e|-)*)/g;
const toPath = (pts) => {
  if (!pts.length) return "";
  const max = pts.length - 1;
  return pts.reduce((acc, p, i, arr) => {
    if (i === max) acc.push(p, med(p, arr[0]), "L", arr[0], "Z"); else acc.push(p, med(p, arr[i + 1]));
    return acc;
  }, ["M", pts[0], "Q"]).join(" ").replace(TRUNC, "$1");
};
const nums = (s) => (s.match(/-?\d+(\.\d+)?(e-?\d+)?/g) || []).map(Number);

window.runVerify = async (sceneText, outlines, keys) => {
  const report = {};
  const raw = JSON.parse(sceneText);
  const restored = restore(raw, null, null);
  const els = restored.elements;
  report.elementsIn = raw.elements.length;
  report.elementsAfterRestore = els.length;
  report.types = els.map((e) => e.type);
  // data fidelity after Excalidraw's own restore()
  report.freedrawDataPreserved = raw.elements.filter(e=>e.type==="freedraw").every((e) => {
    const r = els.find((x) => x.id === e.id);
    return r && JSON.stringify(r.points) === JSON.stringify(e.points) && JSON.stringify(r.pressures) === JSON.stringify(e.pressures)
      && r.simulatePressure === false && r.strokeWidth === e.strokeWidth && r.strokeColor === e.strokeColor;
  });
  const foreign = els.find((e) => e.id === "foreignRect01");
  report.foreignCustomDataPreserved = foreign?.customData?.obsidian === "keep-me";
  const imgIn = raw.elements.find((e) => e.type === "image");
  const imgOut = els.find((e) => e.type === "image");
  report.imageElementKept = !!imgOut && imgOut.fileId === imgIn.fileId && imgOut.status === "saved" && imgOut.width === imgIn.width;
  report.imageFilePresent = !!restored.files?.[imgIn.fileId]?.dataURL?.startsWith("data:image/png;base64,");
  report.indicesUnchangedByRestore = raw.elements.every((e) => els.find((x) => x.id === e.id)?.index === e.index);

  // Rendering parity: Excalidraw's own path vs path from our Kotlin outline
  report.paths = [];
  for (const e of els.filter((e) => e.type === "freedraw")) {
    const theirs = getFreeDrawSvgPath(e);
    const ours = toPath(outlines[e.id]);
    const a = nums(theirs), b = nums(ours);
    let maxDiff = a.length === b.length ? 0 : Infinity;
    if (a.length === b.length) for (let i = 0; i < a.length; i++) maxDiff = Math.max(maxDiff, Math.abs(a[i] - b[i]));
    report.paths.push({ id: e.id, strokeWidth: e.strokeWidth, points: e.points.length, outlinePts: outlines[e.id].length, coordsCompared: a.length, sameCount: a.length === b.length, exactStringMatch: theirs === ours, maxAbsDiff: maxDiff });
  }

  // Fractional index parity
  let k = null, ok = true;
  for (let i = 0; i < keys.length; i++) { k = generateKeyBetween(k, null); if (k !== keys[i]) { ok = false; report.firstKeyMismatch = [i, k, keys[i]]; break; } }
  report.fractionalIndexMatches = ok;

  const svg = await exportToSvg({ elements: els, appState: { ...restored.appState, exportBackground: true, viewBackgroundColor: "#ffffff" }, files: restored.files, skipInliningFonts: true });
  document.body.appendChild(svg);
  report.svgEmbedsImage = svg.outerHTML.includes('data:image/png;base64');
  return report;
};
