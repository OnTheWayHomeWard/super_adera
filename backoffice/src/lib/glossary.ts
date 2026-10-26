/** Error codes the API can answer with, and what to do about them (from docs/src/10-reference.html). */
export interface ErrorCodeRow {
  code: string;
  http: string;
  meaning: string;
}

export const ERROR_GLOSSARY: ErrorCodeRow[] = [
  { code: "unauthorized", http: "401", meaning: "No or invalid session. The dashboard sends you back to sign in." },
  { code: "mfa_required", http: "403", meaning: "Signed in with the emailed code only; the authenticator-app step is missing." },
  { code: "not_staff", http: "403", meaning: "A valid account that is not an active staff member (customers get this)." },
  { code: "insufficient_role", http: "403", meaning: "Your role is too low for this action." },
  { code: "only_super_admin_can_revive_killed_app", http: "403", meaning: "Only a super admin can move a KILLED app to another status." },
  { code: "validation_failed", http: "400", meaning: "Some fields are not acceptable; the answer lists a message per field." },
  { code: "invalid_request", http: "400", meaning: "Unreadable body or a wrong type for a value (for example an id that is not a UUID)." },
  { code: "invalid_json", http: "400", meaning: "The body is not valid JSON (user service)." },
  { code: "not_found", http: "404", meaning: "Unknown id, or not visible to you." },
  { code: "slug_taken", http: "409", meaning: "An app with this slug already exists." },
  { code: "not_ready_for_live", http: "409", meaning: "Going LIVE needs a logo and a support email; the answer says which is missing." },
  { code: "too_many_active_keys", http: "409", meaning: "At most 5 active keys per environment." },
  { code: "cannot_demote_self", http: "409", meaning: "A super admin cannot lower or disable their own account." },
  { code: "last_super_admin", http: "409", meaning: "The last active super admin cannot be removed." },
  { code: "user_deleted", http: "409", meaning: "A deleted user cannot be changed." },
  { code: "template_code_taken", http: "409", meaning: "A template with this code already exists for the app." },
  { code: "conflict", http: "409", meaning: "A uniqueness conflict not covered by a specific code." },
  { code: "file_too_large", http: "413", meaning: "A logo over 2 MiB (or an avatar over 5 MiB)." },
  { code: "rate_limited (429)", http: "429", meaning: "The gateway limit for your address was reached (login submits: 10 per minute; admin API: 300 per minute)." },
  { code: "identity_service_unavailable", http: "503", meaning: "Kratos could not be reached." },
  { code: "audit_unavailable", http: "503", meaning: "The audit log could not be written, so the change was not made." },
  { code: "service_unavailable", http: "503", meaning: "A backend service could not be reached." },
];

/** Partner-facing codes you will see in the notification log and in partner support questions. */
export const PARTNER_CODES: ErrorCodeRow[] = [
  { code: "ip_not_allowed", http: "403", meaning: "The partner's server address is not in the app's allowed list for this deployment's environment." },
  { code: "wrong_environment", http: "403", meaning: "The partner key belongs to the other environment than this deployment's (a DEV key on a PROD deployment, or the reverse). Each deployment accepts only its own environment's keys." },
  { code: "synthetic_user_not_allowed", http: "422", meaning: "A test user (u_p_test_*) was used on a PROD deployment. Test users exist in DEV only." },
  { code: "capability_not_granted", http: "403", meaning: "The template needs a notify.* capability the app does not have." },
  { code: "app_draft / app_paused / app_killed", http: "403", meaning: "The app's status does not allow calls here (DEV works unless the app is KILLED; PROD needs status LIVE)." },
  { code: "user_unreachable", http: "422", meaning: "Unknown alias, deleted or suspended user, or no usable channel." },
  { code: "template_inactive / template_expired / template_not_yet_valid", http: "422", meaning: "The template is switched off or outside its validity window." },
  { code: "param_invalid", http: "422", meaning: "A template value is missing, too long, undeclared, or contains a link where links are not allowed." },
  { code: "quota_exceeded", http: "429", meaning: "A per-app limit was reached (max_per_user_per_day, max_per_day, max_distinct_users_per_day, max_total)." },
  { code: "external_id_reused", http: "409", meaning: "Same externalId with a different request." },
];
