# registry-service

Mini-app registry for the Adera platform (Spring Boot, own Postgres database `registry`).

* Admin API `/admin/api/v1` (staff only, behind the gateway: email code + authenticator app): apps, status, logo (RustFS),
  capabilities, payment settings, per-channel notification limits, allowed server addresses per environment, partner public keys,
  reviews/reports moderation, audit log, staff management (SUPER_ADMIN).
* User API `/api/v1/apps`: catalog, reviews, my review, report.
* Internal API `/internal/**` (needs `X-Internal-Secret`): partner key lookup and app context for notification-service.
* Environment: `ADERA_ENV=dev|prod` (default dev) says which environment this deployment is. Allowed addresses and keys are stored for DEV
  and PROD, only the matching one is active; `GET /admin/api/v1/system` returns `{"environment":"DEV"}`. Internal context answers always for
  this environment (asking for the other: 403 `wrong_environment`); DEV is usable unless the app is KILLED, PROD only while it is LIVE.
  Migration `V2__environments_dev_prod.sql` renames TEST/LIVE rows to DEV/PROD.
* Config: see `application.yml` and `docs/SETUP.md` section 7c. Tests: `mvn test` (needs Docker for Testcontainers).
