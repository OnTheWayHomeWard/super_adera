/**
 * Staff roles as the server defines them (registry-service Role): a higher rank includes everything below it.
 * The server enforces all of this; the UI only mirrors it so people are not shown buttons that would be refused.
 */
export type Role = "VIEWER" | "ADMIN" | "SUPER_ADMIN";

const RANK: Record<Role, number> = { VIEWER: 1, ADMIN: 2, SUPER_ADMIN: 3 };

export function isRole(value: unknown): value is Role {
  return value === "VIEWER" || value === "ADMIN" || value === "SUPER_ADMIN";
}

export function atLeast(role: Role | null | undefined, needed: Role): boolean {
  return !!role && RANK[role] >= RANK[needed];
}

/** What the dashboard offers per role. Mirrors the role.require(...) calls in the services. */
export type Ability =
  | "write" // create/change apps, keys, limits, templates, moderation, user status (ADMIN)
  | "audit.read" // the audit log (ADMIN)
  | "user.pii" // full phone, email, date of birth, extra JSON (ADMIN; VIEWER sees masked values)
  | "staff.manage" // staff list and changes (SUPER_ADMIN)
  | "app.revive"; // move a KILLED app back (SUPER_ADMIN)

const NEEDED: Record<Ability, Role> = {
  write: "ADMIN",
  "audit.read": "ADMIN",
  "user.pii": "ADMIN",
  "staff.manage": "SUPER_ADMIN",
  "app.revive": "SUPER_ADMIN",
};

export function can(role: Role | null | undefined, ability: Ability): boolean {
  return atLeast(role, NEEDED[ability]);
}

export const ROLE_LABEL: Record<Role, string> = {
  VIEWER: "Viewer (read only)",
  ADMIN: "Admin",
  SUPER_ADMIN: "Super admin",
};
