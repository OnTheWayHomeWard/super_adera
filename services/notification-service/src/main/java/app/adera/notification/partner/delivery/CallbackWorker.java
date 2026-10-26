package app.adera.notification.partner.delivery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.notification.partner.PartnerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers the queued status reports. A report is retried until the partner answers 2xx on the schedule in
 * {@link RetryPolicy} (1m, 2m, 4m, 8m, 16m, 32m, 1h, 2h, then every 4h, for 24 hours from when it was queued, at most 15
 * attempts); a partner's Retry-After can lengthen a wait. Reports to addresses we refuse (see {@link UrlGuard}) fail at once and
 * are not retried. An unexpected error while sending counts as a failed attempt (it is rescheduled like any other, never left
 * to be re-claimed every minute), and a report that already used all its attempts (for example because workers kept dying
 * on it) is failed without another send.
 */
@Component
public class CallbackWorker {

	private static final Logger log = LoggerFactory.getLogger(CallbackWorker.class);

	private final JdbcClient jdbc;
	private final CallbackSender sender;
	private final PartnerProperties props;

	public CallbackWorker(JdbcClient jdbc, CallbackSender sender, PartnerProperties props) {
		this.jdbc = jdbc;
		this.sender = sender;
		this.props = props;
	}

	@Scheduled(fixedDelay = 2000)
	void tick() {
		if (!props.workersEnabled()) {
			return;
		}
		try {
			runOnce();
		}
		catch (RuntimeException e) {
			log.error("callback worker tick failed: {}", e.toString());
		}
	}

	public int runOnce() {
		List<Map<String, Object>> due = jdbc.sql("""
				UPDATE callback SET status = 'PROCESSING', attempts = attempts + 1, locked_until = now() + interval '60 seconds', updated_at = now()
				WHERE id IN (
				  SELECT id FROM callback
				  WHERE (status = 'PENDING' AND next_attempt_at <= now()) OR (status = 'PROCESSING' AND locked_until < now())
				  ORDER BY next_attempt_at LIMIT 20 FOR UPDATE SKIP LOCKED)
				RETURNING id, url, payload::text AS payload, attempts, created_at""").query().listOfRows();
		for (Map<String, Object> row : due) {
			UUID id = (UUID) row.get("id");
			int attempts = ((Number) row.get("attempts")).intValue();
			Instant queuedAt = toInstant(row.get("created_at"));
			if (attempts > RetryPolicy.MAX_CALLBACK_ATTEMPTS) {
				// claimed again and again without ever finishing (workers died on it): stop instead of looping
				update(id, "FAILED", null, new CallbackSender.Outcome(false, false, 0, "max_attempts"));
				log.warn("callback {} gave up: used all {} attempts", id, RetryPolicy.MAX_CALLBACK_ATTEMPTS);
				continue;
			}
			CallbackSender.Outcome outcome;
			try {
				outcome = sender.send(id, (String) row.get("url"), (String) row.get("payload"));
			}
			catch (RuntimeException e) {
				log.error("callback {} failed unexpectedly: {}", id, e.toString());
				outcome = new CallbackSender.Outcome(false, false, 0, "internal_error");
			}
			if (outcome.delivered()) {
				update(id, "DELIVERED", null, outcome);
				continue;
			}
			java.util.Optional<Instant> next = outcome.blocked() ? java.util.Optional.empty()
					: RetryPolicy.callbackNext(Instant.now(), queuedAt, attempts, outcome.retryAfter());
			if (next.isPresent()) {
				update(id, "PENDING", next.get(), outcome);
			}
			else {
				update(id, "FAILED", null, outcome);
				log.warn("callback {} gave up: {}", id, outcome.error());
			}
		}
		return due.size();
	}

	private static Instant toInstant(Object value) {
		if (value instanceof Timestamp t) {
			return t.toInstant();
		}
		if (value instanceof java.time.OffsetDateTime o) {
			return o.toInstant();
		}
		return Instant.now();
	}

	private void update(UUID id, String status, Instant next, CallbackSender.Outcome outcome) {
		jdbc.sql("""
				UPDATE callback SET status = :s, next_attempt_at = COALESCE(:next, next_attempt_at), locked_until = NULL,
				  last_http_status = :h, last_error = :e, updated_at = now() WHERE id = :id""")
			.param("s", status).param("next", next == null ? null : Timestamp.from(next)).param("h", outcome.httpStatus() == 0 ? null : outcome.httpStatus())
			.param("e", outcome.error()).param("id", id).update();
	}

}
