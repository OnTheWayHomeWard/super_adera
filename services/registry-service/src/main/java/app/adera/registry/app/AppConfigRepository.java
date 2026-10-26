package app.adera.registry.app;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** The small per-app configuration tables (permissions, payment settings, limits, allowed addresses). */
@Repository
public class AppConfigRepository {

	public record CapabilityRow(String capability, String state, Map<String, Object> reason, Instant updatedAt) {
	}

	public record LimitRow(String channel, Integer maxPerUserPerDay, Integer maxPerDay, Integer maxDistinctUsersPerDay, Long maxTotal) {
	}

	public record EnvironmentRow(String environment, List<String> allowedCidrs) {
	}

	public record PaymentRow(List<String> allowedCurrencies, boolean allowHold, Long minAmountMinor, Long maxAmountMinor) {
	}

	private final JdbcClient jdbc;
	private final JsonMapper mapper;

	public AppConfigRepository(JdbcClient jdbc, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	// ---- capabilities
	public List<CapabilityRow> capabilities(UUID appId) {
		return jdbc.sql("SELECT capability, state, reason::text AS reason, updated_at FROM app_capability WHERE app_id = :id ORDER BY capability")
			.param("id", appId)
			.query((rs, n) -> new CapabilityRow(rs.getString("capability"), rs.getString("state"), map(rs, "reason"),
					rs.getTimestamp("updated_at").toInstant()))
			.list();
	}

	public void upsertCapability(UUID appId, String capability, String state, Map<String, String> reason, UUID by, Instant now) {
		jdbc.sql("""
				INSERT INTO app_capability (app_id, capability, state, reason, updated_by, updated_at)
				VALUES (:id, :cap, :state, CAST(:reason AS jsonb), :by, :now)
				ON CONFLICT (app_id, capability) DO UPDATE
				SET state = :state, reason = CAST(:reason AS jsonb), updated_by = :by, updated_at = :now""")
			.param("id", appId).param("cap", capability).param("state", state).param("reason", json(reason))
			.param("by", by).param("now", java.sql.Timestamp.from(now)).update();
	}

	// ---- notification limits
	public List<LimitRow> limits(UUID appId) {
		return jdbc.sql("SELECT channel, max_per_user_per_day, max_per_day, max_distinct_users_per_day, max_total FROM app_notification_limit WHERE app_id = :id ORDER BY channel")
			.param("id", appId)
			.query((rs, n) -> new LimitRow(rs.getString("channel"), (Integer) rs.getObject("max_per_user_per_day"),
					(Integer) rs.getObject("max_per_day"), (Integer) rs.getObject("max_distinct_users_per_day"), (Long) rs.getObject("max_total")))
			.list();
	}

	public void upsertLimit(UUID appId, LimitRow l, Instant now) {
		jdbc.sql("""
				INSERT INTO app_notification_limit (app_id, channel, max_per_user_per_day, max_per_day, max_distinct_users_per_day, max_total, updated_at)
				VALUES (:id, :ch, :pu, :pd, :du, :tot, :now)
				ON CONFLICT (app_id, channel) DO UPDATE
				SET max_per_user_per_day = :pu, max_per_day = :pd, max_distinct_users_per_day = :du, max_total = :tot, updated_at = :now""")
			.param("id", appId).param("ch", l.channel()).param("pu", l.maxPerUserPerDay()).param("pd", l.maxPerDay())
			.param("du", l.maxDistinctUsersPerDay()).param("tot", l.maxTotal()).param("now", java.sql.Timestamp.from(now)).update();
	}

	/** Gives a freshly granted channel conservative defaults, without touching limits an admin already set. */
	public void insertLimitIfAbsent(UUID appId, LimitRow l, Instant now) {
		jdbc.sql("""
				INSERT INTO app_notification_limit (app_id, channel, max_per_user_per_day, max_per_day, max_distinct_users_per_day, max_total, updated_at)
				VALUES (:id, :ch, :pu, :pd, :du, :tot, :now)
				ON CONFLICT (app_id, channel) DO NOTHING""")
			.param("id", appId).param("ch", l.channel()).param("pu", l.maxPerUserPerDay()).param("pd", l.maxPerDay())
			.param("du", l.maxDistinctUsersPerDay()).param("tot", l.maxTotal()).param("now", java.sql.Timestamp.from(now)).update();
	}

	// ---- environments (allowed partner addresses)
	public List<EnvironmentRow> environments(UUID appId) {
		return jdbc.sql("SELECT environment, allowed_cidrs::text AS cidrs FROM app_environment WHERE app_id = :id ORDER BY environment")
			.param("id", appId)
			.query((rs, n) -> new EnvironmentRow(rs.getString("environment"), list(rs, "cidrs")))
			.list();
	}

	public void upsertEnvironment(UUID appId, String environment, List<String> cidrs, Instant now) {
		jdbc.sql("""
				INSERT INTO app_environment (app_id, environment, allowed_cidrs, updated_at)
				VALUES (:id, :env, CAST(:cidrs AS jsonb), :now)
				ON CONFLICT (app_id, environment) DO UPDATE SET allowed_cidrs = CAST(:cidrs AS jsonb), updated_at = :now""")
			.param("id", appId).param("env", environment).param("cidrs", json(cidrs)).param("now", java.sql.Timestamp.from(now)).update();
	}

	// ---- payment settings
	public Optional<PaymentRow> payment(UUID appId) {
		return jdbc.sql("SELECT allowed_currencies::text AS cur, allow_hold, min_amount_minor, max_amount_minor FROM app_payment_settings WHERE app_id = :id")
			.param("id", appId)
			.query((rs, n) -> new PaymentRow(list(rs, "cur"), rs.getBoolean("allow_hold"), (Long) rs.getObject("min_amount_minor"),
					(Long) rs.getObject("max_amount_minor")))
			.optional();
	}

	public void upsertPayment(UUID appId, PaymentRow p, Instant now) {
		jdbc.sql("""
				INSERT INTO app_payment_settings (app_id, allowed_currencies, allow_hold, min_amount_minor, max_amount_minor, updated_at)
				VALUES (:id, CAST(:cur AS jsonb), :hold, :min, :max, :now)
				ON CONFLICT (app_id) DO UPDATE
				SET allowed_currencies = CAST(:cur AS jsonb), allow_hold = :hold, min_amount_minor = :min, max_amount_minor = :max, updated_at = :now""")
			.param("id", appId).param("cur", json(p.allowedCurrencies())).param("hold", p.allowHold())
			.param("min", p.minAmountMinor()).param("max", p.maxAmountMinor()).param("now", java.sql.Timestamp.from(now)).update();
	}

	// ---- json helpers
	private String json(Object value) {
		return mapper.writeValueAsString(value);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(ResultSet rs, String column) throws SQLException {
		String text = rs.getString(column);
		return text == null ? Map.of() : mapper.readValue(text, Map.class);
	}

	@SuppressWarnings("unchecked")
	private List<String> list(ResultSet rs, String column) throws SQLException {
		String text = rs.getString(column);
		return text == null ? List.of() : mapper.readValue(text, List.class);
	}

}
