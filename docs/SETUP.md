# Adera: local platform setup

State of the platform: infrastructure (steps 1–8), file storage (RustFS), and three application services:
**`notification-service`** (OTP delivery and the partner notification platform), **`user-service`** (profiles, avatars, devices/FCM tokens, Kratos link) and **`registry-service`** (mini apps, staff, audit), plus the staff dashboard in `backoffice/`.
All three are Spring Boot 4.1, Java 21, Maven; each microservice is its own independent Maven project under `services/`.

This file contains **no secret values**. Secrets live in `.env` (container secrets) and Infisical (API keys).

---

## 1. What this is

The backend foundation for the Adera super app (design docs: <http://165.22.183.71:3039/>).
Goal for this phase: email-or-phone login with one-time codes, sessions, logout, and FCM token tracking.

Approach: use proven open-source tools wherever possible; write code only where the product logic is ours.
Nothing is on Kubernetes; everything runs in one `docker-compose.yml`, bound to `127.0.0.1`.

## 2. Architecture

```
Flutter app
    │
    ▼
APISIX gateway :9080   (rate limits, CORS, request-id, session check, tracing)
    ├──► /kratos/*        ──► Kratos :4433   (identity, one-time-code login, sessions)
    ├──► /kratos/admin/*  ──► 403 (blocked)
    └──► /api/v1/me/**    ──► forward-auth ──► user-service /internal/auth/verify ──► Kratos whoami
                              (sets X-User-Id + X-Gateway-Secret)  ──► user-service :8086 (Spring)
                                                                          ├─ Postgres "users"  (profile, devices)
                                                                          └─ RustFS :9000      (avatar files, S3 API)

Kratos ──HTTP (SMS + email)──► notification-service :8085 (Spring)  ──┬─ dev:  console log / Mailpit
                                                                      └─ live: Twilio / Resend
                                  (mode chosen by SMS_PROVIDER / EMAIL_PROVIDER in .env)
Kratos ──web_hook (after sign-up, non-blocking)──► user-service /internal/kratos/after-registration  (creates the profile)

Observability:  containers ─logs─► Alloy ─► Loki ─┐
                APISIX/Kratos ─traces─► OTel Collector ─► Tempo ─┼─► Grafana :3001
                Prometheus ◄─metrics─ APISIX, Kratos, RabbitMQ… ─┘
Secrets:  Infisical :8080  (API keys: Twilio, Resend)   ·   .env  (container secrets)
Data:     Postgres (kratos, users, notification, infisical)   Redis   RabbitMQ
```

## 3. Tools, versions and ports

All host ports are bound to `127.0.0.1` only.

| Tool | Image / version | Host port(s) | Purpose |
|---|---|---|---|
| Postgres | `postgres:16-alpine` | 5432 | One server; one database + login per service |
| Redis | `redis:7-alpine` | 6379 | Rate-limit counters, Infisical queues. ACL users, `noeviction`, 256 MB |
| RabbitMQ | `rabbitmq:4-management-alpine` | 5672 (AMQP), 15672 (UI) | Events between services. Vhost `adera` |
| Mailpit | `axllent/mailpit:v1.21` | 1025 (SMTP), 8025 (UI) | Dev-only fake mail server and inbox |
| Infisical | `infisical/infisical:v0.167.2` | 8080 | Self-hosted secrets manager |
| Ory Kratos | `oryd/kratos:v26.2.0` | 4433 (public), 4434 (admin) | Identity, login, sessions, logout |
| APISIX | `apache/apisix:3.19.0-debian` | 9080 | API gateway, standalone (file-driven) mode |
| **notification-service** | built from `services/notification-service` (Java 21 JRE) | 8085 (dev) | **Ours.** Receives Kratos' SMS/email, rate-limits, delivers via the selected provider, logs. See its README |
| **user-service** | built from `services/user-service` (Java 21 JRE) | 8086 (dev) | **Ours.** Full user record, location, free-form JSON, avatar upload, devices + FCM tokens, gateway verify endpoint, Kratos webhook. See its README |
| RustFS | `rustfs/rustfs:1.0.3` | 9000 (S3 API), 9001 (console) | S3-compatible object storage; avatars today, other files later. Bucket `adera-media` |
| Prometheus | `prom/prometheus:v3.15.1` | 9090 | Metrics, 15-day retention |
| Grafana | `grafana/grafana-oss:13.0.2` | 3001 | Dashboards, logs and traces UI |
| Loki | `grafana/loki:3.7.9` | (internal) | Log storage, 7-day retention |
| Alloy | `grafana/alloy:v1.21.2` | (internal) | Ships all container logs to Loki (mounts Docker socket read-only) |
| Tempo | `grafana/tempo:2.10.10` | (internal) | Trace storage, 3-day retention |
| OTel Collector | `otel/opentelemetry-collector-contrib:0.163.2` | 4317 (gRPC), 4318 (HTTP) | The one endpoint our services send telemetry to |
| (one-shot) | `curlimages/curl:8.10.1` | – | `rabbitmq-setup`: creates vhost/users |
| (one-shot) | `oryd/kratos:v26.2.0` | – | `kratos-migrate`: applies DB migrations |

Chosen against alternatives: Kratos over Keycloak/SuperTokens (headless, API-first, passwordless code flow);
APISIX standalone over Kong (config in git, no etcd); RabbitMQ over NATS/Kafka (Spring support, scale);
Alloy over Promtail (Promtail is being retired).

## 4. Repository layout

```
docker-compose.yml          all services
.env                        container secrets + Infisical bootstrap   (gitignored)
.env.example                variable names only
.gitignore
docs/
  SETUP.md                  this file
  kratos-openapi-v26.2.0.json   Kratos API spec (import into Postman/Swagger)
  ride_flow-credentials.env     reference creds from the GoBellMe project (gitignored)
services/                       one independent Maven project per microservice (no shared parent build)
  notification-service/         Spring Boot 4.1 · Java 21 · own pom, Dockerfile, README, tests
  user-service/                 same shape (a shared lib is only extracted when real shared code exists)
tests/e2e/
  journey.py                    end-to-end journey through the gateway (sign-up → profile → avatar → devices → logout); 29 checks
  s3tool.py                     dependency-free SigV4 client to list/delete objects in RustFS
infra/
  postgres/init-databases.sh    creates DBs + logins on first start of an empty volume
  redis/{entrypoint.sh,redis.conf}   builds ACL users from env at start
  rabbitmq/setup.sh             idempotent vhost/user/permission setup
  kratos/{kratos.yml,identity.schema.json}
  apisix/{config.yaml,apisix.yaml}
  prometheus/prometheus.yml
  grafana/{provisioning/,dashboards/}
  loki/loki.yml   alloy/config.alloy   tempo/tempo.yml   otel-collector/config.yaml
```

## 5. Running it

```bash
docker compose up -d          # start everything
docker compose ps             # all long-running services should be Up / healthy
docker compose logs -f kratos
docker compose stop           # pause everything (containers and data stay); add a service name to stop just one
docker compose start          # start the stopped containers again, no rebuild
docker compose down           # stop (volumes are kept)
```

Edits to a bind-mounted config need a restart of that service (`docker compose restart <name>`);
APISIX `apisix.yaml` route changes hot-reload within about a second.

### URLs

| What | URL |
|---|---|
| Gateway (the app talks only to this) | <http://127.0.0.1:9080> |
| Grafana (user `admin`, password `GRAFANA_ADMIN_PASSWORD`) | <http://localhost:3001> |
| Infisical | <http://localhost:8080> |
| Mailpit inbox | <http://localhost:8025> |
| notification-service dev messages (OTP codes, dev mode only) | <http://localhost:8085/dev/messages> |
| RustFS console (`RUSTFS_ACCESS_KEY` / `RUSTFS_SECRET_KEY`) | <http://localhost:9001> |
| user-service direct (dev only; needs the gateway secret, so use the gateway) | <http://localhost:8086> |
| RabbitMQ UI (`RABBITMQ_ADMIN_USER`) | <http://localhost:15672> |
| Prometheus | <http://localhost:9090> |

## 6. Secrets policy

| Kind | Where | Examples |
|---|---|---|
| Container secrets | `.env` | DB / Redis / RabbitMQ / Grafana passwords, Kratos cookie + cipher keys |
| Infisical's own bootstrap | `.env` | `INFISICAL_ENCRYPTION_KEY`, `INFISICAL_AUTH_SECRET`, its DB/Redis passwords |
| CLI access to Infisical | `.env` | `INFISICAL_TOKEN`, `INFISICAL_PROJECT_ID`, `INFISICAL_DOMAIN` |
| API keys | **Infisical** `dev` → `/notification` | `TWILIO_*`, `RESEND_API_KEY`, `RESEND_FROM_EMAIL`, `RESEND_FROM_NAME`. Loaded by `notification-service` at startup via the Infisical API (not injected by compose) |
| Delivery mode | `.env` | `SMS_PROVIDER`, `EMAIL_PROVIDER`, `NOTIFY_DEV_ENDPOINTS`, `NOTIFY_LIVE_ALLOWLIST` |
| Storage + gateway trust | `.env` | `RUSTFS_ACCESS_KEY`, `RUSTFS_SECRET_KEY` (RustFS admin keys; user-service uses them for now), `USER_GATEWAY_SECRET` (shared by APISIX and user-service) |

- **Back up `INFISICAL_ENCRYPTION_KEY` and `INFISICAL_AUTH_SECRET` off this machine.** Losing the encryption key makes every secret in Infisical unreadable.
- Infisical: project `Adera`, environments `dev` and `prod` (prod is empty). Only `/notification` holds secrets (the nine `TWILIO_*`, `RESEND_*` and `PARTNER_SIGNING_*` names); the folders `/infra`, `/kratos`, `/gateway` and `/user` exist but are empty (checked 2026-10-25, see docs section 2).
- The CLI token belongs to machine identity `adera-local-dev` (Token Auth) and **expires 2026-11-23**. Use Universal Auth for anything beyond local dev.
- Still to add to Infisical: `FIREBASE_SERVICE_ACCOUNT_B64` (new Firebase project) under `/notification`.
- Resend currently uses the GoBellMe account and sender `bookings@gobellme.com` (temporary; change in Infisical, not in code).
- Infisical CLI note: `secrets delete` needs `--type shared` for shared secrets.

## 7. Data stores

**Postgres databases** (each owned by its own login, public access revoked): `kratos`, `users`, `notification`, `infisical`.
Admin login is `POSTGRES_USER`; service logins are `kratos`, `users_svc`, `notif_svc`, `infisical`.

**Redis ACL users:** `default` is disabled; `admin` (everything); `infisical` (no admin commands);
`apisix` (only keys `plugin-limit-count:*`, no admin or dangerous commands).

**RabbitMQ:** vhost `adera`; users `user_svc`, `notification_svc` (AMQP only, no management access); permissions are wide (`.*`) for now and should be narrowed to per-service names once the topology exists. RabbitMQ 4 only allows durable queues (Spring's default).

## 7a. Delivery modes (dev vs real messages)

Kratos always sends to `notification-service`; the service decides, from `.env`:

| `.env` | dev (default) | real |
|---|---|---|
| `SMS_PROVIDER` | `console`: logs the SMS, nothing leaves the machine | `twilio`: real SMS, costs money |
| `EMAIL_PROVIDER` | `mailpit`: local inbox <http://localhost:8025> | `resend`: real email |
| `NOTIFY_DEV_ENDPOINTS` | `true`: `GET /dev/messages` shows codes | must be `false` (service refuses to start otherwise) |
| `NOTIFY_LIVE_ALLOWLIST` | ignored | if set, real sends go only to these recipients |

Each channel switches independently. Defaults are the safe dev values. After editing `.env`: `docker compose up -d notification-service`.
The startup log prints `NOTIFICATION MODE: sms=…, email=…`. Verified: a throwaway instance in real mode loaded the 7 Twilio/Resend secrets
from Infisical and started with `sms=twilio (REAL)`, `email=resend (REAL)`, without sending anything.

## 7aa. Environments: dev or prod (`ADERA_ENV`)

One deployment is exactly one environment: the dev deployment is DEV, the prod deployment is PROD, and they never co-exist.
`ADERA_ENV=dev|prod` (default `dev`, documented in `.env.example`) is passed by `docker-compose.yml` to **registry-service** and
**notification-service**; a different value stops the service from starting. Staff can fill in the allowed server IPs and register keys
for **both** environments in the dashboard, but only the one that matches the deployment is active: a partner key of the other
environment is refused with `403 wrong_environment`, and limits, usage and stored notifications use the deployment's environment.
App status rules are unchanged: DEV works unless the app is KILLED, PROD only while the app status is LIVE.

* **Switch:** set `ADERA_ENV=prod` in `.env`, then `docker compose up -d registry-service notification-service` (both must agree; a mismatch is
  logged and partner requests answer 503). A prod deployment should also use real providers: `SMS_PROVIDER=twilio`, `EMAIL_PROVIDER=resend`,
  `NOTIFY_DEV_ENDPOINTS=false` (see the deployment checklist in section 12).
* **Database:** Flyway runs automatically at start. Registry `V2__environments_dev_prod.sql` and notification-service `V3__environments_dev_prod.sql`
  rename existing rows in place (TEST to DEV, LIVE to PROD) in `app_environment`, `partner_key`, `notification`, `usage_daily`, `usage_total` and
  queued callback payloads, and add `CHECK (environment IN ('DEV','PROD'))`. Existing key ids keep their old `ak_test_` / `ak_live_` names (they
  are opaque identifiers partners already use); new keys are `ak_dev_...` / `ak_prod_...`.
* **Sandbox:** there is no separate simulated sandbox. The dev providers (console SMS, Mailpit, fake push) are the sandbox, so real users in DEV are
  delivered through them like any message. Only the synthetic users `u_p_test_*` are simulated in DEV (nothing sent, attempt provider `sandbox`):
  they have no real contact details or push tokens, so they must not reach providers. In PROD they are refused (`422 synthetic_user_not_allowed`).
* **Dashboard:** `GET /admin/api/v1/system` returns `{"environment":"DEV"}`; the Environments tab shows DEV and PROD, marks the active one; the Keys tab
  tags each key "active here" or not.

## 7b. user-service: the full user record

The Kratos identity id **is** the user id. Kratos owns identity, login and sessions; `user-service` owns everything else.
Database `users`: table `app_user` (profile) and `device` (push tokens). Flyway migration `V1__users_and_devices.sql`.

| Group | Fields |
|---|---|
| Identity copy | `id` (= Kratos id), `email`, `phone_e164` (copied from Kratos traits) |
| Profile | `first_name`, `last_name`, `gender` (MALE/FEMALE/OTHER/PREFER_NOT_TO_SAY), `date_of_birth`, `language` (am/en/om), `market` (ET/US), `timezone` |
| Avatar | `avatar_image_id` (UUID, links to the file in RustFS), `avatar_content_type`, `avatar_updated_at` |
| Location (optional, user-shared) | `country_code`, `region`, `city`, `latitude`, `longitude`, `location_updated_at` |
| Account | `verification_tier` (CONTACT/ID/PROVIDER), `status` (ACTIVE/SUSPENDED/DELETED), `terms_accepted_version/at`, `last_seen_at`, `created_at`, `updated_at`, `deleted_at` |
| **`extra`** | **`jsonb`, free-form.** For data we did not plan for. Edited with JSON Merge Patch (add/change keys, `null` removes), max 16 KB |

`device`: `user_id`, `installation_id` (stable app-generated id), `platform`, `fcm_token` (unique), app/os version, device name, locale, `is_active`, `last_seen_at`, `token_updated_at`.

**API** (all through the gateway, session token as `Authorization: Bearer …`; the gateway adds `X-User-Id`):

| Call | Purpose |
|---|---|
| `GET /api/v1/me` | Profile (created on first use if the Kratos webhook was lost). `avatarUrl` is a 60-minute signed link |
| `PATCH /api/v1/me` | Partial update; absent = unchanged, `null` = clear; unknown fields rejected; `extra` merged |
| `PUT` / `DELETE /api/v1/me/location` | Share / withdraw location (city, region, country, optional lat+lng) |
| `POST /api/v1/me/avatar` (multipart `file`) / `DELETE` | Upload (≤5 MB; JPEG, PNG, GIF, BMP) / remove |
| `PUT /api/v1/me/devices` | Register or refresh an FCM token (call after login and on token rotation) |
| `GET /api/v1/me/devices`, `DELETE /api/v1/me/devices` | List (token never echoed) / remove by `fcmToken` or `installationId` |
| `POST /api/v1/me/logout` | Remove the push token **and** revoke the Kratos session in one call |

Errors are `{"error":"<code>","fields":{…}}`: 400 validation, 401 no/invalid session, 404, 409 (email/phone owned by another account), 413 file too large, 503 Kratos unavailable.

**How it is linked to Kratos**
1. *Sign-up:* Kratos' registration `web_hook` (non-blocking, `response.ignore: true`) posts the new identity to `/internal/kratos/after-registration`; the profile row is created (race-safe `INSERT … ON CONFLICT DO NOTHING`).
2. *Every request:* the gateway's `forward-auth` calls `/internal/auth/verify`, which asks Kratos `whoami` and answers `X-User-Id`. APISIX sets that header on the forwarded request, **overwriting** any value a client sent (tested).
3. *Trust:* the gateway also sends `X-Gateway-Secret`; `user-service` rejects `/api` and `/internal/auth` calls without it, so another container cannot call it directly and spoof a user.
4. *Safety net:* if the webhook is ever lost, `GET /me` creates the profile from Kratos' admin API.
5. *Logout:* Kratos has no native-app logout hook, hence `POST /api/v1/me/logout`.

**Avatars:** the server decodes the upload, centre-crops to a square, resizes to ≤512 px, flattens transparency, and **re-encodes as JPEG**; this drops EXIF GPS/camera data and neutralises odd files (dimension and size limits guard against decompression bombs). Stored in RustFS at `avatars/{user id}/{image id}.jpg`; replacing deletes the previous file. Clients fetch via signed, expiring URLs (the bucket is not public). Signed URLs are built against `STORAGE_PUBLIC_ENDPOINT` (the address phones can reach; the host is part of the signature).

**FCM token rules** (modelled on ride_flow): a token is unique and moves to the current user if re-registered (one phone, two accounts over time); with an `installationId`, a rotated token **replaces** the old one; logout removes it. Sending push is not built yet (needs the Firebase key).

## 7c. Mini-app registry and partner notifications

* **registry-service** (port 8087): mini apps, capabilities, per-channel limits, allowed server addresses, partner public keys,
  staff roles (VIEWER/ADMIN/SUPER_ADMIN), reviews/reports, audit. Staff reach it at `/admin/api/v1` after email code + authenticator
  app (aal2). First super admin comes from `BOOTSTRAP_SUPER_ADMIN_EMAIL`. Users read the catalog at `/api/v1/apps`.
* **notification-service partner platform**: partners call `/v1/*` with RSA-signed requests (spec: `docs/PARTNER_API.md`);
  templates are admin-approved (`/admin/api/v1/notification/templates`); delivery runs in background workers (SMS, email,
  in-app + push) with retry/backoff; signed callbacks; quotas per app/channel; users read their inbox at `/api/v1/me/notifications`.
* Pseudonymous ids: user-service gives each user a different id per app (HMAC, `ALIAS_HMAC_SECRET`); partners never see real ids.
* Push: `FCM_PROVIDER=fake` until a Firebase service account for Adera exists (`FIREBASE_SERVICE_ACCOUNT_B64` in Infisical `/notification`, then `FCM_PROVIDER=firebase`).
* Our response/callback signing key: `PARTNER_SIGNING_KEY_ID` / `PARTNER_SIGNING_PRIVATE_KEY_B64` in Infisical `/notification`; without it an ephemeral key is used (dev only, refused with real providers).
* `PARTNER_ALLOW_PRIVATE_CALLBACKS=true` is for the local e2e test only; the service refuses to start with it while a real provider is on.
* Tests: `tests/e2e/partner.py` (through the gateway), `tests/e2e/journey.py` (29), `tests/e2e/staff.py`; scripts that need staff (`partner.py`, `admin_users.py`, `seed_demo.py`) never use the bootstrap account (a person's authenticator app is enrolled on it): `tests/e2e/tempstaff.py` creates a temporary staff account (`tempstaff-xxxx@adera.local`, role ADMIN) in the dev stack and removes it afterwards (identity, staff row, secret file, send_log rows); `ADERA_STAFF_EMAIL` uses a real account instead (code from `ADERA_TOTP` or a prompt); it refuses to run unless the dev endpoint answers; unit/integration: notification-service 50, registry-service 56, user-service 65; backoffice (`backoffice/`, React dashboard at /backoffice/): 65 unit tests, a live smoke test and a Playwright walk-through, see `backoffice/README.md` and docs section 11; `tests/e2e/admin_users.py` (13 checks).
* Not built yet: frontend bridge/user consent, payments, real Firebase. (The React dashboard is built: `backoffice/`, docs section 11 and the operator's manual, section 12.)

## 8. Login: how it works

Identity schema (`infra/kratos/identity.schema.json`): traits `email` and `phone_number`, each a one-time-code login identifier;
at least one is required (`anyOf`); no other traits (`additionalProperties: false`).
Sessions last 30 days; codes expire after 5 minutes; no passwords.
Profile data (name, gender, location, avatar, free-form JSON, devices) lives in `user-service`, keyed by the Kratos identity id (see §7b).

Native-app flow through the gateway (base `http://127.0.0.1:9080/kratos`):

| Step | Request |
|---|---|
| Sign up | `GET /self-service/registration/api`, then `POST /self-service/registration?flow=ID` with `{method:"code","traits.phone_number":"+251…"}` |
| Verify | same `POST` plus `"code":"123456"` → returns `session_token` |
| Log in | `GET /self-service/login/api`, then `POST /self-service/login?flow=ID` with `{method:"code",identifier:"+251…"}`, then again with `code` |
| Who am I | `GET /sessions/whoami` with `Authorization: Bearer <session_token>` |
| Log out | `DELETE /self-service/logout/api` with `{session_token}` |
| Other sessions | `GET /sessions`, `DELETE /sessions` (all others), `DELETE /sessions/{id}` |

Phone numbers must be valid E.164 (`+1 555…` is rejected). Spec: `docs/kratos-openapi-v26.2.0.json`.

## 9. Gateway (APISIX)

- Plugins enabled (list in `config.yaml` **replaces** the defaults; add a plugin there before using it): `request-id`, `cors`, `prometheus`, `opentelemetry`, `limit-count`, `forward-auth`, `proxy-rewrite`, `mocking`.
- Routes (`apisix.yaml`): `/kratos/admin/*` → 403; `POST /kratos/self-service/*` → limited to 10/min per IP (OTP send/verify); other `/kratos/*` → 120/min; `/api/v1/me/avatar` → session required, 20/min; other `/api/v1/me/**` → session required, 300/min (the old temporary `/api/ping` is gone).
- `forward-auth` on the `/api/v1/me` routes calls `user-service` (`/internal/auth/verify`); on a Kratos outage it returns 503 (not 401). APISIX sends `X-Gateway-Secret` (from `USER_GATEWAY_SECRET`) both to the verify call and to the upstream.
- APISIX's YAML parser: avoid YAML anchors/aliases in `apisix.yaml`; `${{ENV_VAR}}` substitution works but the variable must exist in the container (recreate it with `docker compose up -d apisix` after adding one; `restart` is not enough).
- Rate-limit counters are shared through Redis (`429` when exceeded).
- Kratos `serve.public.base_url` is the gateway (`http://127.0.0.1:9080/kratos/`) so flow URLs lead back through it.
- OpenTelemetry exporter settings live in `plugin_metadata` in `apisix.yaml` (not `config.yaml`).

## 10. Observability

Grafana → Dashboards → Adera → **Adera overview** (service up/down, gateway request rate by status, p95 latency, 429 count, RabbitMQ queue depth, recent errors).
Explore → **Loki** for logs (`{service="kratos"}`); Explore → **Tempo** for traces
(`{ resource.service.name = "apisix" } && { resource.service.name = "kratos" }` finds gateway→Kratos requests).
APISIX, Kratos, `notification-service` and `user-service` send traces (the Java services via OTLP to `otel-collector:4318`) and log to stdout; Prometheus scrapes all of them (12 targets, all up on 2026-10-25: `otel-collector` has two).

## 11. What has been verified

Phone sign-up, email sign-up, returning login (phone and email), `whoami`, logout (old token then gets 401), invalid-number rejection,
gateway proxying, admin path blocked, protected route 401/200, 429 rate limiting, request-id and CORS headers,
Prometheus (all targets up, including `notification-service`), Loki (logs from every container), Tempo (traces from apisix, kratos and
notification-service), Grafana provisioning.

Since the notification service went in, all of the above login flows were re-run **through** it (gateway → Kratos → notification-service →
console SMS / Mailpit email), and the email really arrived in Mailpit. `notification-service` had 28 automated tests at that point
(22 unit, 6 integration against a real Postgres via Testcontainers; it has 50 now, with the partner platform): rate limiting, allowlist, masking, send-log contents (no code, no full
number), provider request formats (Twilio/Resend, mocked), failure → 502, and the Infisical loader.

**user-service:** 47 automated tests at first (65 now; unit + 24 API integration tests against a real Postgres with `jsonb`, including a concurrent first-request race, plus gateway-secret tests).
End to end through the gateway, `tests/e2e/journey.py` runs **29 checks, all passing**: sign-up, Kratos webhook creating the profile before any API call, profile PATCH, `extra` merge persisted as jsonb,
location, avatar upload → RustFS → signed URL serves a 512×512 JPEG (unsigned URL gets 403), replaced avatars leave no orphan objects, non-image/oversize rejected (7 MB → 413; ~2.4 MB passes the gateway),
FCM token registration and rotation, forged `X-User-Id` overridden, bypassing the gateway rejected, logout (token removed, session revoked, other users unaffected), clean-up leaves no rows or objects.
Zero errors in any service log; a single trace spans gateway → Kratos for sign-up.

## 12. Known limitations and open items

**Security / behaviour**
- **Account enumeration:** Kratos answers "account does not exist" for unknown identifiers at login but "code sent" for known ones. Plan: a single "continue" endpoint in our backend that always answers "code sent".
- **Phone and email logins are separate identities.** Linking a second identifier (and merging two `app_user` rows) is a feature we must build.
- `user-service`'s `/internal/kratos/after-registration` has no authentication (Kratos' hook config is static, so a secret would have to sit in `kratos.yml`); it is not routed by the gateway and only reachable inside the compose network (and localhost:8086 in dev). Worst case: someone inside the network creates an empty profile row for an id.
- **Account deletion** (store requirement) is not implemented: it must delete the Kratos identity, soft-delete/anonymise the profile, remove avatar files and device tokens.
- **Avatar formats:** JPEG, PNG, GIF, BMP are accepted. iPhone HEIC and WebP are not decoded by the JDK; the app should convert to JPEG before upload (or we add a decoder).
- `user-service` currently uses RustFS's **admin** keys. Create a dedicated access key restricted to the `adera-media` bucket before production.
- No malware/NSFW scanning of avatars yet (re-encoding removes active content but not inappropriate images).
- Per-IP limits can block real users behind shared mobile-carrier IPs, and locally all clients share one Docker IP. In production configure the real client IP and rely on per-recipient limits in the notification service.

**Real sending is built but untested**
- The Twilio and Resend providers are implemented and unit-tested against mocked HTTP, and the real-mode startup was verified, but **no real SMS or email has been sent yet**. The first real send should use `NOTIFY_LIVE_ALLOWLIST` with your own number or address.
- Resend currently uses the GoBellMe account and sender (`bookings@gobellme.com`), so Adera emails would show that sender until a domain is verified.
- `notification-service`'s internal endpoints (`/internal/kratos/*`) have no authentication; they rely on never being exposed (compose network only; port 8085 is published to localhost for dev). Add a shared-secret header next.
- Kratos courier retries on non-2xx: delivery failures return 502 so messages are retried; rate-limited/skipped messages return 200 on purpose.

**Deploying to a server: checklist (deferred; do before going live)**
Copying the folder and running `docker compose up -d` starts the containers but is not production-ready:
1. **Infisical starts empty** on a new server (its data is in the Postgres volume). Restore the `infisical` DB (works with the same `.env` keys) or recreate project + secrets + token by hand; then update `INFISICAL_TOKEN`/`INFISICAL_PROJECT_ID`.
2. **Public HTTPS in front of the gateway only** (e.g. Caddy → `api.<domain>` → APISIX :9080). Everything else stays on `127.0.0.1` (use SSH tunnels).
3. **Replace hardcoded localhost URLs:** Kratos `serve.public.base_url` (otherwise phones get `127.0.0.1` flow URLs), Infisical `SITE_URL`, APISIX CORS origin.
4. **Real client IP in APISIX** behind the proxy, or the per-IP login limit (10/min) applies to all users combined.
5. **Set the environment and delivery mode:** `ADERA_ENV=prod` (section 7aa), then `SMS_PROVIDER=twilio`, `EMAIL_PROVIDER=resend`, `NOTIFY_DEV_ENDPOINTS=false`, empty `NOTIFY_LIVE_ALLOWLIST`. Mailpit not needed.
6. **Backups** of Postgres and an off-machine copy of `INFISICAL_ENCRYPTION_KEY` / `INFISICAL_AUTH_SECRET`.
7. **Size:** ~2.3 GB RAM at rest, ~9 GB images + volumes → at least 8 GB RAM / 40 GB disk recommended.
8. Put the project in a private git repo (secrets are gitignored) and copy `.env` separately and privately.
9. Revisit: trace sampling (`always_on`), Grafana admin password, the Alloy Docker-socket mount.
10. **RustFS must be reachable by phones over HTTPS** for avatars: set `STORAGE_PUBLIC_ENDPOINT` to its public address (e.g. `https://files.<domain>`, via the reverse proxy; only the S3 API, not the console on 9001). Signed URLs are tied to that host. Alternatively proxy image reads through the gateway.
11. Create a bucket-scoped RustFS access key instead of the admin keys; keep the RustFS console private.
12. Open issue: Twilio → Ethiopia (`+251`) SMS failed with error 21612 from the US sender (see §12); a local route or another channel is needed.

**Production hardening (not done)**
TLS everywhere; do not publish Kratos admin; trace sampling instead of `always_on`; review the Alloy Docker-socket mount;
Postgres/Redis exporters; narrow RabbitMQ permissions; Universal Auth for Infisical; separate Postgres/Redis for Infisical; rotate any key that was pasted into chat
(the ride_flow credentials file holds a live Stripe key and a Maps key, neither used by Adera).

## 13. Next steps

Decided: Spring Boot 4.1.x (Boot 3.5 is past open-source support), Java 21, Maven, one independent project per microservice under `services/`.

Done so far: all tools; `notification-service`; `user-service` (profile, location, `extra` JSON, avatar in RustFS, devices/FCM tokens, Kratos link); first real email delivered (SMS to Ethiopia unresolved).

1. **Push (FCM):** put the new Firebase project's service account into Infisical (`FIREBASE_SERVICE_ACCOUNT_B64`); add an FCM sender to `notification-service` that looks up a user's active devices in `user-service` (via RabbitMQ events or an internal API) and deactivates dead tokens.
2. **Events:** `user-service` publishes `user.created` / `device.registered` / `device.removed` to RabbitMQ (broker and `user_svc` login already exist).
3. **The "continue with phone or email" endpoint** (anti-enumeration: always answer "code sent") and **account linking**.
4. **Account deletion** (Kratos identity + profile + avatar + devices).
5. Ethiopian SMS route; shared secret on `notification-service`'s internal endpoints; bucket-scoped RustFS key.
6. Mini-app platform pieces from the Adera design docs (registry, capability gate, pseudonymous ids, activity cards) build on `user-service`.

## 14. Lessons learned (things that cost time)

- Config files bind-mounted into containers must be world-readable (644/755); containers run as other users.
- Kratos 26: use `kratos migrate sql up -e --yes`; add `hook: session` under `registration.after.code` to get a `session_token` after sign-up.
- Redis ACL files cannot contain comment lines.
- APISIX: unknown plugin in a global rule drops the **whole** rule (request-id, CORS, metrics) silently; OpenTelemetry needs `plugin_metadata`.
- Tempo single node: set `ingester.lifecycler.min_ready_duration: 0s` or `/ready` stays 503.
- Loki: raise `ingestion_rate_mb` or the first-start log backlog is rate-limited.
- Infisical: pin the image; `latest-postgres` was ten months stale and incompatible with the current CLI.
- Java services calling Infisical over plain HTTP: pin `HttpClient` to HTTP/1.1 (the default h2c upgrade gets a 400).
- Spring `EnvironmentPostProcessor`s run before logging exists: use `DeferredLogFactory` or their messages are lost.
- Spring mail health indicator marks a service DOWN when SMTP is unreachable (always, in live mode): disable it.
- Docker builds of Spring services: use `maven:3.9-eclipse-temurin-21` to build and `eclipse-temurin:21-jre` to run, non-root.
- This machine's default JDK is 26; build the services with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`.
- Spring Boot 4: `MockMultipartHttpServletRequestBuilder` no longer extends `MockHttpServletRequestBuilder` (use `AbstractMockHttpServletRequestBuilder<T>`); `@MockitoBean` is in `org.springframework.test.context.bean.override.mockito`; Jackson is `tools.jackson.*` (v3).
- AWS SDK v2 against an S3-compatible store: use path-style, `requestChecksumCalculation(WHEN_REQUIRED)`, and presign with the **public** endpoint (the host is part of the signature).
- Kratos `web_hook` with `response.ignore: true` is non-blocking: a down service can never block sign-ups. Put the hook before `session` in `registration.after.code.hooks`.
- `docker compose restart` does not re-read compose `environment`; use `docker compose up -d <service>` after adding variables.
- Python `urllib` raises `IncompleteRead` when a server (here the 413 for an oversized upload) closes the connection right after the status line; handle `e.partial`.

## Backoffice (staff dashboard)

`backoffice/` is the React staff dashboard (Refine + Ant Design), served by the gateway at http://127.0.0.1:9080/backoffice/ (`docker compose up -d --build backoffice apisix`). Sign in with a staff email, the emailed code (Mailpit in dev) and an authenticator app. See `backoffice/README.md` and `docs/src/11-backoffice.html`.
