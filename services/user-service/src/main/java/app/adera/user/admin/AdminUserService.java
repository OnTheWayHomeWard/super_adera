package app.adera.user.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.user.auth.KratosClient;
import app.adera.user.profile.AppUser;
import app.adera.user.profile.UserRepository;
import app.adera.user.profile.ValidationFailedException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {

	private static final String SUMMARY = "id, email, phone_e164, first_name, last_name, status, market, language, verification_tier, created_at, last_seen_at";

	private final JdbcClient jdbc;
	private final UserRepository users;
	private final AuditClient audit;
	private final KratosClient kratos;
	private final Clock clock;

	public AdminUserService(JdbcClient jdbc, UserRepository users, AuditClient audit, KratosClient kratos, Clock clock) {
		this.jdbc = jdbc;
		this.users = users;
		this.audit = audit;
		this.kratos = kratos;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(StaffRole role, String q, String status, int page, int size) {
		int pageSize = Math.max(1, Math.min(size, 100));
		int pageNo = Math.max(0, page);
		String statusFilter = null;
		if (status != null && !status.isBlank()) {
			try {
				statusFilter = AppUser.Status.valueOf(status.trim().toUpperCase()).name();
			}
			catch (IllegalArgumentException e) {
				throw new ValidationFailedException("status", "must be ACTIVE, SUSPENDED or DELETED");
			}
		}
		StringBuilder where = new StringBuilder(" WHERE 1 = 1");
		Map<String, Object> params = new LinkedHashMap<>();
		if (statusFilter != null) {
			where.append(" AND status = :status");
			params.put("status", statusFilter);
		}
		String term = q == null ? "" : q.trim();
		if (!term.isEmpty()) {
			UUID id = uuidOrNull(term);
			if (id != null) {
				where.append(" AND id = :id");
				params.put("id", id);
			}
			else {
				String like = "%" + escapeLike(term.toLowerCase()) + "%";
				params.put("like", like);
				// A VIEWER sees phone and email masked, so matching on them would let them confirm a full value they cannot see.
				boolean contactVisible = role.atLeast(StaffRole.ADMIN);
				where.append(" AND (" + (contactVisible ? "lower(email) LIKE :like ESCAPE '\\' OR lower(phone_e164) LIKE :like ESCAPE '\\' OR " : "")
						+ "lower(first_name) LIKE :like ESCAPE '\\' OR lower(last_name) LIKE :like ESCAPE '\\'"
						+ " OR lower(coalesce(first_name, '') || ' ' || coalesce(last_name, '')) LIKE :like ESCAPE '\\'");
				String digits = term.replaceAll("[^0-9]", "");
				if (contactVisible && digits.length() >= 3 && digits.length() == term.replaceAll("[\\s+()-]", "").length()) {
					// a phone typed with spaces or dashes still matches
					params.put("digits", "%" + digits + "%");
					where.append(" OR regexp_replace(phone_e164, '[^0-9]', '', 'g') LIKE :digits");
				}
				where.append(")");
			}
		}
		long total = bind(jdbc.sql("SELECT COUNT(*) FROM app_user" + where), params).query(Long.class).single();
		List<Map<String, Object>> items = bind(jdbc.sql("SELECT " + SUMMARY + " FROM app_user" + where
				+ " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset"), params)
			.param("limit", pageSize).param("offset", (long) pageNo * pageSize)
			.query().listOfRows().stream().map(r -> summary(r, role)).toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("page", pageNo);
		out.put("size", pageSize);
		out.put("total", total);
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> stats() {
		Map<String, Object> row = jdbc.sql("""
				SELECT COUNT(*) AS total,
				       COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active,
				       COUNT(*) FILTER (WHERE status = 'SUSPENDED') AS suspended,
				       COUNT(*) FILTER (WHERE status = 'DELETED') AS deleted,
				       COUNT(*) FILTER (WHERE created_at >= now() - interval '7 days') AS "createdLast7Days"
				FROM app_user""").query().singleRow();
		Map<String, Object> out = new LinkedHashMap<>();
		row.forEach((k, v) -> out.put(k, ((Number) v).longValue()));
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> detail(StaffRole role, UUID id) {
		Map<String, Object> row = jdbc.sql("SELECT * FROM app_user WHERE id = :id").param("id", id).query().listOfRows().stream().findFirst()
			.orElseThrow(() -> new ApiException(404, "not_found"));
		boolean masked = !role.atLeast(StaffRole.ADMIN);
		Map<String, Object> out = summary(row, role);
		out.put("gender", masked ? null : row.get("gender"));
		out.put("dateOfBirth", masked || row.get("date_of_birth") == null ? null : row.get("date_of_birth").toString());
		out.put("timezone", row.get("timezone"));
		out.put("avatarImageId", row.get("avatar_image_id"));
		Instant locationAt = instant(row.get("location_updated_at"));
		if (locationAt == null) {
			out.put("location", null);
		}
		else {
			Map<String, Object> location = new LinkedHashMap<>();
			location.put("countryCode", row.get("country_code"));
			location.put("region", row.get("region"));
			location.put("city", row.get("city"));
			location.put("hasPreciseLocation", row.get("latitude") != null && row.get("longitude") != null);
			location.put("updatedAt", locationAt);
			out.put("location", location);
		}
		out.put("termsAcceptedVersion", row.get("terms_accepted_version"));
		out.put("termsAcceptedAt", instant(row.get("terms_accepted_at")));
		out.put("deletedAt", instant(row.get("deleted_at")));
		out.put("updatedAt", instant(row.get("updated_at")));
		Object extra = parseJson(row.get("extra"));
		if (masked) {
			out.put("extra", null);
			out.put("extraKeys", extra instanceof Map<?, ?> m ? m.keySet().stream().map(String::valueOf).sorted().toList() : List.of());
		}
		else {
			out.put("extra", extra);
		}
		Map<String, Object> devices = new LinkedHashMap<>();
		Map<String, Object> counts = jdbc.sql("SELECT COUNT(*) FILTER (WHERE is_active) AS active, COUNT(*) AS total FROM device WHERE user_id = :id")
			.param("id", id).query().singleRow();
		devices.put("active", ((Number) counts.get("active")).longValue());
		devices.put("total", ((Number) counts.get("total")).longValue());
		devices.put("platforms", new ArrayList<>(new LinkedHashSet<>(jdbc.sql("SELECT DISTINCT platform FROM device WHERE user_id = :id AND is_active ORDER BY platform")
			.param("id", id).query(String.class).list())));
		out.put("devices", devices);
		out.put("aliasCount", jdbc.sql("SELECT COUNT(*) FROM app_user_alias WHERE user_id = :id").param("id", id).query(Long.class).single());
		out.put("mutePreferences", jdbc.sql("SELECT app_id, mute_in_app, mute_sms, mute_email, updated_at FROM app_notification_preference WHERE user_id = :id ORDER BY updated_at DESC")
			.param("id", id).query((rs, n) -> {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("appId", rs.getObject("app_id", UUID.class));
				m.put("muteInApp", rs.getBoolean("mute_in_app"));
				m.put("muteSms", rs.getBoolean("mute_sms"));
				m.put("muteEmail", rs.getBoolean("mute_email"));
				m.put("updatedAt", rs.getTimestamp("updated_at").toInstant());
				return m;
			}).list());
		out.put("masked", masked);
		return out;
	}

	/**
	 * ACTIVE, SUSPENDED or DELETED. DELETED is final. Asking for the status the user already has is a no-op (200, nothing audited).
	 * The audit entry is written in the same step: if it fails, the change is rolled back.
	 */
	@Transactional
	public Map<String, Object> setStatus(UUID staffId, StaffRole role, String ip, UUID id, Map<String, Object> body) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (String key : body.keySet()) {
			if (!key.equals("status") && !key.equals("reason")) {
				errors.put(key, "unknown field");
			}
		}
		AppUser.Status target = null;
		try {
			target = AppUser.Status.valueOf(String.valueOf(body.get("status")).trim().toUpperCase());
		}
		catch (IllegalArgumentException e) {
			errors.put("status", "must be ACTIVE, SUSPENDED or DELETED");
		}
		String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason")).strip();
		if (reason != null && reason.length() > 500) {
			errors.put("reason", "max 500 characters");
		}
		else if (target != null && target != AppUser.Status.ACTIVE && (reason == null || reason.length() < 3)) {
			errors.put("reason", "required when suspending or deleting (3-500 characters); it is kept in the audit log");
		}
		if (!errors.isEmpty()) {
			throw new ValidationFailedException(errors);
		}
		AppUser user = users.findById(id).orElseThrow(() -> new ApiException(404, "not_found"));
		AppUser.Status before = user.getStatus();
		if (before == target) {
			return detail(role, id);
		}
		if (before == AppUser.Status.DELETED || user.getDeletedAt() != null) {
			throw new ApiException(409, "user_deleted");
		}
		user.changeStatus(target, clock.instant());
		users.flush();
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("from", before.name());
		meta.put("to", target.name());
		if (reason != null && !reason.isEmpty()) {
			meta.put("reason", reason);
		}
		audit.record(staffId.toString(), role.name(), "user.status", "app_user", id.toString(), meta, ip);
		if (target != AppUser.Status.ACTIVE) {
			kratos.revokeSessions(id);
		}
		return detail(role, id);
	}

	private static Map<String, Object> summary(Map<String, Object> r, StaffRole role) {
		boolean masked = !role.atLeast(StaffRole.ADMIN);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", r.get("id"));
		out.put("email", masked ? Masking.email((String) r.get("email")) : r.get("email"));
		out.put("phone", masked ? Masking.phone((String) r.get("phone_e164")) : r.get("phone_e164"));
		out.put("firstName", r.get("first_name"));
		out.put("lastName", r.get("last_name"));
		out.put("status", r.get("status"));
		out.put("market", r.get("market"));
		out.put("language", r.get("language"));
		out.put("verificationTier", r.get("verification_tier"));
		out.put("createdAt", instant(r.get("created_at")));
		out.put("lastSeenAt", instant(r.get("last_seen_at")));
		return out;
	}

	private static Instant instant(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof Timestamp t) {
			return t.toInstant();
		}
		if (v instanceof java.time.OffsetDateTime o) {
			return o.toInstant();
		}
		return Instant.parse(v.toString());
	}

	private Object parseJson(Object v) {
		if (v == null) {
			return Map.of();
		}
		try {
			return new tools.jackson.databind.json.JsonMapper().readValue(v.toString(), Object.class);
		}
		catch (RuntimeException e) {
			return Map.of();
		}
	}

	private static UUID uuidOrNull(String s) {
		try {
			return s.length() == 36 ? UUID.fromString(s) : null;
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String escapeLike(String s) {
		return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static StatementSpec bind(StatementSpec spec, Map<String, Object> params) {
		StatementSpec out = spec;
		for (Map.Entry<String, Object> e : params.entrySet()) {
			out = out.param(e.getKey(), e.getValue());
		}
		return out;
	}

}
