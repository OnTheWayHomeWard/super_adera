package app.adera.registry.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Real counts for the dashboard home page. Any staff role. */
@RestController
@RequestMapping("/admin/api/v1")
public class DashboardController {

	private final JdbcClient jdbc;
	private final JsonMapper mapper;

	public DashboardController(JdbcClient jdbc, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@GetMapping("/dashboard")
	@SuppressWarnings("unchecked")
	public Map<String, Object> dashboard() {
		Map<String, Object> apps = new LinkedHashMap<>();
		for (String s : List.of("DRAFT", "LIVE", "PAUSED", "KILLED")) {
			apps.put(s, 0L);
		}
		long total = 0;
		for (Map<String, Object> row : jdbc.sql("SELECT status, COUNT(*) AS n FROM mini_app GROUP BY status").query().listOfRows()) {
			long n = ((Number) row.get("n")).longValue();
			apps.put((String) row.get("status"), n);
			total += n;
		}
		apps.put("total", total);

		Map<String, Object> reports = jdbc.sql("SELECT COUNT(*) FILTER (WHERE status = 'OPEN') AS open, COUNT(*) AS total FROM app_report").query().singleRow();
		Map<String, Object> reviews = jdbc.sql("""
				SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE status = 'HIDDEN') AS hidden,
				       COUNT(*) FILTER (WHERE created_at >= now() - interval '7 days') AS "last7Days" FROM app_review""").query().singleRow();
		Map<String, Object> staff = jdbc.sql("""
				SELECT COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active, COUNT(*) FILTER (WHERE status = 'DISABLED') AS disabled FROM staff""")
			.query().singleRow();

		List<Map<String, Object>> drafts = new ArrayList<>();
		for (Map<String, Object> row : jdbc.sql("""
				SELECT id, slug, CAST(name AS text) AS name, logo_image_id IS NULL AS no_logo, support_email IS NULL AS no_email
				FROM mini_app WHERE status = 'DRAFT' ORDER BY created_at DESC LIMIT 10""").query().listOfRows()) {
			List<String> missing = new ArrayList<>();
			if (Boolean.TRUE.equals(row.get("no_logo"))) {
				missing.add("logo");
			}
			if (Boolean.TRUE.equals(row.get("no_email"))) {
				missing.add("supportEmail");
			}
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("id", (UUID) row.get("id"));
			d.put("slug", row.get("slug"));
			d.put("name", mapper.readValue((String) row.get("name"), Map.class));
			d.put("missingForLive", missing);
			drafts.add(d);
		}

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("apps", apps);
		out.put("reports", counts(reports));
		out.put("reviews", counts(reviews));
		out.put("staff", counts(staff));
		out.put("draftApps", drafts);
		return out;
	}

	private static Map<String, Object> counts(Map<String, Object> row) {
		Map<String, Object> out = new LinkedHashMap<>();
		row.forEach((k, v) -> out.put(k, ((Number) v).longValue()));
		return out;
	}

}
