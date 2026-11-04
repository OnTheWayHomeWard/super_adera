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
  if (action === "capability.set") {
    const say = (p: unknown): string => {
      const o = (p ?? {}) as Record<string, string>;
      return o.en ? `"${o.en}"` : "no purpose text";
    };
    // entries written before 2026-11-04 carry only capability and state
    if (!("purpose" in meta)) return `${String(meta.capability)}: ${String(meta.state)}`;
    const was = meta.previousState ? `was ${String(meta.previousState)}, ${say(meta.previousPurpose)}` : "new";
    const now = say(meta.purpose);
    return `${String(meta.capability)}: ${String(meta.state)}, ${now === "no purpose text" ? now : `purpose ${now}`} (${was})`;
  }
  const text = Object.entries(meta)
    .map(([k, v]) => `${k}=${typeof v === "object" ? JSON.stringify(v) : String(v)}`)
    .join(" ");
  return text.length > 90 ? `${text.slice(0, 90)}...` : text;
}
