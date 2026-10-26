package app.adera.notification.dispatch;

/**
 * Outcome returned to Kratos. Anything other than a delivery exception is a 200 on purpose: Kratos retries
 * non-2xx responses, and retrying a rate-limited or allowlist-skipped message would defeat the limit.
 */
public record DispatchResult(SendLog.Status status, String provider) {
}
