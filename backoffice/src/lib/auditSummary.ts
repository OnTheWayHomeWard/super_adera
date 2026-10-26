/** One readable line for an audit entry's details column (the expanded row still shows the full JSON). */

const limit = (v: unknown): string => (v === null || v === undefined ? "unlimited" : String(v));

export function summariseAudit(action: string, meta: Record<string, unknown>): string {
  if (action === "limit.set") {
    // new entries have real fields; entries written before 2026-10-26 were converted by a migration
    return `${String(meta.channel ?? "?")}: per user per day ${limit(meta.maxPerUserPerDay)}, per day ${limit(meta.maxPerDay)}, distinct users per day ${limit(meta.maxDistinctUsersPerDay)}, total ${limit(meta.maxTotal)}`;
  }
  if (action === "payment.set") {
    const currencies = Array.isArray(meta.currencies) ? meta.currencies.join(", ") : "none";
    const range = `${meta.minAmountMinor ?? "no minimum"} to ${meta.maxAmountMinor ?? "no maximum"}`;
    return `currencies ${currencies || "none"}, hold ${meta.allowHold ? "allowed" : "off"}, amount ${range}`;
  }
  const text = Object.entries(meta)
    .map(([k, v]) => `${k}=${typeof v === "object" ? JSON.stringify(v) : String(v)}`)
    .join(" ");
  return text.length > 90 ? `${text.slice(0, 90)}...` : text;
}
