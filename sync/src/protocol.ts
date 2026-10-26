/**
 * Inkcalidraw sync protocol (shared by the Worker, the web app and — re-implemented — the Android app).
 *
 * The unit of sync is an Excalidraw element. Every element already carries `version` (incremented on
 * every edit) and `versionNonce` (random per edit), so merging is per element and needs no history:
 *   remote wins  ⇔  remote.version > local.version || (equal && remote.versionNonce < local.versionNonce)
 * (the same rule Excalidraw's own collaboration uses).
 *
 * Deltas: a client only sends elements whose version changed since it last synced them, and for an
 * element the server already has at `base`, only the top-level fields that changed ("patch"); arrays
 * that only grew (points/pressures of a stroke being drawn) are sent as appended items ("app").
 * Typical sizes: new stroke = the stroke itself; erasing a stroke ≈ 80 bytes; moving ≈ 120 bytes.
 * Frames larger than 1 KB are sent as binary WebSocket frames containing deflate-raw(JSON).
 *
 * Client → server
 *   {t:"hello", since}                          → server replies "sync" with everything newer than seq `since`
 *   {t:"push", ops, meta?}                      → server replies "ack" and broadcasts "ops" to other clients
 *   {t:"get", ids}                              → server replies "full" (used when a patch's base doesn't match)
 * Server → client
 *   {t:"sync", seq, ops, meta}                  ops are always full elements
 *   {t:"ack", seq, need}                        need = ids whose patch couldn't be applied (send them in full)
 *   {t:"ops", seq, ops, meta?, from}            changes made by another client (full or patch)
 *   {t:"full", ops}
 */

export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
export type ElementJson = { id: string; version: number; versionNonce: number; [k: string]: Json };

/** A full element, or a patch against `base` (the element's previous version). */
export type Op =
  | { id: string; v: number; n: number; full: ElementJson }
  | { id: string; v: number; n: number; base: number; patch: { [k: string]: Json }; del?: string[]; app?: { [k: string]: Json[] } };

/** Scene-level data that isn't an element: appState subset, our app block, file descriptors. */
export type Meta = { appState?: Json; inkcalidraw?: Json; files?: { [fileId: string]: { mimeType: string; sha: string } } };

export type ClientMsg =
  | { t: "hello"; since: number }
  | { t: "push"; ops: Op[]; meta?: Meta }
  | { t: "get"; ids: string[] };

export type ServerMsg =
  | { t: "sync"; seq: number; ops: Op[]; meta: Meta }
  | { t: "ack"; seq: number; need: string[] }
  | { t: "ops"; seq: number; ops: Op[]; meta?: Meta; from: string }
  | { t: "full"; ops: Op[] }
  | { t: "error"; message: string };

export function wins(remote: { version: number; versionNonce: number }, local?: { version: number; versionNonce: number } | null): boolean {
  if (!local) return true;
  return remote.version > local.version || (remote.version === local.version && remote.versionNonce < local.versionNonce);
}

/** Applies an op to the element the receiver has (or null). Returns null if a patch's base doesn't match. */
export function applyOp(current: ElementJson | null, op: Op): ElementJson | null {
  if ("full" in op) return op.full;
  if (!current || current.version !== op.base) return null;
  const next: ElementJson = { ...current, ...op.patch } as ElementJson;
  for (const k of op.del ?? []) delete (next as Record<string, Json>)[k];
  for (const [k, items] of Object.entries(op.app ?? {})) {
    const prev = (current as Record<string, Json>)[k];
    (next as Record<string, Json>)[k] = [...(Array.isArray(prev) ? prev : []), ...items];
  }
  next.version = op.v;
  next.versionNonce = op.n;
  return next;
}

/** Builds the smallest op that turns `prev` (what the other side has) into `next`. */
export function diffOp(prev: ElementJson | null | undefined, next: ElementJson): Op {
  if (!prev) return { id: next.id, v: next.version, n: next.versionNonce, full: next };
  const patch: { [k: string]: Json } = {};
  const app: { [k: string]: Json[] } = {};
  const del: string[] = [];
  for (const k of Object.keys(next)) {
    if (k === "version" || k === "versionNonce") continue;
    const a = prev[k], b = next[k];
    if (JSON.stringify(a) === JSON.stringify(b)) continue;
    // A stroke being drawn only grows: send just the new points/pressures.
    if (Array.isArray(a) && Array.isArray(b) && b.length > a.length && a.every((x, i) => JSON.stringify(x) === JSON.stringify(b[i]))) app[k] = b.slice(a.length);
    else patch[k] = b;
  }
  for (const k of Object.keys(prev)) if (!(k in next)) del.push(k);
  const op: Op = { id: next.id, v: next.version, n: next.versionNonce, base: prev.version, patch };
  if (del.length) (op as { del?: string[] }).del = del;
  if (Object.keys(app).length) (op as { app?: { [k: string]: Json[] } }).app = app;
  // A patch that is nearly the whole element isn't worth it.
  return JSON.stringify(op).length > JSON.stringify(next).length * 0.8 ? { id: next.id, v: next.version, n: next.versionNonce, full: next } : op;
}

const enc = new TextEncoder();
const dec = new TextDecoder();

/** Encodes a message: plain JSON text, or deflate-raw(JSON) bytes when that's worth it. */
export async function encode(msg: unknown): Promise<string | Uint8Array> {
  const text = JSON.stringify(msg);
  if (text.length < 1024) return text;
  const stream = new Blob([enc.encode(text)]).stream().pipeThrough(new CompressionStream("deflate-raw"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

export async function decode(data: string | ArrayBuffer | Uint8Array): Promise<unknown> {
  if (typeof data === "string") return JSON.parse(data);
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  const stream = new Blob([bytes as unknown as ArrayBuffer]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
  return JSON.parse(dec.decode(await new Response(stream).arrayBuffer()));
}
