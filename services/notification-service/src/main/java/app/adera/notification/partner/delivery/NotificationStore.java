package app.adera.notification.partner.delivery;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.auth.RegistryClient;
import app.adera.notification.partner.template.TemplateService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every write to a notification's life cycle, each in its own short transaction (so no database connection is held while
 * we talk to Twilio, Resend, Firebase or a partner).
 */
@Component
public class NotificationStore {

	public record NewNotification(UUID appId, String environment, String externalId, String requestHash, UUID userId, String alias,
			UUID templateId, int templateVersion, Map<String, String> params, Map<String, Object> data, String kind, String channel,
			String callbackUrl, Instant expiresAt) {
	}

	private final JdbcClient jdbc;
	private final UsageService usage;
	private final JsonMapper mapper;
	private final Clock clock;

	public NotificationStore(JdbcClient jdbc, UsageService usage, JsonMapper mapper, Clock clock) {
		this.jdbc = jdbc;
		this.usage = usage;
		this.mapper = mapper;
		this.clock = clock;
	}

	/**
	 * Stores a new request and counts it against the app's limits, atomically.
	 *
	 * @return the new id, or empty if this (app, environment, externalId) already exists (the caller replays it)
	 * @throws ApiException 429 {@code quota_exceeded} when a limit would be exceeded; nothing is stored in that case
	 */
	@Transactional
	public Optional<UUID> insertQueued(NewNotification n, RegistryClient.Limit limit) {
		Instant now = clock.instant();
		UUID id = UUID.randomUUID();
		int inserted = insert(id, n, "QUEUED", null, now, now, null);
		if (inserted == 0) {
			return Optional.empty();
		}
		Optional<String> exceeded = usage.reserve(n.appId(), n.environment(), n.channel(), n.userId(), limit, now);
		if (exceeded.isPresent()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("limit", exceeded.get());
			details.put("channel", n.channel());
			throw new ApiException(429, "quota_exceeded", details); // rolls the insert back
		}
		return Optional.of(id);
	}

	/** A request that is already decided at the door (the user muted this app). Terminal, not counted against limits. */
	@Transactional
	public Optional<UUID> insertTerminal(NewNotification n, String status, String reason) {
		Instant now = clock.instant();
		UUID id = UUID.randomUUID();
		int inserted = insert(id, n, status, reason, now, now, now);
		if (inserted == 0) {
			return Optional.empty();
		}
		enqueueCallback(id, now);
		return Optional.of(id);
	}

	private int insert(UUID id, NewNotification n, String status, String reason, Instant nextAttempt, Instant now, Instant completedAt) {
		return jdbc.sql("""
				INSERT INTO notification (id, app_id, environment, external_id, request_hash, user_id, user_alias, template_id, template_version,
				  params, data, kind, channel, status, reason, attempts, next_attempt_at, callback_url, expires_at, created_at, updated_at, completed_at)
				VALUES (:id, :app, :env, :ext, :hash, :user, :alias, :tpl, :ver, CAST(:params AS jsonb), CAST(:data AS jsonb), :kind, :channel,
				  :status, :reason, 0, :next, :cb, :exp, :now, :now, :done)
				ON CONFLICT (app_id, environment, external_id) DO NOTHING""")
			.param("id", id).param("app", n.appId()).param("env", n.environment()).param("ext", n.externalId()).param("hash", n.requestHash())
			.param("user", n.userId()).param("alias", n.alias()).param("tpl", n.templateId()).param("ver", n.templateVersion())
			.param("params", mapper.writeValueAsString(n.params())).param("data", mapper.writeValueAsString(n.data()))
			.param("kind", n.kind()).param("channel", n.channel()).param("status", status).param("reason", reason)
			.param("next", ts(nextAttempt)).param("cb", n.callbackUrl()).param("exp", ts(n.expiresAt())).param("now", ts(now))
			.param("done", completedAt == null ? null : ts(completedAt)).update();
	}

