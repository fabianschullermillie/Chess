import { useEffect, useState } from "react";
import { login, token } from "./api";
import { Library } from "./Library";
import { Editor } from "./Editor";

function route() { return location.hash.startsWith("#/n/") ? decodeURIComponent(location.hash.slice(4)) : null; }

export function App() {
  const [noteId, setNoteId] = useState(route());
  const [signedIn] = useState(!!token());
  useEffect(() => { const f = () => setNoteId(route()); window.addEventListener("hashchange", f); return () => window.removeEventListener("hashchange", f); }, []);
  if (!signedIn) return (
    <div style={{ display: "grid", placeItems: "center", height: "100%", background: "#f4f4f6" }}>
      <div style={{ textAlign: "center" }}>
        <h1 style={{ fontWeight: 600 }}>Inkcalidraw</h1>
        <p style={{ color: "#6b6b75" }}>Your notes from the tablet, live in the browser.</p>
        <button onClick={login} style={btn}>Sign in with GitHub</button>
      </div>
    </div>
  );
  return noteId ? <Editor key={noteId} noteId={noteId} /> : <Library />;
}

export const btn: React.CSSProperties = { background: "#6965db", color: "white", border: 0, borderRadius: 20, padding: "10px 18px", fontSize: 15, cursor: "pointer" };
