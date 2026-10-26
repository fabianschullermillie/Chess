/** Talks to the Cloudflare sync server. */
export const SYNC_URL: string = (import.meta.env.VITE_SYNC_URL as string | undefined) ?? "http://127.0.0.1:8787";

export function token(): string | null {
  const m = location.hash.match(/token=([^&]+)/);
  if (m) { localStorage.setItem("token", m[1]); history.replaceState(null, "", location.pathname); }
  return localStorage.getItem("token");
}

export function login() { location.href = `${SYNC_URL}/auth/github/start?redirect=${encodeURIComponent(location.origin + location.pathname)}`; }
export function logout() { localStorage.removeItem("token"); location.reload(); }

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const r = await fetch(`${SYNC_URL}${path}`, { ...init, headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token()}` } });
  if (r.status === 401) { localStorage.removeItem("token"); throw new Error("signed out"); }
  if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
  return r.headers.get("Content-Type")?.includes("json") ? r.json() : (r as unknown as T);
}

export type NoteInfo = { id: string; name: string; folder: string; updated: number };

export async function sha1(buf: ArrayBuffer): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-1", buf))].map((b) => b.toString(16).padStart(2, "0")).join("");
}
