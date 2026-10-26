import { applyOp, decode, diffOp, encode, wins, type ElementJson, type Meta, type Op, type ServerMsg } from "../../sync/src/protocol";
import { SYNC_URL, token } from "./api";

/**
 * Live connection for one note. Keeps `known` = the last state of every element both sides agree on,
 * so local changes go out as small patches against it, and reconnects resume from the last seq.
 */
export class LiveSync {
  private ws: WebSocket | null = null;
  private chain: Promise<void> = Promise.resolve();
  private seq = 0;
  private known = new Map<string, ElementJson>();
  private outbox = new Map<string, ElementJson>();
  private pendingMeta: Meta | null = null;
  private flushTimer: number | null = null;
  private closed = false;
  private retry = 500;
  status: "connecting" | "live" | "offline" = "connecting";
  stats = { sentBytes: 0, recvBytes: 0, opsOut: 0, opsIn: 0 };

  constructor(
    private noteId: string,
    /** Called with elements that changed remotely (full JSON) and remote meta. */
    private onRemote: (elements: ElementJson[], meta: Meta | undefined, initial: boolean) => void,
    private onStatus: (s: LiveSync["status"]) => void,
  ) { this.connect(); }

  private connect() {
    if (this.closed) return;
    const url = `${SYNC_URL.replace(/^http/, "ws")}/api/notes/${this.noteId}/ws?token=${token()}`;
    const ws = new WebSocket(url);
    ws.binaryType = "arraybuffer";
    this.ws = ws;
    this.setStatus("connecting");
    ws.onopen = async () => { this.retry = 500; ws.send(await encode({ t: "hello", since: this.seq })); };
    ws.onmessage = (ev) => {
      this.stats.recvBytes += typeof ev.data === "string" ? ev.data.length : (ev.data as ArrayBuffer).byteLength;
      // Decoding is async (deflate); keep messages strictly in arrival order.
      this.chain = this.chain.then(async () => this.handle((await decode(ev.data)) as ServerMsg)).catch((e) => console.error(e));
    };
    ws.onclose = () => {
      this.ws = null; this.setStatus("offline");
      if (!this.closed) setTimeout(() => this.connect(), this.retry = Math.min(this.retry * 2, 10_000));
    };
  }

  private setStatus(s: LiveSync["status"]) { this.status = s; this.onStatus(s); }

  private handle(msg: ServerMsg) {
    if (msg.t === "sync") {
      const initial = this.seq === 0;
      const changed: ElementJson[] = [];
      for (const op of msg.ops) if ("full" in op) { this.known.set(op.id, op.full); changed.push(op.full); }
      this.seq = msg.seq;
      this.setStatus("live");
      this.onRemote(changed, msg.meta, initial);
      if (this.outbox.size) this.schedule(); // resend what we changed while offline
    } else if (msg.t === "ops" || msg.t === "full") {
      const changed: ElementJson[] = [];
      const missing: string[] = [];
      for (const op of msg.ops) {
        const cur = this.known.get(op.id) ?? null;
        if (cur && !wins({ version: op.v, versionNonce: op.n }, cur)) continue;
        const next = applyOp(cur, op);
        if (!next) { missing.push(op.id); continue; }
        this.known.set(op.id, next); changed.push(next);
        this.stats.opsIn++;
      }
      if (msg.t === "ops") this.seq = Math.max(this.seq, msg.seq);
      if (missing.length) void this.send({ t: "get", ids: missing });
      if (changed.length || (msg.t === "ops" && msg.meta)) this.onRemote(changed, msg.t === "ops" ? msg.meta : undefined, false);
    } else if (msg.t === "ack") {
      this.seq = Math.max(this.seq, msg.seq);
      // Server didn't have the base of some patches: send those elements in full.
      for (const id of msg.need) { const e = this.known.get(id); if (e) { this.known.delete(id); this.outbox.set(id, e); } }
      if (msg.need.length) this.schedule();
    }
  }

  private async send(m: unknown) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false;
    const data = await encode(m);
    this.stats.sentBytes += typeof data === "string" ? data.length : data.byteLength;
    this.ws.send(data);
    return true;
  }

  /** Queue local elements; only those whose version differs from what the server has are sent. */
  local(elements: readonly ElementJson[]) {
    for (const e of elements) {
      if ((e.customData as Record<string, unknown> | undefined)?.ephemeral) continue; // web-only helpers (PDF page images)
      const k = this.known.get(e.id);
      if (k && k.version === e.version && k.versionNonce === e.versionNonce) continue;
      this.outbox.set(e.id, JSON.parse(JSON.stringify(e)));
    }
    if (this.outbox.size) this.schedule();
  }

  meta(m: Meta) { this.pendingMeta = { ...(this.pendingMeta ?? {}), ...m, files: { ...(this.pendingMeta?.files ?? {}), ...(m.files ?? {}) } }; this.schedule(); }

  /** Batch changes for 40 ms: a stroke being drawn streams at ~25 updates/s. */
  private schedule() {
    if (this.flushTimer != null) return;
    this.flushTimer = window.setTimeout(() => { this.flushTimer = null; void this.flush(); }, 40);
  }

  private async flush() {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN || this.status !== "live") return;
    const ops: Op[] = [];
    for (const [id, e] of this.outbox) { ops.push(diffOp(this.known.get(id), e)); this.known.set(id, e); }
    this.outbox.clear();
    const meta = this.pendingMeta; this.pendingMeta = null;
    if (!ops.length && !meta) return;
    this.stats.opsOut += ops.length;
    await this.send(meta ? { t: "push", ops, meta } : { t: "push", ops });
  }

  close() { this.closed = true; this.ws?.close(); }
}
