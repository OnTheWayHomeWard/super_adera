package app.adera.notification.partner.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * How long to wait before trying again, and when to give up.
 *
 * <p><b>Status callbacks to partners</b> (the schedule the docs quote): after the n-th failed attempt wait
 * 1m, 2m, 4m, 8m, 16m, 32m, 1h, 2h, then 4h for every later retry (each with up to 10% jitter downwards, so a burst of
 * failures does not retry in lockstep). Nominal retry times after the first attempt: +1m, +3m, +7m, +15m, +31m, +63m,
 * +2h03, +4h03, +8h03, +12h03, +16h03, +20h03, and a last one at exactly 24 hours (a retry never goes past that). The window
 * is therefore 24 hours from the moment the callback was queued, 14 attempts in practice and at most {@link #MAX_CALLBACK_ATTEMPTS};
 * after the last one fails the callback is FAILED. Never a tight loop: any failure waits at least {@link #MIN_RETRY_DELAY}.
 * A partner's {@code Retry-After} may lengthen a wait but never shorten it, and is capped at {@link #CALLBACK_MAX_DELAY}.
 *
 * <p><b>Deliveries (SMS, email, push)</b>: 30s, 1m, 2m, 4m ... capped at 1h with 10% jitter, at most
 * {@link #MAX_DELIVERY_ATTEMPTS} attempts (about an hour of trying) and never past the notification's own {@code ttlSeconds}.
 */
public final class RetryPolicy {

	public static final int MAX_DELIVERY_ATTEMPTS = 8;
	/** Hard stop (the 24 h window normally ends the schedule after 14 attempts). */
	public static final int MAX_CALLBACK_ATTEMPTS = 15;
	/** How long a callback keeps being retried, counted from when it was queued. */
	public static final Duration CALLBACK_WINDOW = Duration.ofHours(24);
	/** The longest wait between two callback attempts, also the ceiling for a partner's Retry-After. */
	public static final Duration CALLBACK_MAX_DELAY = Duration.ofHours(4);
	/** No failure is ever retried sooner than this, whatever happens (protects against tight loops). */
	public static final Duration MIN_RETRY_DELAY = Duration.ofSeconds(30);

	/** Nominal wait after the 1st, 2nd, ... failed callback attempt; the last value repeats. */
	private static final Duration[] CALLBACK_SCHEDULE = { Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4), Duration.ofMinutes(8),
			Duration.ofMinutes(16), Duration.ofMinutes(32), Duration.ofHours(1), Duration.ofHours(2), CALLBACK_MAX_DELAY };

	private RetryPolicy() {
	}

	/** 30s, 1m, 2m, 4m ... capped at 1 hour, +-10% so a burst of failures does not retry in lockstep. */
	public static Duration deliveryDelay(int attemptsSoFar) {
		return withJitter(Duration.ofSeconds(30).multipliedBy(1L << Math.min(Math.max(attemptsSoFar - 1, 0), 10)), Duration.ofHours(1));
	}

	/** The nominal (jitter-free) wait after the {@code attemptsSoFar}-th failed callback attempt. */
	public static Duration nominalCallbackDelay(int attemptsSoFar) {
		return CALLBACK_SCHEDULE[Math.min(Math.max(attemptsSoFar - 1, 0), CALLBACK_SCHEDULE.length - 1)];
	}

	/** The wait after the {@code attemptsSoFar}-th failed callback attempt: the schedule above with up to 10% taken off. */
	public static Duration callbackDelay(int attemptsSoFar) {
		Duration nominal = nominalCallbackDelay(attemptsSoFar);
		double factor = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.1;
		Duration jittered = Duration.ofMillis((long) (nominal.toMillis() * factor));
		return jittered.compareTo(MIN_RETRY_DELAY) < 0 ? MIN_RETRY_DELAY : jittered;
	}

	/**
	 * When to try a failed callback again, or empty to give up.
	 *
	 * @param now the time of the failure
	 * @param queuedAt when the callback was queued (the 24 hour window starts there)
	 * @param attemptsSoFar attempts made, including the one that just failed
	 * @param retryAfter the partner's Retry-After (null if none): lengthens the wait, never shortens it
	 */
	public static java.util.Optional<Instant> callbackNext(Instant now, Instant queuedAt, int attemptsSoFar, Duration retryAfter) {
		Instant deadline = queuedAt.plus(CALLBACK_WINDOW);
		if (attemptsSoFar >= MAX_CALLBACK_ATTEMPTS || now.plus(MIN_RETRY_DELAY).isAfter(deadline)) {
			return java.util.Optional.empty();
		}
		Duration delay = callbackDelay(attemptsSoFar);
		if (retryAfter != null && !retryAfter.isNegative()) {
			Duration asked = retryAfter.compareTo(CALLBACK_MAX_DELAY) > 0 ? CALLBACK_MAX_DELAY : retryAfter;
			if (asked.compareTo(delay) > 0) {
				delay = asked;
			}
		}
		Instant next = now.plus(delay);
		return java.util.Optional.of(next.isAfter(deadline) ? deadline : next);
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
