package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import app.adera.notification.partner.delivery.CallbackSender;
import app.adera.notification.partner.delivery.RetryPolicy;
import org.junit.jupiter.api.Test;

/** The retry schedules the docs quote: callbacks keep trying for 24 hours, deliveries for about an hour. */
class RetryPolicyTest {

	private static final Instant T0 = Instant.parse("2026-10-26T10:00:00Z");

	/** Plays a callback that always fails: returns the time of every attempt, the first one at T0. */
	private static List<Instant> playAlwaysFailing(Duration retryAfter) {
		List<Instant> attempts = new ArrayList<>(List.of(T0));
		Instant now = T0;
		for (int n = 1; n <= 100; n++) {
			Optional<Instant> next = RetryPolicy.callbackNext(now, T0, n, retryAfter);
			if (next.isEmpty()) {
				break;
			}
			now = next.get();
			attempts.add(now);
		}
		return attempts;
	}

	@Test
	void theNominalScheduleIsMonotonicCappedAndStartsAtOneMinute() {
		List<Long> minutes = new ArrayList<>();
		for (int n = 1; n <= 12; n++) {
			minutes.add(RetryPolicy.nominalCallbackDelay(n).toMinutes());
		}
		assertThat(minutes).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 120L, 240L, 240L, 240L, 240L);
		assertThat(minutes).isSorted();
		assertThat(RetryPolicy.nominalCallbackDelay(500)).isEqualTo(RetryPolicy.CALLBACK_MAX_DELAY);
	}

	@Test
	void jitterOnlyShortensAndNeverGoesBelowTheMinimumOrAboveTheCap() {
		for (int n = 1; n <= 20; n++) {
			Duration nominal = RetryPolicy.nominalCallbackDelay(n);
			for (int i = 0; i < 200; i++) {
				Duration d = RetryPolicy.callbackDelay(n);
				assertThat(d).isLessThanOrEqualTo(nominal).isGreaterThanOrEqualTo(RetryPolicy.MIN_RETRY_DELAY);
				assertThat(d.toMillis()).isGreaterThanOrEqualTo((long) (nominal.toMillis() * 0.9) - 1);
			}
		}
	}

	@Test
	void aCallbackThatAlwaysFailsIsTriedForAboutADayThenGivenUp() {
		List<Instant> attempts = playAlwaysFailing(null);
		Duration window = Duration.between(T0, attempts.get(attempts.size() - 1));
		assertThat(window).as("last attempt is at the 24 h deadline, never after").isEqualTo(Duration.ofHours(24));
		assertThat(attempts.size()).as("attempts").isBetween(12, RetryPolicy.MAX_CALLBACK_ATTEMPTS);
		for (int i = 1; i < attempts.size(); i++) {
			Duration gap = Duration.between(attempts.get(i - 1), attempts.get(i));
			assertThat(gap).as("gap before attempt " + (i + 1)).isGreaterThanOrEqualTo(RetryPolicy.MIN_RETRY_DELAY).isLessThanOrEqualTo(RetryPolicy.CALLBACK_MAX_DELAY);
			if (i > 1 && i < attempts.size() - 1) { // the last gap is shortened to land exactly on the deadline
				assertThat(gap.toMillis()).as("gaps do not shrink (beyond jitter)").isGreaterThanOrEqualTo((long) (Duration.between(attempts.get(i - 2), attempts.get(i - 1)).toMillis() * 0.8));
			}
		}
		// the early waits really are the documented ones (within the 10% jitter)
		long[] nominal = { 60, 120, 240, 480, 960, 1920, 3600, 7200 };
		for (int i = 0; i < nominal.length; i++) {
			long gap = Duration.between(attempts.get(i), attempts.get(i + 1)).toSeconds();
			assertThat(gap).as("wait " + (i + 1)).isBetween((long) (nominal[i] * 0.9) - 1, nominal[i]);
		}
	}

	@Test
	void theDeadlineAndTheAttemptCapStopTheSchedule() {
		assertThat(RetryPolicy.callbackNext(T0.plus(Duration.ofHours(24)), T0, 3, null)).as("at the deadline").isEmpty();
		assertThat(RetryPolicy.callbackNext(T0.plus(Duration.ofHours(24)).minusSeconds(10), T0, 3, null)).as("too close to the deadline for a retry").isEmpty();
		assertThat(RetryPolicy.callbackNext(T0.plusSeconds(1), T0, RetryPolicy.MAX_CALLBACK_ATTEMPTS, null)).isEmpty();
		assertThat(RetryPolicy.callbackNext(T0.plusSeconds(1), T0, 1, null)).isPresent();
	}

	@Test
	void aPartnersRetryAfterCanLengthenAWaitButNeverShortenIt() {
		for (int i = 0; i < 100; i++) {
			// asks for 10 minutes while the schedule says about 1: honoured
			Instant next = RetryPolicy.callbackNext(T0, T0, 1, Duration.ofMinutes(10)).orElseThrow();
			assertThat(Duration.between(T0, next)).isEqualTo(Duration.ofMinutes(10));
			// asks for 5 seconds (or 0) while the schedule says an hour: the schedule wins
			for (Duration tiny : new Duration[] { Duration.ofSeconds(5), Duration.ZERO }) {
				Instant later = RetryPolicy.callbackNext(T0, T0, 7, tiny).orElseThrow();
				assertThat(Duration.between(T0, later)).isGreaterThanOrEqualTo(Duration.ofMinutes(54));
			}
			// asks for three days: capped at the schedule's maximum
			assertThat(Duration.between(T0, RetryPolicy.callbackNext(T0, T0, 2, Duration.ofDays(3)).orElseThrow())).isEqualTo(RetryPolicy.CALLBACK_MAX_DELAY);
		}
		// and it never pushes a retry past the deadline
		assertThat(RetryPolicy.callbackNext(T0.plus(Duration.ofHours(23)), T0, 5, Duration.ofHours(4)).orElseThrow()).isEqualTo(T0.plus(Duration.ofHours(24)));
	}

	@Test
	void aWholeRunWithRetryAfterStillEndsAtTheDeadline() {
		List<Instant> attempts = playAlwaysFailing(Duration.ofHours(1));
		assertThat(Duration.between(T0, attempts.get(attempts.size() - 1))).isLessThanOrEqualTo(Duration.ofHours(24));
		assertThat(attempts.size()).isLessThanOrEqualTo(RetryPolicy.MAX_CALLBACK_ATTEMPTS);
	}

	@Test
	void deliveriesBackOffExponentiallyUpToAnHour() {
		assertThat(RetryPolicy.deliveryDelay(1).toSeconds()).isBetween(27L, 33L);
		assertThat(RetryPolicy.deliveryDelay(4).toSeconds()).isBetween(216L, 264L);
		for (int n = 1; n < 12; n++) {
			assertThat(RetryPolicy.deliveryDelay(n + 1).toMillis() * 1.25).as("grows (beyond jitter) until the cap").isGreaterThanOrEqualTo(RetryPolicy.deliveryDelay(n).toMillis() * (n < 7 ? 1.6 : 0.9));
		}
		assertThat(RetryPolicy.deliveryDelay(50).toMinutes()).isBetween(53L, 66L);
		assertThat(RetryPolicy.MAX_DELIVERY_ATTEMPTS).isEqualTo(8);
	}

	@Test
	void retryAfterHeadersAreParsedFromSecondsAndDates() throws Exception {
		java.lang.reflect.Method parse = CallbackSender.class.getDeclaredMethod("parseRetryAfter", String.class, Instant.class);
		parse.setAccessible(true);
		assertThat(parse.invoke(null, "120", T0)).isEqualTo(Duration.ofSeconds(120));
		assertThat(parse.invoke(null, "Mon, 26 Oct 2026 10:05:00 GMT", T0)).isEqualTo(Duration.ofMinutes(5));
		assertThat(parse.invoke(null, "Mon, 26 Oct 2026 09:00:00 GMT", T0)).as("a date in the past is no instruction").isNull();
		assertThat(parse.invoke(null, "soon", T0)).isNull();
		assertThat(parse.invoke(null, "-5", T0)).isNull();
		assertThat(parse.invoke(null, null, T0)).isNull();
		assertThat(parse.invoke(null, "99999999999", T0)).as("absurd values are cut to a week, the policy caps them further").isEqualTo(Duration.ofDays(7));
	}

}
