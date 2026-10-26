# Shared brief for the guide-writing agents (read fully before you start)

Repo: /home/mahtot/projects/personal/adera. Dev stack is running (gateway http://127.0.0.1:9080 = `$GATEWAY`, dev mode, ADERA_ENV=dev:
console SMS, Mailpit http://127.0.0.1:8025, fake push). Several agents write DIFFERENT sections at the same time. Stay inside your own folder.

## Who the guides are for
Adera's own people: (1) super-app developers (the mobile/web client team and backend colleagues) and (2) the owner/admins. They must be able to
use and operate EVERY functionality that exists, API first, without reading code. "Super app" = the Adera app. "Mini app" = a partner service
listed in it. Only staff (admins) create/configure mini apps, so the developer sections cover how to READ and USE everything exposed to them,
and the admin sections cover every staff action. Document what EXISTS (verified); put what does not exist yet in a short "Not built yet" list at
the end of the relevant page (never describe unbuilt features as if they work).

## Sources of truth (code wins; verify, do not guess)
- Controllers/services: services/*/src/main/java; gateway routes: infra/apisix/apisix.yaml; Kratos config: infra/kratos/kratos.yml.
- Existing in-house docs with already-captured real examples you may REUSE after checking them against the running stack: docs/index.html,
  docs/src/*.html (08a user API, 08b staff API, 08c partner API, 08d internal, 08e Kratos flows, 06 security, 07 data, 09 guides, 10 reference,
  12 backoffice manual, 13 sample data), docs/SETUP.md, docs/PARTNER_API.md. Those pages are NOT shareable (they interlink); your section must be
  standalone and must not link to them.
- Demo data you can use in examples (already seeded, do not modify or delete it): apps demo-food, demo-ride, demo-market, demo-flights,
  demo-bank, demo-quota, demo-killed, demo-paused; 15 "Demo ..." users (see docs/src/13-sample-data.html and tests/e2e/seed_scenarios.py).

## Shareable-section rules (IMPORTANT)
- Your section is a folder `docs/guides/<your-folder>/` containing `index.html` (section landing page with the table of contents) plus pages.
  Copy `docs/guides/_template/style.css` and `app.js` into your folder (or an `assets/` subfolder) and reference them relatively.
- The section must be COMPLETELY self-contained so the folder can be zipped/served alone and shared: every link and asset resolves inside the
  folder; NO link to `../`, to other sections, or to docs/src; no external CDNs/fonts/scripts (works from file://). Cross-references to other
  topics are written as plain text ("see the Partner API guide") with no link. There is NO "back to home" link: the hub links down into the
  section, never the other way. Navigation inside the section: sidebar listing the pages + previous/next at the bottom of each page.
- Same look as the existing docs (reuse the template CSS classes; look at docs/src/08a-user-api.html and 11-backoffice.html for markup of
  endpoint blocks, tables, callouts, copy buttons, light/dark). Phone-width friendly. Each page has a clear <title>, h1, and unique ids.
- Verify your folder with `python3 -I docs/guides/check.py <your-folder>` (it must print OK). Also open every page's HTML mentally for unbalanced tags.

## What every endpoint/action entry must contain
1. Purpose in plain language AND why it exists / when to use it (the product reason).
2. Method + path exactly as the client calls it through the gateway; who may call it (auth: user session Bearer / staff aal2 Bearer + role /
   partner signature / internal secret) and required headers; gateway rate limits that apply.
3. Every request field/query/path parameter: type, required/optional, constraints, default, and WHY it exists.
4. A REAL request (curl with `$GATEWAY`, `$TOKEN` placeholders) and the REAL response (captured from the running stack, pretty-printed; field
   names/casing/status codes must match exactly). Every documented error response: status, `error` code, when it happens, what the caller should do.
5. How to use the result in an app (what the client should render/store/do next), edge cases and gotchas (idempotency, caching, ordering, masking).
6. For admin actions also: required role, what changes in the system, what is audited (the audit action name), side effects on other services
   (e.g. what partners/users see afterwards), reversibility, and a worked example scenario ("when would you do this").
Also give each page a short "Overview", and each section landing page: audience, base URLs, auth summary, conventions (error shape
`{"error": code, ...}`, validation_failed.fields, pagination, ids, timestamps, languages en/am/om), a glossary of the section's terms.

## How to capture real examples safely
- Reuse previously captured examples from docs/src when still accurate (re-verify a sample against the live stack). Capture new ones with curl/python.
- User login in dev: tests/e2e/journey.py and tests/e2e/staff.py show the Kratos native flows (the one-time code is read from
  http://127.0.0.1:8085/dev/messages). The gateway allows only 10 login-code submits per minute per IP and the notification service about
  10 one-time-code emails per hour per address, and OTHER AGENTS ARE USING THE SAME STACK: log in ONCE, reuse the session token for all your
  calls, sleep/retry on 429, and prefer the seeded demo users (their phones +251900000001..015 are fake: you cannot read OTPs for existing users
  unless you log in as them, which works in dev via the dev endpoint). For staff actions use a TEMPORARY staff account via tests/e2e/tempstaff.py
  (never the bootstrap admin@adera.local account: a human's authenticator is enrolled on it). Remove anything you create when you finish.
- Do NOT modify or delete seeded demo data. If an example needs a write (e.g. create an app), create a throwaway object with a clearly
  temporary name (prefix `doc-tmp-`) and delete it afterward (the platform has no delete API for some objects: SQL via
  `docker compose exec -T postgres psql -U adera_admin -d <db>` is acceptable ONLY for removing your own throwaway rows). Never leave test
  users, apps, keys, templates or notifications behind. Do not stop/restart containers.
- Dev mode only: nothing real is sent. Do not use the Firebase file under /home/mahtot/work/equb.
- Never print or embed secrets (.env values, tokens, session tokens, TOTP secrets, private keys, signed URLs' credentials). Use placeholders
  like `$TOKEN`; mask personal data (+2519*****04, demo emails are fine).
- Do NOT change service code, config, gateway, backoffice or tests. If you find a bug or inconsistency, list it in your final report AND in the
  section's last page "Known issues" (short, with repro).

## Final report (plain text, short)
Pages written (paths), endpoints/actions documented (count), anything you could not verify, bugs found, and the result of check.py.
