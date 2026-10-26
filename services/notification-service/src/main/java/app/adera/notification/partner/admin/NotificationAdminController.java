package app.adera.notification.partner.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.delivery.UsageService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only visibility for staff: what a mini app has sent, what happened to each message, and how close it is to its limits. */
@RestController
@RequestMapping("/admin/api/v1/notification")
public class NotificationAdminController {

	private final JdbcClient jdbc;
	private final UsageService usage;
	private final app.adera.notification.partner.PartnerProperties props;

	public NotificationAdminController(JdbcClient jdbc, UsageService usage, app.adera.notification.partner.PartnerProperties props) {
		this.jdbc = jdbc;
		this.usage = usage;
		this.props = props;
	}

	/**
	 * Notifications newest first, across all apps or one (appId). Filters: status, channel, environment, kind, from (inclusive) and
	 * to (exclusive) as ISO instants. Never the message values, user ids or aliases: those can hold personal data.
	 */
	@GetMapping("/notifications")
	public org.springframework.http.ResponseEntity<List<Map<String, Object>>> list(@RequestParam(required = false) UUID appId, @RequestParam(required = false) String status,
			@RequestParam(required = false) String channel, @RequestParam(required = false) String environment,
			@RequestParam(required = false) String kind, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(defaultValue = "0") int offset) {
		Map<String, String> errors = new java.util.LinkedHashMap<>();
		java.sql.Timestamp fromAt = instant(from, "from", errors);
		java.sql.Timestamp toAt = instant(to, "to", errors);
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		String filter = """
				WHERE (CAST(:a AS uuid) IS NULL OR app_id = CAST(:a AS uuid))
				  AND (CAST(:s AS varchar) IS NULL OR status = CAST(:s AS varchar))
				  AND (CAST(:c AS varchar) IS NULL OR channel = CAST(:c AS varchar))
				  AND (CAST(:e AS varchar) IS NULL OR environment = CAST(:e AS varchar))
				  AND (CAST(:k AS varchar) IS NULL OR kind = CAST(:k AS varchar))
				  AND (CAST(:from AS timestamptz) IS NULL OR created_at >= CAST(:from AS timestamptz))
				  AND (CAST(:to AS timestamptz) IS NULL OR created_at < CAST(:to AS timestamptz))""";
		String a = appId == null ? null : appId.toString();
		long total = jdbc.sql("SELECT COUNT(*) FROM notification " + filter)
			.param("a", a).param("s", upper(status)).param("c", upper(channel)).param("e", upper(environment))
			.param("k", upper(kind)).param("from", fromAt).param("to", toAt).query(Long.class).single();
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT id, app_id AS "appId", external_id AS "externalId", environment, status, channel, reason, attempts, kind,
				       created_at AS "createdAt", completed_at AS "completedAt"
				FROM notification""" + " " + filter + """

				ORDER BY created_at DESC, id LIMIT :l OFFSET :o""")
			.param("a", a).param("s", upper(status)).param("c", upper(channel)).param("e", upper(environment))
			.param("k", upper(kind)).param("from", fromAt).param("to", toAt)
			.param("l", Math.max(1, Math.min(limit, 200))).param("o", Math.max(0, offset))
			.query().listOfRows();
		return org.springframework.http.ResponseEntity.ok().header("X-Total-Count", String.valueOf(total)).body(rows);
	}

	/** Real counts for the dashboard: everything, the last 24 hours, by status and by channel. Optionally for one app. */
	@GetMapping("/stats")
	public Map<String, Object> stats(@RequestParam(required = false) UUID appId) {
		String app = appId == null ? null : appId.toString();
		Map<String, Long> byStatus = counts("status", app, false);
		Map<String, Long> byChannel = counts("channel", app, false);
		Map<String, Long> recent = counts("status", app, true);
		Map<String, Object> last24h = new java.util.LinkedHashMap<>();
		last24h.put("total", recent.values().stream().mapToLong(Long::longValue).sum());
		last24h.put("byStatus", recent);
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("total", byStatus.values().stream().mapToLong(Long::longValue).sum());
		out.put("last24h", last24h);
		out.put("byStatus", byStatus);
		out.put("byChannel", byChannel);
		return out;
	}

	private Map<String, Long> counts(String column, String app, boolean last24h) {
		Map<String, Long> out = new java.util.TreeMap<>();
		jdbc.sql("SELECT " + column + " AS k, COUNT(*) AS n FROM notification WHERE (CAST(:a AS uuid) IS NULL OR app_id = CAST(:a AS uuid))"
				+ (last24h ? " AND created_at >= now() - interval '24 hours'" : "") + " GROUP BY " + column)
			.param("a", app).query().listOfRows().forEach(r -> out.put((String) r.get("k"), ((Number) r.get("n")).longValue()));
		return out;
	}

	private static String upper(String s) {
		return s == null || s.isBlank() ? null : s.trim().toUpperCase();
	}

	private static java.sql.Timestamp instant(String raw, String field, Map<String, String> errors) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return java.sql.Timestamp.from(java.time.Instant.parse(raw.trim()));
		}
		catch (java.time.format.DateTimeParseException e) {
			errors.put(field, "must be an ISO-8601 instant like 2026-10-01T00:00:00Z");
			return null;
		}
	}

	/** One notification with every delivery attempt and its callbacks: the answer to "why did it not arrive?". */
	@GetMapping("/notifications/{id}")
	public Map<String, Object> detail(@PathVariable UUID id) {
		Map<String, Object> n = jdbc.sql("""
				SELECT id, app_id AS "appId", external_id AS "externalId", environment, status, channel, reason, attempts, kind,
				       template_id AS "templateId", template_version AS "templateVersion", created_at AS "createdAt",
				       next_attempt_at AS "nextAttemptAt", expires_at AS "expiresAt", completed_at AS "completedAt"
				FROM notification WHERE id = :id""").param("id", id).query().listOfRows().stream().findFirst()
			.orElseThrow(() -> new ApiException(404, "not_found"));
		Map<String, Object> out = new java.util.LinkedHashMap<>(n);
		out.put("attemptLog", jdbc.sql("SELECT channel, provider, outcome, error_code AS \"errorCode\", at FROM notification_attempt WHERE notification_id = :id ORDER BY at")
			.param("id", id).query().listOfRows());
		out.put("callbacks", jdbc.sql("SELECT status, attempts, last_http_status AS \"lastHttpStatus\", last_error AS \"lastError\", next_attempt_at AS \"nextAttemptAt\" FROM callback WHERE notification_id = :id ORDER BY created_at")
			.param("id", id).query().listOfRows());
		return out;
	}

	@GetMapping("/usage")
	public List<Map<String, Object>> usage(@RequestParam UUID appId, @RequestParam(required = false) String environment) {
		return usage.summary(appId, environment == null ? props.environment() : environment.toUpperCase());
	}

}
