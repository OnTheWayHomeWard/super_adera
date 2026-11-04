package app.adera.user.consent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.adera.user.admin.StaffRole;
import app.adera.user.profile.NotFoundException;
import app.adera.user.registry.RegistryAppClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Backoffice: per capability of an app, how many users granted, denied or were blocked. COUNTS ONLY (no list of users: who
 * answered what is the user's business and the partner's, not a staff browsing view). Any staff role may read it. The data is
 * here because user-service owns the consent; which capabilities exist for the app comes from registry-service.
 */
@RestController
public class AdminConsentController {

	private final JdbcClient jdbc;
	private final RegistryAppClient registry;

	public AdminConsentController(JdbcClient jdbc, RegistryAppClient registry) {
		this.jdbc = jdbc;
		this.registry = registry;
	}

	@GetMapping("/admin/api/v1/apps/{app}/permissions")
	@Transactional(readOnly = true)
	public Map<String, Object> counts(@RequestHeader("X-Staff-Role") StaffRole role, @PathVariable String app) {
		RegistryAppClient.AppInfo info = registry.app(app).orElseThrow(() -> new NotFoundException("mini app"));
		Map<String, long[]> byCapability = new HashMap<>();
		jdbc.sql("""
				SELECT c.capability,
				       count(*) FILTER (WHERE c.state = 'GRANTED') AS granted,
				       count(*) FILTER (WHERE c.state = 'DENIED') AS denied,
				       count(*) FILTER (WHERE c.state = 'BLOCKED') AS blocked
				FROM app_consent c JOIN app_user u ON u.id = c.user_id AND u.deleted_at IS NULL
				WHERE c.app_id = :a GROUP BY c.capability""")
			.param("a", info.appId())
			.query((rs, n) -> {
				byCapability.put(rs.getString("capability"), new long[] { rs.getLong("granted"), rs.getLong("denied"), rs.getLong("blocked") });
				return null;
			}).list();
		// user.basic needs no dialog: everyone with an alias is connected, except those who disconnected (a DENIED row)
		long connected = jdbc.sql("""
				SELECT count(*) FROM app_user_alias al JOIN app_user u ON u.id = al.user_id AND u.deleted_at IS NULL
				WHERE al.app_id = :a AND NOT EXISTS (SELECT 1 FROM app_consent c WHERE c.user_id = al.user_id AND c.app_id = al.app_id
				                                        AND c.capability = 'user.basic' AND c.state = 'DENIED')""")
			.param("a", info.appId()).query(Long.class).single();
		long disconnected = byCapability.getOrDefault(ConsentService.USER_BASIC, new long[3])[1];
		byCapability.put(ConsentService.USER_BASIC, new long[] { connected, disconnected, 0 });

		List<Map<String, Object>> rows = new ArrayList<>();
		for (RegistryAppClient.GrantedPermission g : info.permissions()) {
			long[] c = byCapability.getOrDefault(g.capability(), new long[3]);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("capability", g.capability());
			row.put("label", g.label());
			row.put("granted", c[0]);
			row.put("denied", c[1]);
			row.put("blocked", c[2]);
			rows.add(row);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("appId", info.appId());
		out.put("slug", info.slug());
		out.put("capabilities", rows);
		return out;
	}

}
