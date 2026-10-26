# Adera partner API (mini-app backends)

How a mini app's server sends notifications to Adera users. Staff register the app, its allowed server addresses, a public
key and its message templates in the dashboard API (`/admin/api/v1`); the partner then calls `/v1/*` with signed requests.

## 1. Keys and signing (scheme `ADERA-SIG-1`, RS256)

The partner makes an RSA key pair (2048+ bits) and gives staff only the PUBLIC key (PEM). Private keys are rejected.
Environments: every deployment is exactly one environment, DEV or PROD (setting `ADERA_ENV`). Use the DEV key against the dev deployment and
the PROD key against the prod deployment. A key of the other environment is refused with `403 wrong_environment`. Besides that, the app status
decides: DEV works for any app that is not KILLED, PROD only while the app status is LIVE.

Every request carries:

| Header | Value |
|---|---|
| `X-Adera-Key-Id` | key id staff gave you |
| `X-Adera-Timestamp` | unix seconds, within 5 minutes of our clock |
| `X-Adera-Signature` | base64 RS256 (SHA256withRSA) signature of the canonical string |

Canonical string (lines joined with `\n`, no trailing newline):

```
ADERA-SIG-1
{timestamp}
{METHOD}
{path[?query]}            exactly as sent
{base64(sha256(body))}    empty body = hash of zero bytes
```

Responses and callbacks are signed with OUR key (public key at `/.well-known/jwks.json`):

* response: `ADERA-SIG-1-RESPONSE\n{ts}\n{status}\n{b64 sha256(body)}\n{b64 sha256(your request signature)}`
  in headers `X-Adera-Key-Id`, `X-Adera-Timestamp`, `X-Adera-Signature`;
* callback: same canonical form as a request (`POST`, path of your callback URL), plus `X-Adera-Event` and `Idempotency-Key`.

Requests must come from a server address staff registered for this deployment's environment (otherwise `403 ip_not_allowed`).

## 2. Endpoints

* `GET /v1/templates`, `GET /v1/templates/{id}`: the templates staff approved for your app (partners cannot create them).
* `POST /v1/notifications`
  ```json
  {"externalId":"job-1","userId":"u_p_...","templateId":"<uuid>","params":{"name":"Abebe"},
   "callbackUrl":"https://you.example/hook","ttlSeconds":86400,"data":{"jobId":"1"}}
  ```
  `userId` is the user's pseudonymous id for YOUR app. You never choose a channel: a `DIRECT` template goes by SMS (if the
  user has a phone and your app has `notify.sms`), otherwise email (`notify.email`); an `IN_APP` template goes to the
  user's inbox plus a push. In the push `data` every value is a string (nested objects/arrays as JSON strings; `null` left
  out) and Adera adds `notificationId` and `appId`: those two names, and the Firebase-reserved `from`, `message_type`,
  `google*`, `gcm*`, are refused in your `data` (`400 validation_failed`, `fields.data`).
* `GET /v1/notifications/{externalId}`: current status.

`externalId` makes the call idempotent: the same request again returns `200` with `"replayed": true`; the same id with a
different request is `409 external_id_reused`.

## 3. Statuses and answers

`QUEUED, PROCESSING, RETRYING, SENT, FAILED, UNREACHABLE, SUPPRESSED, EXPIRED`. Permanent errors (bad number) are never
retried; temporary ones retry with backoff (30s doubling, max 1h, 8 attempts) until `ttlSeconds` passes.

| Code | Meaning |
|---|---|
| 202 | accepted, `QUEUED` |
| 200 | replay, or `SUPPRESSED` (the user muted your app; not revealed as an error) |
| 400 `validation_failed` | malformed request (`fields` says which) |
| 400 `invalid_request` | the body is not a JSON object, or a value has the wrong type |
| 401 | `missing_signature`, `invalid_timestamp` (not a number), `stale_timestamp` (more than 5 minutes off), `invalid_signature`, `unknown_key`, `key_revoked_or_expired`, `unknown_app` (the key's app no longer exists) |
| 403 | `wrong_environment` (the key belongs to the other environment than this deployment), `ip_not_allowed`, `capability_not_granted` (with `capability`), `app_draft` / `app_paused` / `app_killed` (the app's status does not allow calls here: DEV works unless KILLED, PROD needs LIVE; `app_not_active` as a fallback) |
| 404 | `template_not_found` (the template is not yours or does not exist); `not_found` (no notification with that `externalId`) |
| 409 | `external_id_reused` |
| 413 | `request_too_large` (body over 64 KiB) |
| 422 | `synthetic_user_not_allowed` (a `u_p_test_*` user on a PROD deployment), `user_unreachable` (deleted, suspended and unknown users look the same), `param_invalid` (with `fields`), `template_inactive`, `template_not_yet_valid`, `template_expired` |
| 429 | `quota_exceeded` with `limit`: `max_per_user_per_day`, `max_per_day`, `max_distinct_users_per_day`, `max_total` |
| 503 | `service_unavailable`, retry later |

Staff-side endpoints (`/admin/api/v1`, not for partners) have their own codes, for example `403 insufficient_role` and `409 template_code_taken`; see `docs/src/10-reference.html` (error-code index).

Limits are set per app and channel by staff (`null` = unlimited, `0` = none). A refused request uses no quota.

## 4. Callbacks

If you send `callbackUrl`, we POST `{"event":"notification.status","notificationId":..,"externalId":..,"status":..,"channel":..,"reason":..,"at":..}`
when the notification reaches a final status. Answer 2xx; otherwise we retry (1m doubling, up to 6h, 8 attempts). The URL must
be public https on port 443/8443; private, loopback, link-local and cloud-metadata addresses are refused and redirects are not followed.
Verify the signature with the key from `/.well-known/jwks.json` and de-duplicate on `Idempotency-Key`.

## 5. Testing in DEV (synthetic users)

There is no separate simulated sandbox: the dev deployment's own providers are the sandbox (console SMS, Mailpit email, fake push), so a
real user in DEV is delivered through them like any message, and nothing reaches a real phone or inbox. The only simulation left is for
synthetic users: ids named `u_p_test_<anything>` are not real people (no contact details or push tokens), so in DEV their delivery is
simulated (nothing is sent, the attempt is recorded with provider `sandbox`, status `SENT`). `u_p_test_unreachable` is refused at once with
`422 user_unreachable`; `u_p_test_muted` answers `200 SUPPRESSED` (`recipient_opted_out`). In PROD every `u_p_test_*` id is refused with
`422 synthetic_user_not_allowed`.
