# Setting up cloud sync (one time, ~15 minutes)

Pieces:
- **Cloudflare Worker** `sync/` — login, live sync (one Durable Object per note), file storage (R2). Free plan.
- **GitHub Pages** `web/` — the website with the Excalidraw editor (static), deployed by `.github/workflows/deploy-web.yml`
  to the `gh-pages` branch. (Vercel works too: root directory `web`, env `VITE_SYNC_URL`.)
- **GitHub OAuth app** — "Sign in with GitHub" for the web and the tablet.

Do the steps in this order.

## 1. GitHub Pages (web app)
1. The workflow already publishes the site to the `gh-pages` branch on every push to `web/`.
2. Repo **Settings → Pages**: Source = *Deploy from a branch*, Branch = `gh-pages` / root.
   (GitHub Pages for a **private** repository needs GitHub Pro; otherwise make the repository public.)
3. The site is `https://fabianschullermillie.github.io/Chess/` → your **WEB_ORIGIN** is `https://fabianschullermillie.github.io`.

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
2. Actions → **Deploy web app (GitHub Pages)** → **Run workflow** so the site is rebuilt with SYNC_URL built in
   (or just enter the server address on the site's sign-in page once).
3. The next APK built by CI has SYNC_URL built in. On the tablet: Notes → **Cloud** → **Sign in with GitHub**
   (enter the shown code at github.com/login/device). Open a note: the top bar shows ● live.

## What costs what (free tiers)
- Durable Objects: hibernating WebSockets, so an open-but-idle note costs nothing. 100k requests/day.
- R2: images and PDFs, each stored once (content-addressed). 10 GB free.
- GitHub Pages: static site, free.
