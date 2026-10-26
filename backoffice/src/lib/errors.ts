/** One error type for everything the API can answer, built from the services' `{"error": code, "fields": {...}}` shape. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fields: Record<string, string>;
  readonly details: Record<string, unknown>;

  /** Refine reads this name when it reports a failed request. */
  get statusCode(): number {
    return this.status;
  }

  /** Refine shows these per-field messages next to form inputs. */
  get errors(): Record<string, string> {
    return this.fields;
  }

  constructor(status: number, code: string, fields: Record<string, string> = {}, details: Record<string, unknown> = {}) {
    super(messageFor(code, fields, details));
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fields = fields;
    this.details = details;
  }
}

/** Plain-language text per error code (the codes are listed in docs/src/10-reference.html). */
export const ERROR_TEXT: Record<string, string> = {
  unauthorized: "Your session has ended. Sign in again.",
  mfa_required: "The authenticator-app step is not done for this session.",
  not_staff: "This account is not an active staff member of the dashboard.",
  insufficient_role: "Your role is not allowed to do this.",
  only_super_admin_can_revive_killed_app: "Only a super admin can move a KILLED app back to another status.",
  validation_failed: "Some fields are not acceptable. Fix the marked fields and try again.",
  invalid_request: "The request could not be read (a wrong type or an unreadable value).",
  invalid_json: "The request body is not valid JSON.",
  not_found: "Not found. It may have been removed, or the id is wrong.",
  slug_taken: "An app with this slug already exists.",
  not_ready_for_live: "The app is not ready to go LIVE yet.",
  too_many_active_keys: "This environment (DEV or PROD) already has the maximum of 5 active keys. Revoke one first.",
  cannot_demote_self: "You cannot lower or disable your own account. Ask another super admin.",
  last_super_admin: "This is the last active super admin. Add or promote another one first.",
  file_too_large: "The file is too large.",
  conflict: "This conflicts with existing data (for example the value is already in use).",
  identity_service_unavailable: "The identity service (Kratos) could not be reached. Try again shortly.",
  service_unavailable: "A backend service could not be reached. Try again shortly.",
  audit_unavailable: "The audit log could not be written, so nothing was changed. Try again shortly.",
  user_deleted: "This user is marked deleted. A deleted user cannot be changed.",
  template_code_taken: "A template with this code already exists for this app.",
  rate_limited: "Too many requests from this address. Wait a minute and try again.",
  network_error: "The server could not be reached. Check your connection and the gateway.",
  server_error: "The server answered with an unexpected error.",
};

function messageFor(code: string, fields: Record<string, string>, details: Record<string, unknown>): string {
  if (code === "not_ready_for_live") {
    const missing = String(details.missing ?? fields.missing ?? "").split(",").filter(Boolean);
    if (missing.length) {
      return `Not ready for LIVE: missing ${missing.map(missingLabel).join(" and ")}.`;
    }
  }
  return ERROR_TEXT[code] ?? code;
}

export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message;
  }
  if (error instanceof Error) {
    return error.message;
  }
  if (error && typeof error === "object" && "message" in error) {
    return String((error as { message: unknown }).message);
  }
  return "Something went wrong.";
}

function missingLabel(key: string): string {
  return key === "logo" ? "a logo" : key === "supportEmail" ? "a support email" : key;
}

/**
 * Turns an HTTP answer into an ApiError. Handles our JSON shape, plain-HTML answers from the gateway (the 429 page,
 * 502/503/504 when a service is down) and empty bodies.
 */
export function toApiError(status: number, text: string): ApiError {
  let json: unknown = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = null;
  }
  if (status === 429) {
    return new ApiError(429, "rate_limited");
  }
  if (json && typeof json === "object" && !Array.isArray(json)) {
    const body = json as Record<string, unknown>;
    if (typeof body.error === "string") {
      const fields: Record<string, string> = {};
      const details: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(body)) {
        if (key === "error") continue;
        if (key === "fields" && value && typeof value === "object") {
          for (const [field, message] of Object.entries(value as Record<string, unknown>)) {
            fields[field] = String(message);
          }
        } else {
          details[key] = value;
        }
      }
      return new ApiError(status, body.error, fields, details);
    }
  }
  if (status === 401) return new ApiError(401, "unauthorized");
  if (status === 502 || status === 503 || status === 504) return new ApiError(status, "service_unavailable");
  return new ApiError(status, status >= 500 ? "server_error" : "invalid_request");
}

/**
 * Field errors for a form, from validation_failed. Keys can be dotted paths such as `content.en.body`; the form
 * shows each message next to its input, and anything it has no input for is returned in `rest`.
 */
export function splitFieldErrors(error: unknown, knownFields: readonly string[]): { byField: Record<string, string>; rest: string[] } {
  const byField: Record<string, string> = {};
  const rest: string[] = [];
  if (error instanceof ApiError && error.code === "validation_failed") {
    for (const [field, message] of Object.entries(error.fields)) {
      if (knownFields.includes(field)) {
        byField[field] = message;
      } else {
        rest.push(`${field}: ${message}`);
      }
    }
  }
  return { byField, rest };
}
