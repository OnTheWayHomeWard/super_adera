/** The address where a partner wants consent callbacks (user.consent_changed, user.disconnected) for one environment. Empty = none. */
export function callbackUrlProblem(value: string, env: "DEV" | "PROD"): string | null {
  const url = value.trim();
  if (!url) return null;
  if (url.length > 300) return "At most 300 characters.";
  const https = url.startsWith("https://");
  const http = url.startsWith("http://") && env === "DEV";
  if (!https && !http) return env === "DEV" ? "Must start with https:// (http:// is accepted for DEV)." : "Must start with https://.";
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return "Not a valid URL: it needs a host.";
  }
  if (!parsed.hostname) return "The URL needs a host.";
  if (parsed.username || parsed.password) return "No user name or password in the URL.";
  return null;
}

/** The request body that sets (or removes, with null) only the callback URL, leaving the allowed addresses as they are. */
export function callbackUrlBody(value: string): { callbackUrl: string | null } {
  const url = value.trim();
  return { callbackUrl: url ? url : null };
}
