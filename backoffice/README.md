# Adera Backoffice

The staff dashboard for the Adera platform: mini apps, moderation, users, notifications, staff, audit log and system
health. React + TypeScript + Vite, [Refine](https://refine.dev) with Ant Design (default theme), Vitest for tests.
It is an independent project (own `package.json`), built as a container (node build, nginx static) and served by the
gateway at **http://127.0.0.1:9080/backoffice/**, the same origin as the admin API, so no CORS is needed.

## Run it

### With the stack (production-like)

```bash
docker compose up -d --build backoffice apisix      # from the repository root
open http://127.0.0.1:9080/backoffice/
```

The host does not need Node: `docker run --rm -v "$PWD":/app -w /app node:22-alpine sh -c 'npx tsc --noEmit && npx vitest run'` runs the type check and unit tests. The `backoffice` container has no published port. The gateway routes `/backoffice/*` to it (public static files) and
`/backoffice/api/health/*` to it behind the staff forward-auth (the System page's health probes).

### Development with hot reload

Needs Node 22.12 or newer (or run the commands through `docker run --rm -it -v "$PWD":/app -w /app -p 5173:5173 node:22-alpine ...`).

```bash
cd backoffice
npm install
npm run dev            # http://localhost:5173/backoffice/
```

Vite forwards `/admin`, `/kratos` and `/backoffice/api` to the gateway (`GATEWAY_URL`, default `http://127.0.0.1:9080`),
so the browser sees one origin and the Kratos native flows and the admin API work unchanged. The stack must be running
(`docker compose up -d`). `public/config.json` holds the dev defaults (Mailpit hint on, tool links).

### Other commands

| Command | What it does |
|---|---|
| `npm run build` | Type-checks (`tsc --noEmit`) and builds `dist/` (must pass with no TypeScript errors) |
| `npm test` | 65 unit tests (Vitest, jsdom): session handling, API client error mapping, role gating, Kratos flow handling, form payloads |
| `npm run smoke` | Live smoke test against the running dev stack: signs in as a real staff member and walks every section through the dashboard's own API client (see below) |
| `bash tests/ui/run.sh` | Browser walk-through with Playwright in a throw-away container; saves screenshots to `tests/ui/out/` |

## Configuration

Settings are not baked into the image. The container writes `/backoffice/config.json` at start from environment
variables (set in `docker-compose.yml`, overridable in the repo-root `.env`). It is public: never put secrets in it.

| Variable | Meaning | Default (dev) |
|---|---|---|
| `BACKOFFICE_DEV_HINTS` | Show development hints on the login page (the email code is in Mailpit). Follows `NOTIFY_DEV_ENDPOINTS` unless set. Must be `false` in production. | `true` |
| `BACKOFFICE_MAILPIT_URL` | Link in the dev hint and on the System page | `http://127.0.0.1:8025` |
| `BACKOFFICE_GRAFANA_URL`, `BACKOFFICE_PROMETHEUS_URL`, `BACKOFFICE_INFISICAL_URL`, `BACKOFFICE_RABBITMQ_URL`, `BACKOFFICE_RUSTFS_CONSOLE_URL` | Tool links on the System page (an empty value hides the link) | local dev ports |

At build time the base path is `/backoffice/` (`vite.config.ts`). Everything else is same-origin: the API is
`/admin/api/v1/...` and sign-in uses `/kratos/...`.

## How login works

1. **Email**: enter the staff email. Kratos' native login flow emails a 6-digit code (valid 5 minutes).
   In the dev stack the email goes to Mailpit (http://127.0.0.1:8025); the login page shows that hint only when
   `BACKOFFICE_DEV_HINTS` is true.
2. **Authenticator app**: the first time, the page shows the TOTP secret, the `otpauth://` link and a QR code; scan it,
   enter the first code to enrol. Later logins ask for the authenticator code only.
3. The result is an **aal2 session token** (both factors). The dashboard keeps it in memory and in `sessionStorage`
   (gone when the tab closes; never `localStorage`) and sends it as `Authorization: Bearer ...` to `/admin/api/v1/*`.
   The gateway checks it with registry-service (`/internal/staff/verify`) and passes `X-Staff-Id` / `X-Staff-Role` on.
   Native flows carry no cookies, so the gateway removes the `Origin` header from `POST /kratos/self-service/*`
   (Kratos refuses a native-flow submit that has one, and every browser `fetch()` POST sends it). A plain script does not
   send `Origin`, which is why this only showed up in the browser walk-through.
4. Errors are handled in one place: `401` goes to the login page; `403 mfa_required` goes to the authenticator step;
   `403 not_staff` signs out and shows "not an active staff member"; the gateway's `429` (10 login submits per minute
   per IP) shows a clear wait-a-minute message.

## Roles

The server enforces roles; the dashboard only mirrors them (hides or disables what would be refused).

| Role | Can do |
|---|---|
| VIEWER | Read apps, users (email, phone, date of birth, gender and extra data are masked or hidden by the server), notifications, reviews, reports, system |
| ADMIN | Everything a viewer can, plus changes: apps, capabilities, limits, IPs, keys, payment, templates, moderation, user status; reads the audit log and unmasked user data |
| SUPER_ADMIN | Everything an admin can, plus staff management and reviving a KILLED app |

## Screens and the API calls they make

See `docs/src/11-backoffice.html` in the repository docs.

## Code layout

```
src/lib        api client, errors, session store, Kratos flows, roles, formatting, shared types
src/auth       Refine auth provider, access control, login notices
src/dataProvider.ts   Refine data provider mapping resources to /admin/api/v1
src/components shared UI (error alert, confirm button, tables)
src/pages      one folder or file per section
tests/live     live smoke test (vitest, uses the real API client)
tests/ui       Playwright walk-through (runs in a container)
```

## Environments (DEV / PROD)

* Environments: the dashboard shows DEV and PROD (type `Environment` is `"DEV" | "PROD"`). `GET system` tells which one this deployment runs
  (`ADERA_ENV`): the Environments tab marks it "ACTIVE on this deployment" and the other "stored, not used here"; the Keys tab tags keys
  "active here" and warns when a key is made for the other environment; the System page names the environment; Usage defaults to it.

## Smoke tests

* The e2e scripts of `tests/e2e` use a temporary staff account (`tests/e2e/tempstaff.py`), never the bootstrap account; this smoke test and the walkthrough need `STAFF_EMAIL` of an account that has its authenticator secret file.

* `npm run smoke` needs the dev stack and a staff account. It reads `BOOTSTRAP_SUPER_ADMIN_EMAIL` from `../.env`
  (or `STAFF_EMAIL`) and reuses the authenticator secret saved by `tests/e2e/staff.py`
  (`tests/e2e/.staff-totp-<email>.json`). If there is none it enrols a new authenticator and saves it next to the test
  (`tests/.smoke-totp-...json`, mode 600, git-ignored). It creates one app `backoffice-smoke` (reused later, left KILLED)
  and a throw-away viewer account, which it deletes again. The gateway allows 10 login submits per minute per IP,
  so do not run it together with the Python e2e scripts.
* Kratos refuses an authenticator code it has already seen, so consecutive logins wait for the next 30-second window.
