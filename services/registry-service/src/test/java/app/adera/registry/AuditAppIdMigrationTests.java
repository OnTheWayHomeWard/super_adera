package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V5 adds audit_event.app_id and fills it for old entries where the app can be derived. */
class AuditAppIdMigrationTests {

	private static final String APP = "00000000-0000-0000-0000-0000000000a1";

	@Test
	void oldEntriesAboutAnAppGetItsId() throws Exception {
		try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			pg.start();
			Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("4").load().migrate();
			try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
				insert(s, "e1", "app.update", "mini_app", APP, "{}");
				insert(s, "e2", "key.register", "partner_key", "ak_test_x", "{\"appId\":\"" + APP + "\",\"environment\":\"DEV\"}");
				insert(s, "e3", "key.revoke", "partner_key", "ak_test_x", "{\"appId\":\"" + APP + "\"}");
				insert(s, "e4", "review.moderate", "app_review", "r1", "{\"appId\":\"" + APP + "\",\"status\":\"HIDDEN\"}");
				insert(s, "e5", "report.handle", "app_report", "p1", "{\"appId\":\"" + APP + "\",\"status\":\"REVIEWED\"}");
				insert(s, "e6", "staff.create", "staff", "s1", "{\"email\":\"a@b.c\"}");
				insert(s, "e7", "template.update", "template", "t1", "{\"appId\":\"not-a-uuid\"}");
				insert(s, "e8", "app.update", "mini_app", "not-a-uuid", "{}");

				Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

				Map<String, String> byId = new LinkedHashMap<>();
				try (ResultSet rs = s.executeQuery("SELECT CAST(id AS text) || '=' || COALESCE(CAST(app_id AS text), '-') FROM audit_event ORDER BY id")) {
					while (rs.next()) {
						String[] kv = rs.getString(1).split("=", 2);
						byId.put(kv[0], kv[1]);
					}
				}
				assertThat(byId.values()).filteredOn(APP::equals).hasSize(5);
				assertThat(byId.values()).filteredOn("-"::equals).as("staff entry, junk meta and junk target stay without an app").hasSize(3);
			}
		}
	}

	private static void insert(Statement s, String n, String action, String type, String target, String meta) throws Exception {
		String id = "00000000-0000-0000-0000-00000000" + String.format("%04d", Integer.parseInt(n.substring(1)));
		s.execute("INSERT INTO audit_event (id, action, target_type, target_id, meta, at) VALUES ('" + id + "', '" + action + "', '" + type + "', '" + target + "', '" + meta + "', now())");
	}

}
