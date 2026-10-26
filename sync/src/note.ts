import { DurableObject } from "cloudflare:workers";
import { applyOp, decode, encode, wins, type ClientMsg, type ElementJson, type Meta, type Op } from "./protocol";

export interface NoteEnv {
  LIBRARY: DurableObjectNamespace;
}

type Attachment = { cid: string };

/**
 * One Durable Object per note. Holds the note's elements in SQLite (one row per element, with the
 * sequence number of its last change) and all live WebSocket connections for that note.
 * Uses the WebSocket Hibernation API: idle connections cost nothing on the free plan.
 */
export class NoteRoom extends DurableObject<NoteEnv> {
  private sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: NoteEnv) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS elements (id TEXT PRIMARY KEY, version INTEGER, nonce INTEGER, json TEXT, seq INTEGER)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS elements_seq ON elements(seq)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)`);
  }

  private get(k: string): string | null {
    const r = this.sql.exec(`SELECT v FROM kv WHERE k = ?`, k).toArray()[0];
    return r ? (r.v as string) : null;
  }
  private set(k: string, v: string) { this.sql.exec(`INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v`, k, v); }

  private seq(): number { return Number(this.get("seq") ?? "0"); }

  private meta(): Meta { return JSON.parse(this.get("meta") ?? "{}"); }

  private element(id: string): ElementJson | null {
    const r = this.sql.exec(`SELECT json FROM elements WHERE id = ?`, id).toArray()[0];
    return r ? JSON.parse(r.json as string) : null;
  }

  private since(seq: number): ElementJson[] {
    return this.sql.exec(`SELECT json FROM elements WHERE seq > ? ORDER BY seq`, seq).toArray().map((r) => JSON.parse(r.json as string));
  }

  /** Applies ops; returns the ops that were accepted (as received) and ids whose patch base didn't match. */
  private apply(ops: Op[]): { accepted: Op[]; need: string[]; seq: number } {
    let seq = this.seq();
    const accepted: Op[] = [];
    const need: string[] = [];
    for (const op of ops) {
      const cur = this.element(op.id);
      if (cur && !wins({ version: op.v, versionNonce: op.n }, cur)) continue; // stale: we already have newer
      const next = applyOp(cur, op);
      if (!next) { need.push(op.id); continue; }
      seq++;
      this.sql.exec(
        `INSERT INTO elements (id, version, nonce, json, seq) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET version = excluded.version, nonce = excluded.nonce, json = excluded.json, seq = excluded.seq`,
        op.id, next.version, next.versionNonce, JSON.stringify(next), seq,
      );
      accepted.push(op);
    }
    this.set("seq", String(seq));
    return { accepted, need, seq };
  }

  private mergeMeta(m: Meta) {
    const cur = this.meta();
    const next: Meta = { ...cur, ...m, files: { ...(cur.files ?? {}), ...(m.files ?? {}) } };
    this.set("meta", JSON.stringify(next));
  }

  private async touchLibrary() {
    const uid = this.get("uid"); const id = this.get("noteId");
    const last = Number(this.get("libraryTouched") ?? "0");
    if (!uid || !id || Date.now() - last < 30_000) return;
    this.set("libraryTouched", String(Date.now()));
    const lib = this.env.LIBRARY.get(this.env.LIBRARY.idFromName(uid));
    await lib.fetch("https://do/touch", { method: "POST", body: JSON.stringify({ id, updated: Date.now() }) });
  }

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const uid = req.headers.get("X-User"); const noteId = req.headers.get("X-Note");
    if (uid && !this.get("uid")) { this.set("uid", uid); if (noteId) this.set("noteId", noteId); }

    if (url.pathname.endsWith("/ws")) {
      if (req.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
      const pair = new WebSocketPair();
      const cid = crypto.randomUUID().slice(0, 8);
      this.ctx.acceptWebSocket(pair[1]);
      pair[1].serializeAttachment({ cid } satisfies Attachment);
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    if (url.pathname.endsWith("/snapshot")) {
      return Response.json({ seq: this.seq(), elements: this.since(0), meta: this.meta() });
    }
    // REST push (used by clients without a socket, e.g. a one-off upload of a whole note)
    if (url.pathname.endsWith("/push") && req.method === "POST") {
      const msg = (await req.json()) as { ops: Op[]; meta?: Meta };
      const r = this.apply(msg.ops);
      if (msg.meta) this.mergeMeta(msg.meta);
      if (r.accepted.length || msg.meta) await this.broadcast({ t: "ops", seq: r.seq, ops: r.accepted, meta: msg.meta, from: "rest" }, null);
      await this.touchLibrary();
      return Response.json({ seq: r.seq, need: r.need });
    }
    return new Response("not found", { status: 404 });
  }

  private async broadcast(msg: unknown, except: WebSocket | null) {
    const data = await encode(msg);
    for (const ws of this.ctx.getWebSockets()) if (ws !== except) { try { ws.send(data); } catch { /* closed */ } }
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer) {
    let msg: ClientMsg;
    try { msg = (await decode(raw)) as ClientMsg; } catch { ws.send(JSON.stringify({ t: "error", message: "bad message" })); return; }
    const { cid } = ws.deserializeAttachment() as Attachment;
    if (msg.t === "hello") {
      const ops: Op[] = this.since(msg.since ?? 0).map((e) => ({ id: e.id, v: e.version, n: e.versionNonce, full: e }));
      ws.send(await encode({ t: "sync", seq: this.seq(), ops, meta: this.meta() }));
    } else if (msg.t === "push") {
      const r = this.apply(msg.ops ?? []);
      if (msg.meta) this.mergeMeta(msg.meta);
      ws.send(await encode({ t: "ack", seq: r.seq, need: r.need }));
      if (r.accepted.length || msg.meta) await this.broadcast({ t: "ops", seq: r.seq, ops: r.accepted, meta: msg.meta, from: cid }, ws);
      await this.touchLibrary();
    } else if (msg.t === "get") {
      const ops = (msg.ids ?? []).map((id) => this.element(id)).filter((e): e is ElementJson => !!e).map((e) => ({ id: e.id, v: e.version, n: e.versionNonce, full: e }));
      ws.send(await encode({ t: "full", ops }));
    }
  }

  async webSocketClose(ws: WebSocket, code: number) { try { ws.close(code, "bye"); } catch { /* already closed */ } }
}
