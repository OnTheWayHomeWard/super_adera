package app.adera.notification.partner.delivery;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.partner.auth.RegistryClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the per-app, per-channel limits staff configure, counted per UTC day:
 * messages to one user per day, messages per day, distinct users per day, and a lifetime total.
 * A null limit means unlimited, zero means none. Checks are serialised per app/channel with a row lock, so
 * concurrent requests cannot overshoot a limit.
 */
@Service
public class UsageService {

	private final JdbcClient jdbc;

	public UsageService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Reserves one message against the limits. Must run in the caller's transaction (so a rejected request leaves no trace).
	 *
	 * @return the name of the limit that would be exceeded, or empty if the message was counted
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<String> reserve(UUID appId, String environment, String channel, UUID userId, RegistryClient.Limit limit, Instant now) {
		jdbc.sql("INSERT INTO usage_total (app_id, environment, channel, total) VALUES (:a, :e, :c, 0) ON CONFLICT DO NOTHING")
			.param("a", appId).param("e", environment).param("c", channel).update();
		long total = jdbc.sql("SELECT total FROM usage_total WHERE app_id = :a AND environment = :e AND channel = :c FOR UPDATE")
			.param("a", appId).param("e", environment).param("c", channel).query(Long.class).single();
		LocalDate day = now.atZone(ZoneOffset.UTC).toLocalDate();
		if (limit != null) {
			if (limit.maxTotal() != null && total >= limit.maxTotal()) {
				return Optional.of("max_total");
			}
			Map<String, Object> today = jdbc.sql("SELECT COALESCE(SUM(sent), 0) AS sent, COUNT(*) AS users FROM usage_daily WHERE app_id = :a AND environment = :e AND channel = :c AND day = :d")
				.param("a", appId).param("e", environment).param("c", channel).param("d", java.sql.Date.valueOf(day)).query().singleRow();
			long sentToday = ((Number) today.get("sent")).longValue();
			long usersToday = ((Number) today.get("users")).longValue();
			long toThisUser = jdbc.sql("SELECT COALESCE(MAX(sent), 0) FROM usage_daily WHERE app_id = :a AND environment = :e AND channel = :c AND day = :d AND user_id = :u")
				.param("a", appId).param("e", environment).param("c", channel).param("d", java.sql.Date.valueOf(day)).param("u", userId)
				.query(Long.class).single();
			if (limit.maxPerUserPerDay() != null && toThisUser >= limit.maxPerUserPerDay()) {
				return Optional.of("max_per_user_per_day");
			}
			if (limit.maxPerDay() != null && sentToday >= limit.maxPerDay()) {
				return Optional.of("max_per_day");
			}
			if (limit.maxDistinctUsersPerDay() != null && toThisUser == 0 && usersToday >= limit.maxDistinctUsersPerDay()) {
				return Optional.of("max_distinct_users_per_day");
			}
		}
		jdbc.sql("""
				INSERT INTO usage_daily (app_id, environment, channel, day, user_id, sent) VALUES (:a, :e, :c, :d, :u, 1)
				ON CONFLICT (app_id, environment, channel, day, user_id) DO UPDATE SET sent = usage_daily.sent + 1""")
			.param("a", appId).param("e", environment).param("c", channel).param("d", java.sql.Date.valueOf(day)).param("u", userId).update();
		jdbc.sql("UPDATE usage_total SET total = total + 1 WHERE app_id = :a AND environment = :e AND channel = :c")
			.param("a", appId).param("e", environment).param("c", channel).update();
		return Optional.empty();
	}

	/** Today's and lifetime numbers for one app (for the dashboard). */
	@Transactional(readOnly = true)
	public java.util.List<Map<String, Object>> summary(UUID appId, String environment) {
		LocalDate day = Instant.now().atZone(ZoneOffset.UTC).toLocalDate();
		return jdbc.sql("""
				SELECT t.channel, t.total AS lifetime,
				       COALESCE((SELECT SUM(sent) FROM usage_daily d WHERE d.app_id = t.app_id AND d.environment = t.environment AND d.channel = t.channel AND d.day = :d), 0) AS today,
				       COALESCE((SELECT COUNT(*) FROM usage_daily d WHERE d.app_id = t.app_id AND d.environment = t.environment AND d.channel = t.channel AND d.day = :d), 0) AS distinct_users_today
				FROM usage_total t WHERE t.app_id = :a AND t.environment = :e ORDER BY t.channel""")
			.param("a", appId).param("e", environment).param("d", java.sql.Date.valueOf(day)).query().listOfRows();
	}

}
