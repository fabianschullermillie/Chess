/** Login with GitHub (web redirect flow + device flow for the tablet) and our own signed tokens. */

export interface AuthEnv {
  GITHUB_CLIENT_ID: string;
  GITHUB_CLIENT_SECRET: string;
  TOKEN_SECRET: string;
  ALLOWED_USERS: string;
  WEB_ORIGIN: string;
  DEV_LOGIN?: string; // local testing only (.dev.vars): enables /auth/dev
}

export type User = { uid: string; login: string };

const b64url = (b: ArrayBuffer | Uint8Array) =>
  btoa(String.fromCharCode(...new Uint8Array(b instanceof Uint8Array ? b : new Uint8Array(b)))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const fromB64url = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4)), (c) => c.charCodeAt(0));

async function hmacKey(secret: string) {
  return crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

export async function sign(env: AuthEnv, payload: object, ttlSeconds: number): Promise<string> {
  const body = b64url(new TextEncoder().encode(JSON.stringify({ ...payload, exp: Math.floor(Date.now() / 1000) + ttlSeconds })));
  const sig = await crypto.subtle.sign("HMAC", await hmacKey(env.TOKEN_SECRET), new TextEncoder().encode(body));
  return `${body}.${b64url(sig)}`;
}

export async function verify<T = Record<string, unknown>>(env: AuthEnv, token: string | null): Promise<T | null> {
  if (!token || !env.TOKEN_SECRET) return null;
  const [body, sig] = token.split(".");
  if (!body || !sig) return null;
  const ok = await crypto.subtle.verify("HMAC", await hmacKey(env.TOKEN_SECRET), fromB64url(sig), new TextEncoder().encode(body));
  if (!ok) return null;
  const p = JSON.parse(new TextDecoder().decode(fromB64url(body)));
  return p.exp > Date.now() / 1000 ? (p as T) : null;
}

export async function userFromRequest(env: AuthEnv, req: Request): Promise<User | null> {
  const url = new URL(req.url);
  const h = req.headers.get("Authorization");
  const token = h?.startsWith("Bearer ") ? h.slice(7) : url.searchParams.get("token");
  const p = await verify<{ uid: string; login: string; kind: string }>(env, token);
  return p && p.kind === "session" ? { uid: p.uid, login: p.login } : null;
}

function allowed(env: AuthEnv, login: string) {
  return env.ALLOWED_USERS.split(",").map((s) => s.trim().toLowerCase()).filter(Boolean).includes(login.toLowerCase());
}

async function sessionFor(env: AuthEnv, gh: { id: number; login: string }) {
  return sign(env, { kind: "session", uid: `gh${gh.id}`, login: gh.login }, 60 * 60 * 24 * 180); // 180 days
}

async function githubUser(accessToken: string) {
  const r = await fetch("https://api.github.com/user", {
    headers: { Authorization: `Bearer ${accessToken}`, "User-Agent": "inkcalidraw-sync", Accept: "application/vnd.github+json" },
  });
  if (!r.ok) throw new Error(`GitHub user lookup failed: ${r.status}`);
  return (await r.json()) as { id: number; login: string };
}

const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });

/** Handles /auth/* routes. Returns null for other paths. */
export async function handleAuth(env: AuthEnv, req: Request): Promise<Response | null> {
  const url = new URL(req.url);
  const self = `${url.origin}/auth/github/callback`;

  if (url.pathname === "/auth/github/start") {
    const redirect = url.searchParams.get("redirect") ?? env.WEB_ORIGIN;
    if (!redirect.startsWith(env.WEB_ORIGIN)) return new Response("redirect not allowed", { status: 400 });
    const state = await sign(env, { kind: "state", redirect }, 600);
    const gh = new URL("https://github.com/login/oauth/authorize");
    gh.searchParams.set("client_id", env.GITHUB_CLIENT_ID);
    gh.searchParams.set("redirect_uri", self);
    gh.searchParams.set("scope", "read:user");
    gh.searchParams.set("state", state);
    return Response.redirect(gh.toString(), 302);
  }

  if (url.pathname === "/auth/github/callback") {
    const st = await verify<{ kind: string; redirect: string }>(env, url.searchParams.get("state"));
    if (!st || st.kind !== "state") return new Response("bad state", { status: 400 });
    const tok = await fetch("https://github.com/login/oauth/access_token", {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ client_id: env.GITHUB_CLIENT_ID, client_secret: env.GITHUB_CLIENT_SECRET, code: url.searchParams.get("code"), redirect_uri: self }),
    }).then((r) => r.json() as Promise<{ access_token?: string; error?: string }>);
    if (!tok.access_token) return new Response(`GitHub login failed: ${tok.error}`, { status: 401 });
    const gh = await githubUser(tok.access_token);
    if (!allowed(env, gh.login)) return new Response(`${gh.login} is not allowed on this server (ALLOWED_USERS).`, { status: 403 });
    return Response.redirect(`${st.redirect}#token=${await sessionFor(env, gh)}`, 302);
  }

  // Device flow: the tablet shows a code, you type it at github.com/login/device.
  if (url.pathname === "/auth/device/start" && req.method === "POST") {
    const r = await fetch("https://github.com/login/device/code", {
      method: "POST", headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ client_id: env.GITHUB_CLIENT_ID, scope: "read:user" }),
    });
    return json(await r.json(), r.status);
  }
  if (url.pathname === "/auth/device/poll" && req.method === "POST") {
    const { device_code } = (await req.json()) as { device_code: string };
    const tok = await fetch("https://github.com/login/oauth/access_token", {
      method: "POST", headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ client_id: env.GITHUB_CLIENT_ID, device_code, grant_type: "urn:ietf:params:oauth:grant-type:device_code" }),
    }).then((r) => r.json() as Promise<{ access_token?: string; error?: string; interval?: number }>);
    if (!tok.access_token) return json({ pending: true, error: tok.error, interval: tok.interval });
    const gh = await githubUser(tok.access_token);
    if (!allowed(env, gh.login)) return json({ error: `${gh.login} is not allowed on this server` }, 403);
    return json({ token: await sessionFor(env, gh), login: gh.login });
  }

  // Local end-to-end tests only.
  if (url.pathname === "/auth/dev" && env.DEV_LOGIN === "1") {
    const login = url.searchParams.get("login") ?? "dev";
    return json({ token: await sign(env, { kind: "session", uid: `dev-${login}`, login }, 3600), login });
  }
  return null;
}
