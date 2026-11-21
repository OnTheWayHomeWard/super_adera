# Bridge Lab

A **test mini app** for the Adera super app. You open it *inside the real super app* and walk through a checklist of
manual, step-by-step tests that cover every way the super app and a mini app interact: the launch token, the
`window.adera` bridge and each of its outcomes and error codes, the permission dialog, the settings sheet, location,
deep links, the partner calls a mini app's backend makes (profile, notifications) and the callbacks Adera sends it.

It is **not a product and not an auto-runner**. A human taps Allow / Deny, opens the menu, pauses the app in the
backoffice and so on. Bridge Lab does the mini-app side of each step, shows *expected vs actual*, logs every message in
both directions and every backend call, and tells you plainly what the super app has not built yet.

Source of truth: `docs/guides/app-permissions/` (bridge-spec, super-app-api, mini-app, scenario, errors, known-issues),
`docs/guides/partner-api/`, `docs/guides/app-notifications/`, `docs/guides/app-catalog/`, `docs/guides/app-banners/`,
`docs/src/14-bridge-consent.html`.

## Contents

1. [What is inside](#what-is-inside)
2. [Ports and URLs](#ports-and-urls)
3. [Run it (dev laptop)](#run-it-dev-laptop)
4. [Register it (seed)](#register-it-seed)
5. [Open it from the super app](#open-it-from-the-super-app)
6. [Reading results and logs](#reading-results-and-logs)
7. [Automated checks](#automated-checks)
8. [Staging (157.245.133.250)](#staging-157245133250)
9. [Configuration](#configuration)
10. [Design](#design)
11. [The full test list](#the-full-test-list)

## What is inside

```
bridge-lab/
  README.md              this file
  Dockerfile             one container: Node 22, no npm dependencies
  docker-compose.yml     its own compose file; joins the Adera network (adera_default)
  server/                the backend (plain node:http)
    server.js            static files, JSON API, SSE log stream, callback receiver
    launch.js            launch-token verification against the Adera JWKS
    partner.js           ADERA-SIG-1 signing (RS256), response-signature check, callback check
    logbook.js           per-session in-memory log (last 500 entries) + SSE fan-out
  public/                the front end (plain HTML/CSS/JS, mobile first, light and dark)
    index.html, app.css
    js/bridge.js         wraps window.adera, AderaBridge.postMessage and _receive; validates every message
    js/lab.js            step runner, verdicts, results store (localStorage), report
    js/tests.js          THE test list (data + the steps' code)
    js/ui.js             screens: list, test, report, log drawer
    probe.html           the small page used by the origin-rule tests
  seed.py                registers "Bridge Lab" through the real admin API (temporary ADMIN), idempotent
  tests/
    e2e_backend.py       backend side against the real stack (launch token, tamper, profile, notify, callbacks)
    unit.test.mjs        node:test tests of the front-end logic with a fake window.adera (stub lives ONLY here)
    headless.mjs         real headless browser run (Playwright container): no bridge, then a stub bridge
    run_headless.sh      runs headless.mjs in the Playwright image
  .local/                gitignored: config.json and the partner private key written by seed.py
```

## Ports and URLs

| What | URL | Why |
|---|---|---|
| Bridge Lab (entry origin) | `http://localhost:8095` | the registered `entryUrl` is `http://localhost:8095/?entry=1` |
| Second allowed origin | `http://localhost:8096` | same server on a second port, listed in `allowedOrigins` (test O02) |
| Foreign origin | `http://127.0.0.1:8095` | same server, but an origin that is NOT registered (tests O03, O04) |
| Callback receiver (inside the compose network) | `http://bridge-lab:8095/adera/events` | the app's DEV callback URL |
| Gateway, as the backend calls it | `http://apisix:9080` | partner API and `/.well-known/jwks.json` |
| Grafana (links in the backend log) | `http://127.0.0.1:3001` | `GRAFANA_URL` |

8095 and 8096 are free on the dev stack and on staging (staging publishes 9080, 8090, 8025, 8091, 9000).
Why `localhost`: the registry accepts an `entryUrl` only if it is `https://...` or `http://localhost...` /
`http://127.0.0.1...`, so a plain LAN address is refused. A phone reaches the laptop's `localhost` with
`adb reverse` (below), the same way it reaches the gateway.

## Run it (dev laptop)

The Adera stack must be running (`docker compose up -d` at the repo root). Then:

```bash
cd bridge-lab
python3 -I seed.py                       # once (idempotent): registers the app, writes .local/
docker compose up -d --build             # starts the bridge-lab container on the adera_default network
open http://localhost:8095/?entry=1      # in a desktop browser: works, but shows "bridge not injected"
```

`docker compose logs -f bridge-lab` shows the backend's own log. Stop it with `docker compose down` (inside
`bridge-lab/`; this compose file only knows the bridge-lab service, it never touches the Adera services).

## Register it (seed)

`seed.py` uses a **temporary ADMIN** (`tests/e2e/tempstaff.py`: a random `tempstaff-xxxx@adera.local` account that is
removed at exit; it never touches `admin@adera.local`) and the real admin API through the gateway to:

1. create the mini app `bridge-lab` ("Bridge Lab") if it does not exist, with a generated logo and a support email;
2. set `entryUrl` = `http://localhost:8095/?entry=1` and `allowedOrigins` = `http://localhost:8095`, `http://localhost:8096`;
3. grant **every** capability of `GET /admin/api/v1/capabilities` with a purpose sentence (en);
4. set the DEV environment: allowed caller addresses (the docker ranges) and the callback URL
   `http://bridge-lab:8095/adera/events`;
5. generate an RSA key pair in `.local/` (private half never leaves the laptop) and register the public half (DEV);
6. create two approved notification templates (`bridge-lab-inapp` IN_APP, `bridge-lab-direct` DIRECT);
7. set the app LIVE;
8. write `.local/config.json` (app id, key id, template ids, capability labels and purposes, URLs).

Every step checks first and only changes what differs, so running it again is safe.
Options: `--entry-url URL`, `--alt-origin ORIGIN`, `--callback-url URL` (for other setups, see Staging), `--revive`
(a KILLED app needs a SUPER_ADMIN to come back: uses a temporary SUPER_ADMIN for that one call).

## Open it from the super app

1. Run the seed and start the container (above).
2. Phone on USB (or an emulator): `adb reverse tcp:8095 tcp:8095 && adb reverse tcp:8096 tcp:8096`
   (plus whatever you already reverse for the gateway, usually `tcp:9080`).
3. In the super app, sign in as a test user (dev: `+251900000001` / `kebede`, "Demo Abebe") and open **Bridge Lab**
   from the catalog. The super app calls `POST /api/v1/me/apps/bridge-lab/launch` and must load the returned
   `url` = `http://localhost:8095/?entry=1&launch=<token>`.
4. Bridge Lab verifies the token on its backend at once and shows the user's pseudonymous id at the top.

If the super app cannot open mini apps yet, the page still loads in a normal browser: almost every test then ends in
**Not developed yet** with the exact thing the super app must build, which is the point.

## Reading results and logs

* **Home**: tests grouped by area, each with its status (Not run, Pass, Fail, Not developed yet, Skipped) and a progress
  count at the top. Filter by status.
* **A test**: goal, preconditions, numbered steps. An automatic step has a **Run step** button and shows
  *expected*, *actual* and a verdict for that step. A human step says what to do in the super app (in bold). At the end
  you mark the test **Pass / Fail / Not developed / Skip** and can add a note. Bridge Lab proposes a verdict from the
  steps; the human decides.
* **Not developed detection**: no `window.adera` -> the bridge is not injected; a method missing -> that method is not
  built; no answer within 10 s (or the step's own longer limit when a human must tap something) -> the super app did
  not answer; an answer that breaks the spec (no `"adera": 1`, id mismatch, unknown state, unknown error code) -> **Fail**.
  A "Not developed" verdict always shows **what the super app must do**, quoting the spec, with a link to the section.
* **Report**: results per group plus "What the super app still needs to build"; download as Markdown or JSON.
  Results live in `localStorage` (per origin); **Reset** clears them.
* **Log drawer** (bottom, always there), two tabs:
  * **Bridge**: every message page -> super app and super app -> page, with time, round trip in ms, pretty JSON, spec
    violations in red, unsolicited messages marked, filter by method, copy, download JSON.
  * **Backend**: a live stream (server-sent events) from the Bridge Lab backend: launch-token checks (claims, each
    check, why it was rejected), every signed partner call (method, path, signed headers without the signature, status,
    body, `X-Request-Id`, trace id, whether Adera's response signature verified) and every callback received (headers,
    signature result, body). With `GRAFANA_URL` set each call gets links to its trace (Tempo) and its logs (Loki).
    The backend keeps the last 500 entries per session in memory, so reopening shows the history. A session is keyed by
    the user alias (`sub`) of the verified launch token.

## Automated checks

What can be checked without the super app:

```bash
python3 -I bridge-lab/tests/e2e_backend.py    # backend against the real stack (container must be running)
bash bridge-lab/tests/run_unit.sh             # node:test in a node:22-alpine container: front-end logic with a stub
bash bridge-lab/tests/run_headless.sh         # Playwright: the real page with no bridge, then with a stub bridge
```

`e2e_backend.py` signs in as Demo Abebe (password), gets a real launch token from
`POST /api/v1/me/apps/bridge-lab/launch`, has the Bridge Lab backend verify it (and tampered versions), makes signed
profile and notification calls through the backend, changes a consent through the user API and waits for the signed
`user.consent_changed` callback to arrive at the Bridge Lab receiver.

## Staging (157.245.133.250)

Not deployed by this change. To run it there later:

* The registry refuses a plain `http://157.245.133.250:8095` as `entryUrl` (only `https://` or `http://localhost`).
  Put Bridge Lab behind HTTPS, for example Caddy with an automatic certificate for `157-245-133-250.sslip.io`
  (or a tunnel), and use that address: `BRIDGE_LAB_PUBLIC_URL=https://lab.157-245-133-250.sslip.io`.
* On the server (it has the same repo and the same dev-mode `.env`): `python3 -I bridge-lab/seed.py
  --entry-url https://lab.../?entry=1 --alt-origin https://lab2...` (the seed needs the local Kratos admin port and
  `docker compose exec postgres`, which exist on the server), then `docker compose -f bridge-lab/docker-compose.yml up -d --build`.
* The callback URL stays `http://bridge-lab:8095/adera/events` (same compose network; staging runs with
  `PARTNER_ALLOW_PRIVATE_CALLBACKS=true` like the laptop).
* `GRAFANA_URL` is only reachable through an SSH tunnel on staging; leave it empty or set it to the tunnel address.

## Configuration

Environment of the container (all optional except what the seed writes to `.local/config.json`):

| Variable | Default | Meaning |
|---|---|---|
| `ADERA_BASE` | `http://apisix:9080` | gateway the backend calls (partner API, JWKS) |
| `ADERA_ENV` | `dev` | the `env` claim a launch token must carry |
| `ADERA_APP_ID`, `ADERA_KEY_ID`, `ADERA_PRIVATE_KEY_FILE` | from `.local/config.json` | the app and its partner key |
| `PORT`, `ALT_PORT` | `8095`, `8096` | the two listening ports |
| `BRIDGE_LAB_PUBLIC_URL` | `http://localhost:8095` | entry origin as the phone sees it |
| `BRIDGE_LAB_ALT_URL` | `http://localhost:8096` | the second allowed origin |
| `BRIDGE_LAB_FOREIGN_URL` | `http://127.0.0.1:8095` | an origin that is not registered |
| `CALLBACK_PATH` | `/adera/events` | where callbacks are received |
| `CALLBACK_MAX_SKEW_SECONDS` | `300` | callback timestamp tolerance |
| `GRAFANA_URL` | empty (compose sets `http://127.0.0.1:3001`) | links per trace id |
| `DOCS_URL` | `/docs` | where the spec links point (compose mounts `docs/guides` read-only there) |

Callbacks carry no shared secret: they are verified with Adera's public key from the JWKS over
`ADERA-SIG-1\n<timestamp>\nPOST\n<path>\n<sha256(body) base64>`, timestamp within 300 s.

## Design

* **One container, no dependencies.** Node 22's `http`, `crypto` and `fetch`; nothing to `npm install`.
* **The bridge is wrapped, never replaced.** `js/bridge.js` wraps the four methods of `window.adera` (call, result,
  rejection, time), `window.AderaBridge.postMessage` (the raw request on the wire) and `window.adera._receive` (the
  raw answer), and re-wraps if the super app replaces them. Every message is validated against the spec: `"adera": 1`,
  id (string, at most 64), method, an answer's id matching a request, `ok` with `result` or `error {code, message}`,
  states and error codes from the spec's tables. Answers with an unknown id are logged as **unsolicited**.
  Raw tests (malformed messages) post straight to `AderaBridge` and read the answer from `_receive`.
* **Verdict per step**: `pass`, `fail`, `notdev`, `info`, `skip`. A test proposes Fail if any step failed,
  Not developed if any step is not developed, Pass if all passed; the human confirms.
* **Partner calls are made by the backend**, never by the page, and always for the alias of the verified launch token
  (only the DEV synthetic aliases `u_p_test_*` and an unknown-alias probe may be chosen freely). Each call sends a
  W3C `traceparent` so its trace id is known, and logs the `X-Request-Id`.
* **Personal data in logs** (phone, email) is masked in the backend log; the full value is only in the test step's
  "actual" on the phone.
* **Dialog steps** wait longer than 10 s (a human must tap) and show a countdown; everything else times out at 10 s.

## The full test list

104 tests in 12 groups. "Human" = a person must act in the super app or the backoffice; "auto" = Bridge Lab
checks it; "not specified" = the docs imply super-app behaviour that has no contract yet: the test records what
happens and says what the super app should do.

### Launch (12)
| Id | Test | Kind |
|---|---|---|
| L01 | Opened with a launch token in the URL (`?launch=` from `POST .../launch`) | auto |
| L02 | The registered entry URL is kept (path and its query `entry=1`, token appended with `&`) | auto |
| L03 | The backend verifies the token against the JWKS (RS256, kid, iss, aud, exp, env) | auto |
| L04 | The token is fresh: `exp - iat = 300`, issued just now, right environment | auto |
| L05 | No permission dialog appears when the app opens | human |
| L06 | The scopes in the token match the permission states | auto |
| L07 | Tampered and foreign tokens are rejected (signature, payload, `alg: none`, HS256, unknown kid, garbage, other app) | auto |
| L08 | An expired token is rejected (wait 5 minutes) | human + auto |
| L09 | A replayed token (same `jti`) is noticed | auto |
| L10 | Every opening gets a new token (close and reopen) | human + auto |
| L11 | The page receives no session token, user id or scopes from the super app | auto |
| L12 | Opening Bridge Lab while signed out: sign in first, then launch | human |

### Bridge basics (11)
| Id | Test | Kind |
|---|---|---|
| B01 | `window.adera` is injected | auto |
| B02 | The four methods exist and return Promises | auto |
| B03 | Transport: the `AderaBridge` channel and `window.adera._receive` (or the web `postMessage`) | auto |
| B04 | Request messages follow the format (`adera: 1`, id at most 64, method, params) | auto |
| B05 | Answer messages follow the format (same id, `ok` + `result` or `error {code, message}`) | auto |
| B06 | Parallel calls are matched by id | auto |
| B07 | The bridge is still there after a reload | human + auto |
| B08 | The bridge is still there after same-origin navigation | auto |
| B09 | Unsolicited messages from the super app are logged and checked | auto |
| B10 | `getPermission` never shows a dialog (all capabilities) | auto + human |
| B11 | Web-hosted super app (iframe + `postMessage`): how `window.adera` gets there | not specified |

### Permissions (12)
| Id | Test | Kind |
|---|---|---|
| P01 | `user.basic` is granted with no dialog | auto + human |
| P02 | `getPermission` says `not_asked` before the first ask | auto |
| P03 | Ask, **Allow** -> `granted` (`user.full_name`); backend sees the name; callback GRANTED | human + auto |
| P04 | The dialog shows Adera's approved label and purpose verbatim | human |
| P05 | Ask, **Deny** -> `denied` (`user.contact`); backend 403 `consent_required` DENIED; callback | human + auto |
| P06 | Ask again after a denial (dialog shown once more), **Deny** -> `blocked`; callback BLOCKED | human + auto |
| P07 | `blocked` answers at once with no dialog | auto + human |
| P08 | The server decides: a switch changed in the menu is seen at once (no cache) | human + auto |
| P09 | `not_available` for a capability the app was not granted | auto |
| P10 | Staff remove a grant while the app is open -> `not_available` | human (staff) + auto |
| P11 | The backend sees the same consent (profile `granted` = page's granted `user.*`) | auto |
| P12 | One permission per call (no batch method, an array is `invalid_params`) | auto |

### Settings sheet (9)
| Id | Test | Kind |
|---|---|---|
| S01 | `openPermissionSettings()` opens the sheet and resolves `{"permissions": {...}}` on close | human + auto |
| S02 | The sheet lists exactly the app's granted capabilities | human + auto |
| S03 | Unblock only from the menu: blocked -> switch on -> `granted` without a dialog | human + auto |
| S04 | Switch off in the sheet -> `denied`, and the dialog may be shown again | human + auto |
| S05 | `user.basic` has no switch of its own (Disconnect instead) | human |
| S06 | The sheet opened from the super app's menu (not by the page); the page re-checks | human + auto |
| S07 | Disconnect: everything DENIED, `user.disconnected` callback, profile refused | human + auto |
| S08 | Reopening after Disconnect reconnects `user.basic` | human + auto |
| S09 | The super app's settings screen lists Bridge Lab and what it can see | human |

### Location (8)
| Id | Test | Kind |
|---|---|---|
| LO01 | `getLocation()` before consent -> `not_asked`, no dialog | auto + human |
| LO02 | Fine location after consent: `{state: "granted", location: {latitude, longitude, accuracyMeters, at}}` | human + auto |
| LO03 | Coarse location gives a place (`countryCode`, `region`, `city`), never coordinates | human + auto |
| LO04 | `precision: "coarse"` without `location.coarse` consent -> not granted | auto |
| LO05 | Phone permission off -> `denied`/`blocked` without saying which | human + auto |
| LO06 | Our consent switched off -> no reading (checked on the server) | human + auto |
| LO07 | Location services off on the phone | not specified |
| LO08 | Unknown options (`{precision: "exact"}`) | not specified |

### Profile (backend, signed) (8)
| Id | Test | Kind |
|---|---|---|
| PR01 | Profile with only `user.basic` -> 200, `alias` = `sub` | auto |
| PR02 | Adera's response signature verifies (ADERA-SIG-1-RESPONSE) | auto |
| PR03 | `?fields=` for a permission not allowed -> 403 `consent_required` with the page's state | auto |
| PR04 | Full name after the user allowed it | human + auto |
| PR05 | Contact and address after the user allowed them | human + auto |
| PR06 | DEV synthetic users `u_p_test_ok / noconsent / denied / blocked / unreachable` | auto |
| PR07 | Unknown alias -> 422 `user_unreachable` | auto |
| PR08 | Bad partner signatures refused (`invalid_signature`, `stale_timestamp`, `unknown_key`, `missing_signature`) | auto |

### Notifications (9)
| Id | Test | Kind |
|---|---|---|
| N01 | Bridge Lab's approved templates (`GET /v1/templates`) | auto |
| N02 | Default deny: an in-app message before `notify.inapp` is allowed -> 403 `consent_required` | auto |
| N03 | In-app message after the page asked for `notify.inapp` -> 202, SENT, visible in the bell | human + auto |
| N04 | SMS after `notify.sms` was allowed -> 202 channel SMS (dev SMS page) | human + auto |
| N05 | SMS refused -> 403, no fallback to email | human + auto |
| N06 | Muting SMS in the super app is a consent change (callback, 403) | human + auto |
| N07 | Idempotency on `externalId` (replay 200, changed 409) | auto |
| N08 | Daily SMS quota -> 429 `quota_exceeded` `max_per_user_per_day` | auto |
| N09 | Final status callback `notification.status`, signed | auto |

### Callbacks (6)
| Id | Test | Kind |
|---|---|---|
| C01 | The receiver refuses forged callbacks (self-test) | auto |
| C02 | `user.consent_changed` arrives after a change | human + auto |
| C03 | Every callback received verifies against the JWKS | auto |
| C04 | Duplicate deliveries are ignored (`Idempotency-Key`) | auto |
| C05 | `user.disconnected` lists everything taken back | auto (after S07) |
| C06 | Events are ordered by `at` (late events allowed) | auto |

### Deep links (6)
| Id | Test | Kind |
|---|---|---|
| D01 | Where was I opened? (path, query, hash) | auto |
| D02 | Banner (MINI_APP) with a route opens Bridge Lab at that route | human (staff) + auto |
| D03 | Banner with no route opens the home | human (staff) + auto |
| D04 | A route with a query string keeps both queries | human (staff) + auto |
| D05 | Notification with a deep link (`data`) opens Bridge Lab at the route | human + not specified |
| D06 | Push notification tap | not testable in dev (fake push) |

### Session and app state (9)
| Id | Test | Kind |
|---|---|---|
| SA01 | App paused while open -> `app_not_live` | human (staff) + auto |
| SA02 | App killed while open -> `app_not_live` (do last; reviving needs a SUPER_ADMIN) | human (staff) + auto |
| SA03 | Session expired -> `session_expired`, sign in again, retry | human + auto |
| SA04 | Account suspended -> `account_not_active` | human (staff) + auto |
| SA05 | No network -> `service_unavailable` | human + auto |
| SA06 | App sent to the background and back: the bridge still answers | human + auto |
| SA07 | Reload after the launch token expired | not specified |
| SA08 | Back button inside the mini app | not specified |
| SA09 | Language and theme handed to the mini app | not specified |

### Bridge error codes (9)
| Id | Test | Kind |
|---|---|---|
| E01 | `invalid_message`: not JSON, an array, no `adera: 1`, no id, id over 64, params over 1 KB | auto |
| E02 | `unknown_method` | auto |
| E03 | `invalid_params` (a capability that is not a key) | auto |
| E04 | `busy`: a second request while a dialog is open | human + auto |
| E05 | `dialog_dismissed`: the dialog closed with Back | human + auto |
| E06 | `prompt_expired`: answered after 5 minutes | human + auto |
| E07 | `too_many_requests`: 31 calls in a minute | auto |
| E08 | Every error has a known code and a message, and no personal data | auto |
| E09 | `internal_error` (cannot be provoked from the page) | skip |

### Origin rule (5)
| Id | Test | Kind |
|---|---|---|
| O01 | The bridge is present on the registered origin | auto |
| O02 | The bridge is present on another `allowedOrigins` origin | human + auto (probe page) |
| O03 | No bridge after navigating to a foreign origin | human + auto (probe page) |
| O04 | A foreign iframe gets no bridge and no answer | auto |
| O05 | External links open outside the mini app | not specified |
