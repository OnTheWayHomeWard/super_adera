/** Body of PATCH reviews/{id} and reports/{id}: the new status plus the optional written reason (max 500 characters, staff-only). */
export const MAX_REASON = 500;

export function moderationBody<S extends string>(status: S, reason: string): { status: S; reason?: string } {
  const text = reason.trim();
  return text ? { status, reason: text.slice(0, MAX_REASON) } : { status };
}
