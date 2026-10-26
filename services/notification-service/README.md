# notification-service

Spring Boot 4.1 · Java 21 · Maven · Postgres (Flyway) · its own independent project (no parent build).

Delivers every message the platform sends to a person. Today that is the one-time codes Kratos produces for
login and sign-up; push (FCM) and event-driven messages come next.

```
Kratos ──HTTP──► POST /internal/kratos/sms    ┐
               └► POST /internal/kratos/email ┴─► NotificationDispatcher ─► provider ─► send_log
```

The dispatcher, for every message, in this order:
1. **Per-recipient rate limit**: default 5/hour and 10/day for SMS, 10/hour and 20/day for email (counted from `send_log`).
   Over the limit → status `suppressed`, **HTTP 200** (Kratos retries non-2xx, which would defeat the limit).
2. **Live allowlist** (`NOTIFY_LIVE_ALLOWLIST`): if set, *real* providers only deliver to those recipients (`skipped` otherwise).
3. **Provider** call. A delivery failure returns **502** so Kratos keeps the message and retries.
4. **Send log** row: channel, masked recipient, SHA-256 of the recipient, template type, provider, status, error code.
   Never the message body (it contains the code) and never the full phone/email.

## Modes: what actually gets sent

| Variable | Values | Meaning |
|---|---|---|
| `SMS_PROVIDER` | `console` (default) | Writes the SMS to the log. Nothing leaves the machine. |
| | `twilio` | **Real SMS, costs money.** Messaging Service SID preferred, sender number as fallback. |
| `EMAIL_PROVIDER` | `mailpit` (default) | SMTP to the local Mailpit inbox (<http://localhost:8025>). |
| | `resend` | **Real email** via Resend. Sender domain must be verified there. |
| `NOTIFY_DEV_ENDPOINTS` | `true` / `false` | Exposes `GET /dev/messages` (recent dev messages, so tests can read OTP codes). **The service refuses to start if this is true while any real provider is on.** |
| `NOTIFY_LIVE_ALLOWLIST` | comma-separated | Limits real sends to these recipients. |

These are set in the repo-root `.env` and passed by `docker-compose.yml`. The banner printed at startup states the active mode
(`NOTIFICATION MODE: sms=console (dev…), email=mailpit (dev…)`).

To send a real SMS to one number, for example:
```
SMS_PROVIDER=twilio
NOTIFY_DEV_ENDPOINTS=false
NOTIFY_LIVE_ALLOWLIST=+2519XXXXXXXX
```
then `docker compose up -d notification-service`.

## Secrets

Twilio and Resend keys are **not** in `.env`, the image or compose. At startup `InfisicalEnvironmentPostProcessor` loads
the `/notification` folder of the Infisical project into the environment (lowest priority, so a real env var always wins).
It needs `INFISICAL_DOMAIN`, `INFISICAL_PROJECT_ID`, `INFISICAL_TOKEN` (and optionally `INFISICAL_ENV`, `INFISICAL_PATH`).
If Infisical is unreachable the service still starts in dev mode; in real mode it fails fast naming the missing key.

Expected keys: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_MESSAGING_SERVICE_SID` (or `TWILIO_PHONE_NUMBER`),
`RESEND_API_KEY`, `RESEND_FROM_EMAIL`, `RESEND_FROM_NAME`.

## Endpoints

| | |
|---|---|
| `POST /internal/kratos/sms` | `{to, body, templateType}`. **Internal only: never route it through the gateway.** |
| `POST /internal/kratos/email` | `{recipient, subject, body, htmlBody, templateType}`. Internal only. |
| `GET/DELETE /dev/messages[?to=]` | Dev only (see above). |
| `GET /actuator/health`, `/actuator/prometheus` | Health (mail check disabled on purpose) and metrics. |

Known gap: the internal endpoints have no authentication; they rely on not being exposed (compose network only, plus a
localhost-published port for dev). A shared-secret header is the next hardening step.

## Build, test, run

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn test        # unit tests + integration tests (needs Docker: Testcontainers Postgres)
docker compose build notification-service             # from the repo root
docker compose up -d notification-service
```
The shell default JDK on this machine is 26; build with 21 (the target and the image).

## Layout

```
config/    NotificationProperties, AppConfig, InfisicalEnvironmentPostProcessor (+ META-INF/spring.factories)
sms/       SmsProvider, ConsoleSmsProvider, TwilioSmsProvider, GsmText
email/     EmailProvider, MailpitEmailProvider, ResendEmailProvider
dispatch/  NotificationDispatcher, SendLog(+Repository), DevMessageStore, Masking, DeliveryException
kratos/    KratosCourierController, ApiExceptionHandler
dev/       DevMessageController, ProviderSafetyCheck
db/migration/V1__send_log.sql
```

## Lessons learned while building it
- Java's `HttpClient` tries an h2c upgrade over plain HTTP; Infisical answers 400 to it. The loader pins HTTP/1.1.
- A custom `EnvironmentPostProcessor` runs before logging exists; use Spring's `DeferredLogFactory` or its messages vanish.
- Spring's mail health indicator would mark the service DOWN whenever SMTP is unreachable (always, in live mode). It is disabled.
- `ddl-auto: validate` caught a `CHAR(64)` vs `VARCHAR` mismatch between the migration and the entity.

## Partner platform

Environment: `ADERA_ENV=dev|prod` (default dev; anything else stops the service) is the one environment of this deployment. A partner key of the
other environment is refused with `403 wrong_environment` (after the signature verifies); notifications, limits and usage use the deployment's
environment (migration `V3__environments_dev_prod.sql` renamed TEST/LIVE rows to DEV/PROD). There is no simulated sandbox: DEV uses the dev providers
(console SMS, Mailpit, fake push). Only the synthetic users `u_p_test_*` are simulated in DEV (nothing sent, attempt provider `sandbox`); in PROD they
are refused (`422 synthetic_user_not_allowed`).

Besides OTP delivery for Kratos, this service runs the partner notification platform (signed `/v1/*` API, templates, workers, callbacks, inbox). See `docs/PARTNER_API.md` and `docs/SETUP.md` section 7c.
