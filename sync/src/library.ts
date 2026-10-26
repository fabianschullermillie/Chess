import { DurableObject } from "cloudflare:workers";

/** One per user: the list of notes (name, folder path, last change). */
export class Library extends DurableObject {
  private sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: unknown) {
    super(ctx, env as never);
    this.sql = ctx.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, name TEXT, folder TEXT, updated INTEGER, deleted INTEGER DEFAULT 0)`);
  }

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/list") {
      const rows = this.sql.exec(`SELECT id, name, folder, updated FROM notes WHERE deleted = 0 ORDER BY updated DESC`).toArray();
      return Response.json(rows);
    }
    if (url.pathname === "/upsert") {
      const b = (await req.json()) as { id: string; name?: string; folder?: string; updated?: number };
      this.sql.exec(
        `INSERT INTO notes (id, name, folder, updated, deleted) VALUES (?, ?, ?, ?, 0)
         ON CONFLICT(id) DO UPDATE SET name = COALESCE(excluded.name, name), folder = COALESCE(excluded.folder, folder), updated = MAX(updated, excluded.updated), deleted = 0`,
        b.id, b.name ?? "Untitled", b.folder ?? "", b.updated ?? Date.now(),
      );
      return Response.json({ ok: true });
    }
    if (url.pathname === "/touch") {
      const b = (await req.json()) as { id: string; updated: number };
      this.sql.exec(`UPDATE notes SET updated = MAX(updated, ?) WHERE id = ?`, b.updated, b.id);
      return Response.json({ ok: true });
    }
    if (url.pathname === "/delete") {
      const b = (await req.json()) as { id: string };
      this.sql.exec(`UPDATE notes SET deleted = 1 WHERE id = ?`, b.id);
      return Response.json({ ok: true });
    }
    return new Response("not found", { status: 404 });
  }
}
