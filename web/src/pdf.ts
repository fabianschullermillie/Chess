import type { ExcalidrawImperativeAPI } from "@excalidraw/excalidraw/types";
import type { ElementJson, Meta } from "../../sync/src/protocol";
import { api } from "./api";

type AnyEl = ElementJson & { customData?: Record<string, unknown> };
const docs = new Map<string, Promise<unknown>>();

/**
 * Pages of annotated PDFs aren't stored in the note (the tablet keeps the PDF next to it). In the browser
 * we render the referenced page with pdf.js and put it under the page frame as a local-only, locked
 * image (customData.ephemeral: never synced back).
 */
export async function pdfBackgrounds(elements: AnyEl[], meta: Meta, loaded: Set<string>, ex: ExcalidrawImperativeAPI): Promise<AnyEl[]> {
  const pdfs = ((meta.inkcalidraw as Record<string, unknown> | undefined)?.pdfs ?? {}) as Record<string, string>;
  const out: AnyEl[] = [];
  for (const f of elements) {
    const bg = f.customData?.background as { kind?: string; file?: string; page?: number } | undefined;
    if (f.type !== "frame" || f.isDeleted || bg?.kind !== "pdf" || !bg.file || !pdfs[bg.file]) continue;
    const sha = pdfs[bg.file]; const page = bg.page ?? 0;
    const fileId = `pdf-${sha.slice(0, 16)}-${page}`;
    if (!loaded.has(fileId)) {
      loaded.add(fileId);
      const dataURL = await renderPage(sha, page, Number(f.width) * 2);
      if (dataURL) ex.addFiles([{ id: fileId as never, mimeType: "image/png" as never, dataURL: dataURL as never, created: Date.now() }]);
    }
    out.push({
      id: `bg-${f.id}`, type: "image", x: f.x, y: f.y, width: f.width, height: f.height, angle: 0,
      strokeColor: "transparent", backgroundColor: "transparent", fillStyle: "solid", strokeWidth: 1, strokeStyle: "solid",
      roughness: 0, opacity: 100, groupIds: [], frameId: f.id, roundness: null, seed: 1, version: 1, versionNonce: 1,
      isDeleted: false, boundElements: null, updated: 1, link: null, locked: true, status: "saved", fileId, scale: [1, 1], crop: null,
      customData: { ephemeral: true },
    } as unknown as AnyEl);
  }
  return out;
}

async function renderPage(sha: string, page: number, widthPx: number): Promise<string | null> {
  try {
    const pdfjs = await import("pdfjs-dist");
    pdfjs.GlobalWorkerOptions.workerSrc = new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url).toString();
    if (!docs.has(sha)) docs.set(sha, (async () => {
      const r = (await api<Response>(`/api/files/${sha}`)) as unknown as Response;
      return pdfjs.getDocument({ data: new Uint8Array(await r.arrayBuffer()) }).promise;
    })());
    const doc = (await docs.get(sha)) as { getPage(n: number): Promise<{ getViewport(o: { scale: number }): { width: number; height: number }; render(o: unknown): { promise: Promise<void> } }> };
    const p = await doc.getPage(page + 1);
    const vp1 = p.getViewport({ scale: 1 });
    const vp = p.getViewport({ scale: widthPx / vp1.width });
    const canvas = document.createElement("canvas");
    canvas.width = Math.ceil(vp.width); canvas.height = Math.ceil(vp.height);
    await p.render({ canvasContext: canvas.getContext("2d")!, viewport: vp }).promise;
    return canvas.toDataURL("image/png");
  } catch (e) { console.warn("pdf page render failed", e); return null; }
}
