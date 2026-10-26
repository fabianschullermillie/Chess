# Setting up cloud sync (one time, ~15 minutes)

Pieces:
- **Cloudflare Worker** `sync/` — login, live sync (one Durable Object per note), file storage (R2). Free plan.
- **Vercel** `web/` — the website with the Excalidraw editor. Free (Hobby) plan, personal use.
- **GitHub OAuth app** — "Sign in with GitHub" for the web and the tablet.

Do the steps in this order.

## 1. Vercel (web app)
1. vercel.com → **Add New → Project** → import the `Chess` repository.
2. **Root Directory**: `web`. Framework preset: Vite (auto-detected).
3. Deploy. After it's done, open **Settings → Git** and set **Production Branch** to `inkcalidraw`.
4. Note the URL, e.g. `https://inkcalidraw-web.vercel.app` → this is your **WEB_ORIGIN**.

## 2. Cloudflare (sync server)
1. dash.cloudflare.com → **R2** → enable R2 once (Cloudflare may ask for a payment method; the free tier is 10 GB).
2. **My Profile → API Tokens → Create Token → "Edit Cloudflare Workers"** template. Make sure it also has
   *Account → Workers R2 Storage → Edit*. Copy the token.
3. Your **Account ID** is shown on the right of **Workers & Pages → Overview**.

## 3. GitHub OAuth app (login)
github.com → **Settings → Developer settings → OAuth Apps → New OAuth App**
- Homepage URL: your WEB_ORIGIN
- Authorization callback URL: `https://inkcalidraw-sync.<your-subdomain>.workers.dev/auth/github/callback`
  (the subdomain is shown under Workers & Pages → your account's `workers.dev` subdomain)
- After creating: tick **Enable Device Flow**, then **Generate a new client secret**.

## 4. Repository settings (GitHub → Chess → Settings → Secrets and variables → Actions)
Secrets:
| name | value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | token from 2.2 |
| `CLOUDFLARE_ACCOUNT_ID` | id from 2.3 |
| `OAUTH_CLIENT_ID` | OAuth app client id |
| `OAUTH_CLIENT_SECRET` | OAuth app client secret |
| `SYNC_TOKEN_SECRET` | any long random string (signs login tokens) |

Variables:
| name | value |
|---|---|
| `SYNC_DEPLOY` | `true` |
| `ALLOWED_USERS` | your GitHub username (comma-separate to add more) |
| `WEB_ORIGIN` | WEB_ORIGIN from step 1 |
| `SYNC_URL` | `https://inkcalidraw-sync.<your-subdomain>.workers.dev` |

## 5. Deploy
1. GitHub → Actions → **Deploy sync server (Cloudflare)** → **Run workflow** (afterwards it runs on every push to `sync/`).
2. Vercel → Project → **Settings → Environment Variables**: `VITE_SYNC_URL` = SYNC_URL → **Redeploy**.
3. The next APK built by CI has SYNC_URL built in. On the tablet: Notes → **Cloud** → **Sign in with GitHub**
   (enter the shown code at github.com/login/device). Open a note: the top bar shows ● live.

## What costs what (free tiers)
- Durable Objects: hibernating WebSockets, so an open-but-idle note costs nothing. 100k requests/day.
- R2: images and PDFs, each stored once (content-addressed). 10 GB free.
- Vercel: static site only, no functions → nothing to run out of.
