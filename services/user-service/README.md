# user-service

Spring Boot 4.1 · Java 21 · Maven · Postgres (Flyway) · RustFS (S3 API). An independent project (no parent build).

Owns the full user record. The Ory Kratos identity id **is** the user id: Kratos owns identity, login and sessions; this
service owns everything else about the person. Full description, data model and API tables: `docs/SETUP.md` §7b.

```
app ─► APISIX ─forward-auth─► /internal/auth/verify ─► Kratos whoami ─► X-User-Id
        └────────────────────► /api/v1/me/**  (X-User-Id + X-Gateway-Secret)
Kratos ─web_hook─► /internal/kratos/after-registration  (creates the profile)
profile ─► Postgres "users" (app_user, device)        avatar files ─► RustFS bucket adera-media
```

## What it does
- **Profile:** names, gender, date of birth, language (am/en/om), market (ET/US), timezone, terms accepted.
- **`extra` JSONB:** free-form data for things nobody planned. JSON Merge Patch semantics (`null` removes a key), 16 KB limit.
- **Location (optional):** country, region, city, lat/lng, set and withdrawn by the user.
- **Avatar:** multipart upload; the server crops to a square, resizes, **re-encodes to JPEG (drops EXIF/GPS)**, stores it in
  RustFS under `avatars/{userId}/{imageId}.jpg` and keeps only `avatar_image_id` in the database. Reads use signed, expiring URLs.
- **Devices / FCM tokens:** register after login and on rotation; token unique and re-assigned if another account signs in on the
  same phone; with an `installationId` a rotated token replaces the old one; `POST /api/v1/me/logout` removes the token and
  revokes the Kratos session in one call.

## Security model
- Only the gateway can call `/api/**` and `/internal/auth/verify`: it sends `X-Gateway-Secret` (`USER_GATEWAY_SECRET`);
  without it the service answers 401 even if `X-User-Id` is present. The gateway overwrites any client-supplied `X-User-Id`.
- `/internal/kratos/after-registration` is not routed by the gateway (compose network only).
- Uploads are decoded and re-encoded, with size, pixel-count and dimension limits.

## Configuration (environment)
`DB_URL`, `DB_USER`, `DB_PASSWORD` · `KRATOS_PUBLIC_URL`, `KRATOS_ADMIN_URL` · `STORAGE_ENDPOINT` (internal),
`STORAGE_PUBLIC_ENDPOINT` (what phones use in signed URLs), `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` ·
`USER_GATEWAY_SECRET` · `AVATAR_MAX_BYTES`, `AVATAR_SIZE_PX`, `EXTRA_MAX_BYTES` · `OTEL_TRACES_ENDPOINT`.
Tests use `adera.user.storage.type=memory` (an in-memory fake) instead of RustFS.

## Build, test, run
```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn test           # 47 tests; needs Docker (Testcontainers Postgres)
docker compose build user-service && docker compose up -d user-service     # from the repo root
python3 -I tests/e2e/journey.py                           # full journey through the gateway (dev mode)
```

## Layout
```
auth/      KratosClient, GatewayAuthFilter, VerifyController, AfterRegistrationController, LogoutController, ApiExceptionHandler
profile/   AppUser, UserRepository, ProfileService, ProfilePatch, JsonMergePatch, LocationRequest, ProfileResponse, ProfileController
avatar/    AvatarImageProcessor, AvatarStorage, S3AvatarStorage (RustFS), AvatarService
device/    Device, DeviceRepository, DeviceService, DeviceDtos, DeviceController
db/migration/V1__users_and_devices.sql
```
