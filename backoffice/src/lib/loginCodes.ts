/**
 * "May I ask for a sign-in code for this address?" asked BEFORE Kratos is told to send one (POST /public/v1/login-codes/check).
 * The server's real per-address limit drops codes silently, which used to leave this screen saying "we sent a code" when
 * nothing was delivered. The check counts requests for any address the same way, so it never reveals whether an account exists.
 */
export interface CodeAllowance {
  allowed: boolean;
  limitPerHour: number;
  limitPerDay: number;
  remainingThisHour: number;
  remainingToday: number;
  retryAfterSeconds: number;
}

type Fetcher = (input: string, init?: RequestInit) => Promise<Response>;

/** Returns null when the check itself could not be answered (then sign-in simply goes on: the server still enforces the limit). */
export async function checkCodeAllowance(identifier: string, fetcher: Fetcher = (...a) => fetch(...a)): Promise<CodeAllowance | null> {
  try {
    const res = await fetcher("/public/v1/login-codes/check", {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json" },
      body: JSON.stringify({ identifier }),
    });
    if (res.status !== 200 && res.status !== 429) return null;
    const body = (await res.json()) as Partial<CodeAllowance>;
    if (typeof body.limitPerHour !== "number") return null;
    return {
      allowed: res.status === 200,
      limitPerHour: body.limitPerHour,
      limitPerDay: body.limitPerDay ?? 0,
      remainingThisHour: body.remainingThisHour ?? 0,
      remainingToday: body.remainingToday ?? 0,
      retryAfterSeconds: body.retryAfterSeconds ?? 0,
    };
  } catch {
    return null;
  }
}

/** "about 12 minutes", "1 hour 5 minutes", "45 seconds" */
export function waitText(seconds: number): string {
  if (seconds < 90) return `${Math.max(1, Math.round(seconds))} seconds`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} minutes`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m === 0 ? `${h} hour${h === 1 ? "" : "s"}` : `${h} hour${h === 1 ? "" : "s"} ${m} minutes`;
}

/** The message shown instead of sending the request when the address has used up its codes. */
export function refusedText(a: CodeAllowance): string {
  return `Too many sign-in codes were requested for this address (at most ${a.limitPerHour} per hour and ${a.limitPerDay} per day). No new code is sent now: try again in ${waitText(a.retryAfterSeconds)}.`;
}

/** Said after a code was requested: honest about delivery and about the limit. */
export function sentText(email: string, a: CodeAllowance | null): string {
  const base = `If ${email} is a staff address, we emailed a 6-digit code to it. It is valid for 5 minutes.`;
  const limit = a ? ` Each address can receive at most ${a.limitPerHour} codes per hour (${a.remainingThisHour} left this hour).` : "";
  return `${base} If nothing arrives within a minute, wait a little and request a new code.${limit}`;
}
