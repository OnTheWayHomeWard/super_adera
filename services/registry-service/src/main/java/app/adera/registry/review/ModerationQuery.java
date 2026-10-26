package app.adera.registry.review;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Cross-app moderation lists (reviews and reports) with the app's slug, for the dashboard. */
@Component
public class ModerationQuery {

	private final JdbcClient jdbc;

	public ModerationQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<Map<String, Object>> reviews(UUID appId, String status, int page, int size) {
		return jdbc.sql("""
				SELECT r.id, r.app_id, a.slug, r.user_id, r.rating, r.comment, r.status, r.moderation_reason, r.created_at, r.updated_at
				FROM app_review r JOIN mini_app a ON a.id = r.app_id
				WHERE (CAST(:app AS uuid) IS NULL OR r.app_id = CAST(:app AS uuid))
				  AND (CAST(:status AS varchar) IS NULL OR r.status = CAST(:status AS varchar))
				ORDER BY r.created_at DESC, r.id LIMIT :limit OFFSET :offset""")
			.param("app", appId == null ? null : appId.toString()).param("status", status)
			.param("limit", size).param("offset", (long) page * size)
			.query((rs, n) -> {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("id", rs.getObject("id", UUID.class));
				out.put("appId", rs.getObject("app_id", UUID.class));
				out.put("appSlug", rs.getString("slug"));
				out.put("userId", rs.getObject("user_id", UUID.class));
				out.put("rating", rs.getInt("rating"));
				out.put("comment", rs.getString("comment"));
				out.put("status", rs.getString("status"));
				out.put("moderationReason", rs.getString("moderation_reason"));
				out.put("createdAt", instant(rs.getTimestamp("created_at")));
				out.put("updatedAt", instant(rs.getTimestamp("updated_at")));
				return out;
			}).list();
	}

	public List<Map<String, Object>> reports(UUID appId, String status, int page, int size) {
		return jdbc.sql("""
				SELECT r.id, r.app_id, a.slug, r.user_id, r.reason, r.details, r.status, r.handled_by, r.handled_at, r.handling_reason, r.created_at
				FROM app_report r JOIN mini_app a ON a.id = r.app_id
				WHERE (CAST(:app AS uuid) IS NULL OR r.app_id = CAST(:app AS uuid))
				  AND (CAST(:status AS varchar) IS NULL OR r.status = CAST(:status AS varchar))
				ORDER BY r.created_at DESC, r.id LIMIT :limit OFFSET :offset""")
			.param("app", appId == null ? null : appId.toString()).param("status", status)
			.param("limit", size).param("offset", (long) page * size)
			.query((rs, n) -> {
				Map<String, Object> out = new LinkedHashMap<>();
				out.put("id", rs.getObject("id", UUID.class));
				out.put("appId", rs.getObject("app_id", UUID.class));
				out.put("appSlug", rs.getString("slug"));
				out.put("userId", rs.getObject("user_id", UUID.class));
				out.put("reason", rs.getString("reason"));
				out.put("details", rs.getString("details"));
				out.put("status", rs.getString("status"));
				out.put("handledBy", rs.getObject("handled_by", UUID.class));
				out.put("handledAt", instant(rs.getTimestamp("handled_at")));
				out.put("handlingReason", rs.getString("handling_reason"));
				out.put("createdAt", instant(rs.getTimestamp("created_at")));
				return out;
			}).list();
	}

	public long countReviews(UUID appId, String status) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM app_review r
				WHERE (CAST(:app AS uuid) IS NULL OR r.app_id = CAST(:app AS uuid))
				  AND (CAST(:status AS varchar) IS NULL OR r.status = CAST(:status AS varchar))""")
			.param("app", appId == null ? null : appId.toString()).param("status", status).query(Long.class).single();
	}

	public long countReports(UUID appId, String status) {
		return jdbc.sql("""
				SELECT COUNT(*) FROM app_report r
				WHERE (CAST(:app AS uuid) IS NULL OR r.app_id = CAST(:app AS uuid))
				  AND (CAST(:status AS varchar) IS NULL OR r.status = CAST(:status AS varchar))""")
			.param("app", appId == null ? null : appId.toString()).param("status", status).query(Long.class).single();
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

}
