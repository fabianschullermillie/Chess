import { handleAuth, userFromRequest, type AuthEnv } from "./auth";
export { NoteRoom } from "./note";
export { Library } from "./library";

interface Env extends AuthEnv {
  NOTES: DurableObjectNamespace;
  LIBRARY: DurableObjectNamespace;
  FILES: R2Bucket;
}

const ID = /^[A-Za-z0-9_-]{6,64}$/;
const SHA = /^[0-9a-f]{40}$/;

function cors(env: Env, req: Request, res: Response): Response {
  const origin = req.headers.get("Origin");
  if (!origin) return res;
  const ok = origin === env.WEB_ORIGIN || origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:");
  if (!ok || res.status === 101) return res;
  const h = new Headers(res.headers);
  h.set("Access-Control-Allow-Origin", origin);
  h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
  h.set("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, HEAD, OPTIONS");
  h.set("Vary", "Origin");
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
}

async function sha1Hex(buf: ArrayBuffer) {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-1", buf))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    if (req.method === "OPTIONS") return cors(env, req, new Response(null, { status: 204 }));
    try {
      return cors(env, req, await route(req, env));
    } catch (e) {
      return cors(env, req, Response.json({ error: String(e) }, { status: 500 }));
    }
  },
};

async function route(req: Request, env: Env): Promise<Response> {
  const url = new URL(req.url);
  const auth = await handleAuth(env, req);
  if (auth) return auth;
  if (url.pathname === "/") return new Response("inkcalidraw sync server");

  const user = await userFromRequest(env, req);
  if (!user) return Response.json({ error: "not signed in" }, { status: 401 });
  const parts = url.pathname.split("/").filter(Boolean); // ["api", ...]
  if (parts[0] !== "api") return new Response("not found", { status: 404 });

  if (parts[1] === "me") return Response.json(user);

  const library = env.LIBRARY.get(env.LIBRARY.idFromName(user.uid));
  if (parts[1] === "notes" && parts.length === 2) return library.fetch("https://do/list");

  if (parts[1] === "notes" && parts[2]) {
    const noteId = parts[2];
    if (!ID.test(noteId)) return Response.json({ error: "bad note id" }, { status: 400 });
    if (parts.length === 3 && req.method === "PUT") {
      const b = (await req.json()) as { name?: string; folder?: string };
      return library.fetch("https://do/upsert", { method: "POST", body: JSON.stringify({ id: noteId, ...b, updated: Date.now() }) });
    }
    if (parts.length === 3 && req.method === "DELETE") return library.fetch("https://do/delete", { method: "POST", body: JSON.stringify({ id: noteId }) });
    // ws | snapshot | push → the note's Durable Object (namespaced per user)
    const room = env.NOTES.get(env.NOTES.idFromName(`${user.uid}:${noteId}`));
    const headers = new Headers(req.headers);
    headers.set("X-User", user.uid); headers.set("X-Note", noteId);
    return room.fetch(new Request(`https://do/${parts.slice(3).join("/")}`, { method: req.method, headers, body: req.body }));
  }

  // Content-addressed files (images, PDFs): /api/files/<sha1>
  if (parts[1] === "files" && parts[2]) {
    const sha = parts[2];
    if (!SHA.test(sha)) return Response.json({ error: "bad sha" }, { status: 400 });
    const key = `${user.uid}/${sha}`;
    if (req.method === "HEAD") return new Response(null, { status: (await env.FILES.head(key)) ? 200 : 404 });
    if (req.method === "GET") {
      const obj = await env.FILES.get(key);
      if (!obj) return new Response("not found", { status: 404 });
      return new Response(obj.body, { headers: { "Content-Type": obj.httpMetadata?.contentType ?? "application/octet-stream", "Cache-Control": "private, max-age=31536000, immutable" } });
    }
    if (req.method === "PUT") {
      const body = await req.arrayBuffer();
      if ((await sha1Hex(body)) !== sha) return Response.json({ error: "content does not match sha1" }, { status: 400 });
      await env.FILES.put(key, body, { httpMetadata: { contentType: req.headers.get("Content-Type") ?? "application/octet-stream" } });
      return Response.json({ ok: true, size: body.byteLength });
    }
  }
  return new Response("not found", { status: 404 });
}
