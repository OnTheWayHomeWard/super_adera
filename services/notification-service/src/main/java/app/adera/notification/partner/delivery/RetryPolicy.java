package app.adera.notification.partner.delivery;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** How long to wait before trying again, and when to give up. */
public final class RetryPolicy {

	public static final int MAX_DELIVERY_ATTEMPTS = 8;
	public static final int MAX_CALLBACK_ATTEMPTS = 8;

	private RetryPolicy() {
	}

	/** 30s, 1m, 2m, 4m ... capped at 1 hour, +-10% so a burst of failures does not retry in lockstep. */
	public static Duration deliveryDelay(int attemptsSoFar) {
		return withJitter(Duration.ofSeconds(30).multipliedBy(1L << Math.min(Math.max(attemptsSoFar - 1, 0), 10)), Duration.ofHours(1));
	}

	/** 1m, 2m, 4m ... capped at 6 hours: about a day of trying across 8 attempts. */
	public static Duration callbackDelay(int attemptsSoFar) {
		return withJitter(Duration.ofMinutes(1).multipliedBy(1L << Math.min(Math.max(attemptsSoFar - 1, 0), 10)), Duration.ofHours(6));
	}

	private static Duration withJitter(Duration base, Duration cap) {
		Duration capped = base.compareTo(cap) > 0 ? cap : base;
		double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
		return Duration.ofMillis((long) (capped.toMillis() * factor));
	}

	/**
	 * Permanent failures are never retried: the provider told us the request itself is wrong (invalid number, bad address,
	 * rejected sender). Temporary ones (timeouts, 5xx, rate limiting, our own dependency down) are retried.
	 */
	public static boolean isPermanent(String errorCode) {
		if (errorCode == null) {
			return false;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(?:twilio|resend)_http_(\\d{3})$").matcher(errorCode);
		if (m.matches()) {
			int status = Integer.parseInt(m.group(1));
			return status >= 400 && status < 500 && status != 408 && status != 429;
		}
		return false;
	}

}