	@Transactional
	public void recordAttempt(UUID notificationId, String channel, String provider, String outcome, String errorCode) {
		jdbc.sql("INSERT INTO notification_attempt (id, notification_id, channel, provider, outcome, error_code, at) VALUES (:id, :n, :c, :p, :o, :e, :at)")
			.param("id", UUID.randomUUID()).param("n", notificationId).param("c", channel).param("p", provider).param("o", outcome)
			.param("e", errorCode).param("at", ts(clock.instant())).update();
	}

	@Transactional
	public void markSent(UUID id) {
		finish(id, Notification.SENT, null);
	}

	/** FAILED, UNREACHABLE, SUPPRESSED or EXPIRED: it will not be tried again. */
	@Transactional
	public void markTerminal(UUID id, String status, String reason) {
		finish(id, status, reason);
	}

	@Transactional
	public void scheduleRetry(UUID id, Instant nextAttemptAt, String reason) {
		jdbc.sql("UPDATE notification SET status = 'RETRYING', reason = :r, next_attempt_at = :n, locked_until = NULL, updated_at = :now WHERE id = :id")
			.param("r", reason).param("n", ts(nextAttemptAt)).param("now", ts(clock.instant())).param("id", id).update();
	}

	private void finish(UUID id, String status, String reason) {
		Instant now = clock.instant();
		jdbc.sql("UPDATE notification SET status = :s, reason = :r, locked_until = NULL, completed_at = :now, updated_at = :now WHERE id = :id")
			.param("s", status).param("r", reason).param("now", ts(now)).param("id", id).update();
		enqueueCallback(id, now);
	}

	/** Queues the signed status report, if the partner gave a callback address. */
	private void enqueueCallback(UUID id, Instant now) {
		Map<String, Object> n = jdbc.sql("SELECT id, app_id, environment, external_id, channel, status, reason, callback_url FROM notification WHERE id = :id")
			.param("id", id).query().singleRow();
		if (n.get("callback_url") == null) {
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("event", "notification.status");
		payload.put("notificationId", n.get("id").toString());
		payload.put("externalId", n.get("external_id"));
		payload.put("status", n.get("status"));
		payload.put("channel", n.get("channel"));
		payload.put("reason", n.get("reason"));
		payload.put("environment", n.get("environment"));
		payload.put("at", now.toString());
		jdbc.sql("""
				INSERT INTO callback (id, notification_id, url, payload, status, attempts, next_attempt_at, created_at, updated_at)
				VALUES (:id, :n, :url, CAST(:payload AS jsonb), 'PENDING', 0, :now, :now, :now)""")
			.param("id", UUID.randomUUID()).param("n", id).param("url", n.get("callback_url")).param("payload", mapper.writeValueAsString(payload))
			.param("now", ts(now)).update();
	}

	/** The in-app inbox entry. Idempotent per notification, so a retry never shows the message twice. */
	@Transactional
	public void addInbox(UUID notificationId, UUID userId, UUID appId, String title, String body, Map<String, Object> data) {
		jdbc.sql("""
				INSERT INTO inbox_item (id, user_id, app_id, notification_id, title, body, data, created_at)
				VALUES (:id, :u, :a, :n, :t, :b, CAST(:d AS jsonb), :now) ON CONFLICT (notification_id) DO NOTHING""")
			.param("id", UUID.randomUUID()).param("u", userId).param("a", appId).param("n", notificationId)
			.param("t", clip(title, TemplateService.INBOX_TITLE_MAX)).param("b", clip(body, TemplateService.INBOX_BODY_MAX)).param("d", mapper.writeValueAsString(data)).param("now", ts(clock.instant())).update();
	}

	/** A template edited after acceptance can render longer than the column; cut instead of failing the delivery. */
	private static String clip(String text, int max) {
		return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
	}

	private static Timestamp ts(Instant i) {
		return Timestamp.from(i);
	}

}
