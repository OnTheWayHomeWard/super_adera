import type { NotificationDetail, NotificationRow } from "./types";

/** What each notification status means (see docs/src/10-reference.html). */
export const NOTIFICATION_STATUS_HELP: Record<string, string> = {
  QUEUED: "Accepted and waiting for the delivery worker.",
  PROCESSING: "A worker is sending it right now.",
  RETRYING: "An attempt failed for a temporary reason; it will be tried again until it expires.",
  SENT: "Handed to the provider (SMS, email or push) successfully. We do not track the handset delivery receipt.",
  FAILED: "Permanent failure (for example a bad number) or too many attempts. It will not be retried.",
  UNREACHABLE: "There was no way to reach the user: account deleted or suspended, or no phone or email on file.",
  SUPPRESSED: "Deliberately not sent: the user muted this app, or the recipient is not on the live allowlist.",
  EXPIRED: "It kept failing until its deadline passed.",
};

export const NOTIFICATION_REASON_HELP: Record<string, string> = {
  recipient_opted_out: "The user muted this channel for this app (the three-dot menu in the app window).",
  user_unreachable: "The user is deleted or suspended, or unknown.",
  no_phone: "The user has no phone number on file, so an SMS cannot be sent.",
  no_email: "The user has no email address on file.",
  not_allowlisted: "This is a development or limited-live setup and the recipient is not on the allowlist, so nothing real was sent.",
};

/** A plain-language answer to "why did this not arrive?". */
export function explainNotification(n: Pick<NotificationRow, "status" | "reason" | "attempts" | "channel">, detail?: NotificationDetail): string[] {
  const lines: string[] = [NOTIFICATION_STATUS_HELP[n.status] ?? `Status ${n.status}.`];
  if (n.reason) {
    const base = n.reason.startsWith("max_attempts:") ? "max_attempts" : n.reason;
    const known = NOTIFICATION_REASON_HELP[base];
    if (known) lines.push(known);
    else if (base === "max_attempts") lines.push(`It was tried ${n.attempts} times and every attempt failed (last reason: ${n.reason.slice("max_attempts:".length)}).`);
    else lines.push(`Reason code: ${n.reason}.`);
  }
  if (detail && detail.attemptLog.length) {
    const last = detail.attemptLog[detail.attemptLog.length - 1];
    lines.push(`Last attempt: ${last.channel} through ${last.provider} -> ${last.outcome}${last.errorCode ? ` (${last.errorCode})` : ""}.`);
  } else if (detail && n.status !== "QUEUED" && n.status !== "PROCESSING") {
    lines.push("No delivery attempt was recorded: it was decided before any provider was called.");
  }
  if (detail && detail.callbacks.some((c) => c.status !== "DELIVERED" && c.status !== "SENT")) {
    lines.push("The callback to the partner is not confirmed yet (see the callbacks table).");
  }
  return lines;
}
