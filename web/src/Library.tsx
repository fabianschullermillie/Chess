import { useEffect, useMemo, useState } from "react";
import { api, logout, type NoteInfo } from "./api";
import { btn } from "./App";

export function Library() {
  const [notes, setNotes] = useState<NoteInfo[] | null>(null);
  const [q, setQ] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api<NoteInfo[]>("/api/notes").then(setNotes).catch((e) => setError(String(e))); }, []);

  const groups = useMemo(() => {
    const m = new Map<string, NoteInfo[]>();
    for (const n of notes ?? []) if (!q || n.name.toLowerCase().includes(q.toLowerCase())) m.set(n.folder || "", [...(m.get(n.folder || "") ?? []), n]);
    return [...m.entries()].sort(([a], [b]) => a.localeCompare(b));
  }, [notes, q]);

  async function create() {
    const id = crypto.randomUUID().replace(/-/g, "").slice(0, 20);
    await api(`/api/notes/${id}`, { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ name: "Web note " + new Date().toLocaleString(), folder: "" }) });
    location.hash = `#/n/${id}`;
  }

  return (
    <div style={{ background: "#f4f4f6", minHeight: "100%", padding: "24px 32px", boxSizing: "border-box" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, marginBottom: 20 }}>
        <h1 style={{ margin: 0, flex: 1, fontWeight: 600 }}>Notes</h1>
        <input placeholder="Search" value={q} onChange={(e) => setQ(e.target.value)} style={{ padding: "8px 12px", borderRadius: 18, border: "1px solid #ddd", fontSize: 15 }} />
        <button style={btn} onClick={create}>+ New note</button>
        <button style={{ ...btn, background: "#e0dfff", color: "#1e1e1e" }} onClick={logout}>Sign out</button>
      </div>
      {error && <p style={{ color: "#e03131" }}>{error}</p>}
      {notes === null && !error && <p>Loading…</p>}
      {notes?.length === 0 && <p style={{ color: "#6b6b75" }}>No synced notes yet. Open a note on the tablet while signed in and it appears here.</p>}
      {groups.map(([folder, list]) => (
        <section key={folder} style={{ marginBottom: 24 }}>
          <h3 style={{ color: "#6b6b75", fontWeight: 500 }}>{folder || "Notes"}</h3>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(220px, 1fr))", gap: 14 }}>
            {list.map((n) => (
              <a key={n.id} href={`#/n/${n.id}`} style={{ background: "white", borderRadius: 12, padding: 14, textDecoration: "none", color: "#1e1e1e", border: "1px solid #e3e3ea" }}>
                <div style={{ fontWeight: 500 }}>{n.name}</div>
                <div style={{ color: "#6b6b75", fontSize: 12, marginTop: 4 }}>{new Date(n.updated).toLocaleString()}</div>
              </a>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
