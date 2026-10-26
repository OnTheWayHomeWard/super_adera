package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V3 renames TEST/LIVE to DEV/PROD in place (notifications, usage counters, queued callbacks) and keeps every row. */
class EnvironmentMigrationTests {

	private static final String APP = "00000000-0000-0000-0000-000000000001";

	@Test
	void oldTestAndLiveRowsBecomeDevAndProd() throws Exception {
		try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			pg.start();
			Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("2").load().migrate();
			try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
				for (String[] n : new String[][] { { "10000000-0000-0000-0000-000000000001", "TEST" }, { "10000000-0000-0000-0000-000000000002", "LIVE" } }) {
					s.execute("""
							INSERT INTO notification (id, app_id, environment, external_id, request_hash, user_id, user_alias, template_id, template_version,
							  kind, channel, status, next_attempt_at, expires_at, created_at, updated_at, callback_url)
							VALUES ('%s', '%s', '%s', 'ext-%s', 'h', '%s', 'u_p_x', '%s', 1, 'DIRECT', 'SMS', 'SENT', now(), now(), now(), now(), 'https://x.test/cb')"""
						.formatted(n[0], APP, n[1], n[1], APP, APP));
					s.execute("INSERT INTO callback (id, notification_id, url, payload, next_attempt_at, created_at, updated_at) VALUES ('%s', '%s', 'https://x.test/cb', '{\"environment\":\"%s\"}', now(), now(), now())"
						.formatted(n[0].replace("1000", "2000"), n[0], n[1]));
					s.execute("INSERT INTO usage_daily VALUES ('%s', '%s', 'SMS', current_date, '%s', 3)".formatted(APP, n[1], APP));
					s.execute("INSERT INTO usage_total VALUES ('%s', '%s', 'SMS', 7)".formatted(APP, n[1]));
				}

				Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

				assertThat(column(s, "SELECT environment || ':' || external_id FROM notification ORDER BY environment")).containsExactly("DEV:ext-TEST", "PROD:ext-LIVE");
				assertThat(column(s, "SELECT environment || ':' || sent FROM usage_daily ORDER BY environment")).containsExactly("DEV:3", "PROD:3");
				assertThat(column(s, "SELECT environment || ':' || total FROM usage_total ORDER BY environment")).containsExactly("DEV:7", "PROD:7");
				assertThat(column(s, "SELECT payload->>'environment' FROM callback ORDER BY 1")).containsExactly("DEV", "PROD");
				assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE usage_total SET environment = 'TEST'"));
				assertThrows(java.sql.SQLException.class, () -> s.execute("UPDATE notification SET environment = 'LIVE'"));
			}
		}
	}

	@Test
	void theDeploymentSettingMustBeDevOrProd() {
		assertThat(props("dev").environment()).isEqualTo("DEV");
		assertThat(props(" Prod ").environment()).isEqualTo("PROD");
		assertThat(props("prod").isDev()).isFalse();
		assertThrows(IllegalStateException.class, () -> props("staging"));
		assertThrows(IllegalStateException.class, () -> props("test"));
		assertThrows(IllegalStateException.class, () -> props(""));
	}

	private static PartnerProperties props(String env) {
		return new PartnerProperties(env, true, "", "", "", "", "", "", 5, false, "fake", "", "");
	}

	private static List<String> column(Statement s, String sql) throws Exception {
		List<String> out = new ArrayList<>();
		try (ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

}
