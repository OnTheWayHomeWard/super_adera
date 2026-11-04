package app.adera.user.consent;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers the events in {@code partner_event_outbox} to notification-service, oldest first. At least once: an event whose hand-over
 * failed is retried with a growing delay (5 s, doubling, at most 10 minutes) and dropped after 24 hours, like the callbacks it feeds.
 * The payload carries {@code at} (when the user decided) so a partner can order events that arrive late. Sent rows are kept 7 days
 * and then removed. Switched off in tests with {@code adera.user.callbacks-enabled=false} (they call {@link #runOnce()}).
 */
@Component
public class ConsentCallbackDispatcher {

	private static final Logger log = LoggerFactory.getLogger(ConsentCallbackDispatcher.class);
	private static final Duration GIVE_UP_AFTER = Duration.ofHours(24);

	private final JdbcClient jdbc;
	private final ConsentEventClient client;
	private final JsonMapper mapper;
	private final Clock clock;
	private final boolean enabled;

	public ConsentCallbackDispatcher(JdbcClient jdbc, ConsentEventClient client, JsonMapper mapper, Clock clock,
			@org.springframework.beans.factory.annotation.Value("${adera.user.callbacks-enabled:true}") boolean enabled) {
		this.jdbc = jdbc;
		this.client = client;
		this.mapper = mapper;
		this.clock = clock;
		this.enabled = enabled;
	}

	@Scheduled(fixedDelay = 3000)
	void tick() {
		if (!enabled) {
			return;
		}
		try {
			runOnce();
		}
		catch (RuntimeException e) {
			log.error("consent callback dispatcher failed: {}", e.toString());
		}
	}

	/** @return how many events were handled (delivered, rescheduled or dropped) */
	public int runOnce() {
		Instant now = clock.instant();
		jdbc.sql("DELETE FROM partner_event_outbox WHERE sent_at IS NOT NULL AND sent_at < :cut").param("cut", Timestamp.from(now.minus(Duration.ofDays(7)))).update();
		List<Map<String, Object>> due = jdbc.sql("""
				SELECT id, event, app_id, alias, payload::text AS payload, created_at, attempts FROM partner_event_outbox
				WHERE sent_at IS NULL AND next_attempt_at <= :now ORDER BY id LIMIT 50""").param("now", Timestamp.from(now)).query().listOfRows();
		for (Map<String, Object> row : due) {
			long id = ((Number) row.get("id")).longValue();
			int attempts = ((Number) row.get("attempts")).intValue();
			Instant created = ((Timestamp) row.get("created_at")).toInstant();
			if (created.isBefore(now.minus(GIVE_UP_AFTER))) {
				jdbc.sql("UPDATE partner_event_outbox SET sent_at = :now, last_error = 'expired' WHERE id = :id").param("now", Timestamp.from(now)).param("id", id).update();
				log.warn("consent event {} dropped: could not be handed over for 24 hours", id);
				continue;
			}
			try {
				Map<String, Object> event = new LinkedHashMap<>();
				event.put("event", row.get("event"));
				event.put("appId", String.valueOf(row.get("app_id")));
				event.put("alias", row.get("alias"));
				@SuppressWarnings("unchecked")
				Map<String, Object> payload = mapper.readValue((String) row.get("payload"), Map.class);
				event.putAll(payload);
				client.send(event);
				jdbc.sql("UPDATE partner_event_outbox SET sent_at = :now, attempts = attempts + 1, last_error = NULL WHERE id = :id").param("now", Timestamp.from(now)).param("id", id).update();
			}
			catch (RuntimeException e) {
				Duration wait = Duration.ofSeconds(Math.min(600, 5L << Math.min(attempts, 7)));
				jdbc.sql("UPDATE partner_event_outbox SET attempts = attempts + 1, next_attempt_at = :next, last_error = 'unavailable' WHERE id = :id")
					.param("next", Timestamp.from(now.plus(wait))).param("id", id).update();
			}
		}
		return due.size();
	}

}
