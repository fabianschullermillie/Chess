import { useEffect, useRef, useState } from "react";
import { CaptureUpdateAction, Excalidraw } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import type { ExcalidrawImperativeAPI, BinaryFileData } from "@excalidraw/excalidraw/types";
import { wins, type ElementJson, type Meta } from "../../sync/src/protocol";
import { api, sha1 } from "./api";
import { LiveSync } from "./sync";
import { pdfBackgrounds } from "./pdf";

type AnyEl = ElementJson & { index?: string; customData?: Record<string, unknown> };

export function Editor({ noteId }: { noteId: string }) {
  const [ex, setEx] = useState<ExcalidrawImperativeAPI | null>(null);
  const [status, setStatus] = useState<LiveSync["status"]>("connecting");
  const [stats, setStats] = useState("");
  const live = useRef<LiveSync | null>(null);
  const loadedFiles = useRef(new Set<string>());     // fileIds present in Excalidraw
  const uploaded = useRef(new Map<string, string>()); // fileId -> sha (on the server)
  const meta = useRef<Meta>({});

  useEffect(() => {
    if (!ex) return;
    // Remote updates are applied one after another; each reads the scene only after its async work.
    let queue: Promise<void> = Promise.resolve();
    const s = new LiveSync(noteId, (changed, m, initial) => { queue = queue.then(() => onRemote(changed, m, initial)).catch(console.error); }, setStatus);
    live.current = s;
    (window as unknown as { __ink: unknown }).__ink = { ex, sync: s }; // used by the end-to-end tests
    const t = setInterval(() => setStats(`↑ ${(s.stats.sentBytes / 1024).toFixed(1)} KB · ↓ ${(s.stats.recvBytes / 1024).toFixed(1)} KB`), 1000);
    return () => { clearInterval(t); s.close(); };

    async function onRemote(changed: ElementJson[], m: Meta | undefined, initial: boolean) {
      if (m) {
        meta.current = { ...meta.current, ...m, files: { ...(meta.current.files ?? {}), ...(m.files ?? {}) } };
        for (const [id, f] of Object.entries(m.files ?? {})) uploaded.current.set(id, f.sha);
        await loadFiles(m);
      }
      // Async work first (PDF pages), then read the scene and merge synchronously.
      const frames = [...ex!.getSceneElementsIncludingDeleted(), ...changed] as unknown as AnyEl[];
      const bg = await pdfBackgrounds(frames, meta.current, loadedFiles.current, ex!);
      const cur = new Map(ex!.getSceneElementsIncludingDeleted().map((e) => [e.id, e as unknown as AnyEl]));
      let dirty = false;
      for (const e of changed) {
        const c = cur.get(e.id);
        if (!c || wins(e, c)) { cur.set(e.id, e as AnyEl); dirty = true; }
      }
      for (const b of bg) { if (!cur.has(b.id)) { cur.set(b.id, b); dirty = true; } }
      if (!dirty) return;
      // Backgrounds first, then by fractional index (Excalidraw's z-order).
      const els = [...cur.values()].sort((a, b) => {
        const ea = a.customData?.ephemeral ? 0 : 1, eb = b.customData?.ephemeral ? 0 : 1;
        return ea - eb || String(a.index ?? "").localeCompare(String(b.index ?? ""));
      });
      ex!.updateScene({ elements: els as never, captureUpdate: CaptureUpdateAction.NEVER });
      if (initial) setTimeout(() => ex!.scrollToContent(undefined, { fitToContent: true }), 50);
    }

    async function loadFiles(m: Meta) {
      const add: BinaryFileData[] = [];
      for (const [id, f] of Object.entries(m.files ?? {})) {
        if (loadedFiles.current.has(id)) continue;
        loadedFiles.current.add(id);
        const blob = (await api<Response>(`/api/files/${f.sha}`)) as unknown as Response;
        const dataURL = await new Promise<string>((res) => { const r = new FileReader(); blob.blob().then((b) => { r.onload = () => res(r.result as string); r.readAsDataURL(b); }); });
        add.push({ id: id as never, mimeType: f.mimeType as never, dataURL: dataURL as never, created: Date.now() });
      }
      if (add.length) ex!.addFiles(add);
    }
  }, [ex, noteId]);

  async function onChange(elements: readonly unknown[], _state: unknown, files: Record<string, { id: string; dataURL: string; mimeType: string }>) {
    live.current?.local(elements as ElementJson[]);
    // New images drawn/pasted on the web: upload once (content-addressed), then announce in meta.
    for (const f of Object.values(files)) {
      if (uploaded.current.has(f.id) || f.id.startsWith("pdf-")) continue;
      uploaded.current.set(f.id, "pending");
      const bytes = await (await fetch(f.dataURL)).arrayBuffer();
      const sha = await sha1(bytes);
      await api(`/api/files/${sha}`, { method: "PUT", headers: { "Content-Type": f.mimeType }, body: bytes });
      uploaded.current.set(f.id, sha);
      live.current?.meta({ files: { [f.id]: { mimeType: f.mimeType, sha } } });
    }
  }

  const color = status === "live" ? "#2f9e44" : status === "connecting" ? "#f08c00" : "#e03131";
  return (
    <div style={{ height: "100%", display: "flex", flexDirection: "column" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "6px 12px", borderBottom: "1px solid #e3e3ea", fontSize: 14 }}>
        <a href="#/" style={{ textDecoration: "none", color: "#1e1e1e" }}>‹ Notes</a>
        <span style={{ flex: 1 }} />
        <span style={{ color: "#6b6b75" }}>{stats}</span>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <span style={{ width: 8, height: 8, borderRadius: 4, background: color }} /> {status}
        </span>
      </div>
      <div style={{ flex: 1 }}>
        <Excalidraw excalidrawAPI={setEx} onChange={onChange as never} />
      </div>
    </div>
  );
}
