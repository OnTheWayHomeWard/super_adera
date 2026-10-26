package app.adera.registry.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Filtered, paged reading of the audit log (newest first), with the actor's email when the actor is a known staff member. */
@Component
public class AuditQuery {

	public record Filter(UUID actor, String action, String target, String targetType, UUID appId, Instant from, Instant to) {
	}

	private final JdbcClient jdbc;
	private final JsonMapper mapper;

	public AuditQuery(JdbcClient jdbc, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	/** How many events match the filters (ignoring paging). */
	public long count(Filter f) {
		return bind(jdbc.sql("""
				SELECT COUNT(*) FROM audit_event e
				WHERE (CAST(:actor AS uuid) IS NULL OR e.actor_id = CAST(:actor AS uuid))
				  AND (CAST(:actionPrefix AS varchar) IS NULL OR e.action LIKE CAST(:actionPrefix AS varchar) ESCAPE '\\')
				  AND (CAST(:target AS varchar) IS NULL OR e.target_id = CAST(:target AS varchar) OR CAST(e.app_id AS varchar) = CAST(:target AS varchar))
				  AND (CAST(:appId AS uuid) IS NULL OR e.app_id = CAST(:appId AS uuid))
				  AND (CAST(:targetType AS varchar) IS NULL OR e.target_type = CAST(:targetType AS varchar))
				  AND (CAST(:from AS timestamptz) IS NULL OR e.at >= CAST(:from AS timestamptz))
				  AND (CAST(:to AS timestamptz) IS NULL OR e.at < CAST(:to AS timestamptz))
				"""), f).query(Long.class).single();
	}

	private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Filter f) {
		return spec.param("actor", f.actor() == null ? null : f.actor().toString())
			.param("actionPrefix", f.action() == null ? null : likePrefix(f.action()))
			.param("target", f.target())
			.param("targetType", f.targetType())
			.param("appId", f.appId() == null ? null : f.appId().toString())
			.param("from", f.from() == null ? null : Timestamp.from(f.from()))
			.param("to", f.to() == null ? null : Timestamp.from(f.to()));
	}

	@SuppressWarnings("unchecked")
	public List<Map<String, Object>> search(Filter f, int limit, int offset) {
		return jdbc.sql("""
				SELECT e.id, e.actor_id, s.email AS actor_email, e.actor_role, e.action, e.target_type, e.target_id, e.app_id,
				       CAST(e.meta AS text) AS meta, e.ip, e.at
				FROM audit_event e LEFT JOIN staff s ON s.identity_id = e.actor_id
				WHERE (CAST(:actor AS uuid) IS NULL OR e.actor_id = CAST(:actor AS uuid))
				  AND (CAST(:actionPrefix AS varchar) IS NULL OR e.action LIKE CAST(:actionPrefix AS varchar) ESCAPE '\\')
				  AND (CAST(:target AS varchar) IS NULL OR e.target_id = CAST(:target AS varchar) OR CAST(e.app_id AS varchar) = CAST(:target AS varchar))
				  AND (CAST(:appId AS uuid) IS NULL OR e.app_id = CAST(:appId AS uuid))
				  AND (CAST(:targetType AS varchar) IS NULL OR e.target_type = CAST(:targetType AS varchar))
				  AND (CAST(:from AS timestamptz) IS NULL OR e.at >= CAST(:from AS timestamptz))
				  AND (CAST(:to AS timestamptz) IS NULL OR e.at < CAST(:to AS timestamptz))
				ORDER BY e.at DESC, e.id LIMIT :limit OFFSET :offset""")
			.param("actor", f.actor() == null ? null : f.actor().toString())
			.param("actionPrefix", f.action() == null ? null : likePrefix(f.action()))
			.param("target", f.target())
			.param("targetType", f.targetType())
			.param("appId", f.appId() == null ? null : f.appId().toString())
			.param("from", f.from() == null ? null : Timestamp.from(f.from()))
			.param("to", f.to() == null ? null : Timestamp.from(f.to()))
			.param("limit", Math.max(1, Math.min(limit, 200)))
			.param("offset", Math.max(0, offset))
			.query((rs, n) -> {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("id", rs.getObject("id", UUID.class));
				out.put("actorId", rs.getObject("actor_id", UUID.class));
				out.put("actorEmail", rs.getString("actor_email"));
				out.put("actorRole", rs.getString("actor_role"));
				out.put("action", rs.getString("action"));
				out.put("targetType", rs.getString("target_type"));
				out.put("targetId", rs.getString("target_id"));
				out.put("appId", rs.getObject("app_id", UUID.class));
				out.put("meta", mapper.readValue(rs.getString("meta"), Map.class));
				out.put("ip", rs.getString("ip"));
				out.put("at", rs.getTimestamp("at").toInstant());
				return out;
			})
			.list();
	}

	/** The action filter is a starts-with match: "app." finds every app event, "app.status" that one (and anything longer). */
	private static String likePrefix(String action) {
		String base = action.endsWith("*") ? action.substring(0, action.length() - 1) : action;
		return base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
	}

}
