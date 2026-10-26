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
 * Delivers the queued status reports. A report is retried with backoff (1m, 2m, 4m ... up to 6h, eight attempts: about a
 * day) until the partner answers 2xx. Reports to addresses we refuse (see {@link UrlGuard}) fail at once and are not retried.
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
				RETURNING id, url, payload::text AS payload, attempts""").query().listOfRows();
		for (Map<String, Object> row : due) {
			UUID id = (UUID) row.get("id");
			int attempts = ((Number) row.get("attempts")).intValue();
			CallbackSender.Outcome outcome = sender.send(id, (String) row.get("url"), (String) row.get("payload"));
			if (outcome.delivered()) {
				update(id, "DELIVERED", null, outcome);
			}
			else if (outcome.blocked() || attempts >= RetryPolicy.MAX_CALLBACK_ATTEMPTS) {
				update(id, "FAILED", null, outcome);
				log.warn("callback {} gave up: {}", id, outcome.error());
			}
			else {
				update(id, "PENDING", Instant.now().plus(RetryPolicy.callbackDelay(attempts)), outcome);
			}
		}
		return due.size();
	}

	private void update(UUID id, String status, Instant next, CallbackSender.Outcome outcome) {
		jdbc.sql("""
				UPDATE callback SET status = :s, next_attempt_at = COALESCE(:next, next_attempt_at), locked_until = NULL,
				  last_http_status = :h, last_error = :e, updated_at = now() WHERE id = :id""")
			.param("s", status).param("next", next == null ? null : Timestamp.from(next)).param("h", outcome.httpStatus() == 0 ? null : outcome.httpStatus())
			.param("e", outcome.error()).param("id", id).update();
	}

}
