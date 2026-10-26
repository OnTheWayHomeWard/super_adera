package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;

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

/** V2 renames TEST/LIVE to DEV/PROD in place, keeping every row (and key ids), and then only DEV/PROD are accepted. */
class EnvironmentMigrationTests {

	@Test
	void oldTestAndLiveRowsBecomeDevAndProdAndNothingIsLost() throws Exception {
		try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			pg.start();
			Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("1").load().migrate();
			try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
				s.execute("""
						INSERT INTO mini_app (id, slug, name, description, created_at, updated_at)
						VALUES ('00000000-0000-0000-0000-000000000001', 'old', '{"en":"Old"}', '{"en":"d"}', now(), now())""");
				s.execute("INSERT INTO app_environment VALUES ('00000000-0000-0000-0000-000000000001', 'TEST', '[\"10.0.0.1/32\"]', now())");
				s.execute("INSERT INTO app_environment VALUES ('00000000-0000-0000-0000-000000000001', 'LIVE', '[\"203.0.113.1/32\"]', now())");
				for (String[] k : new String[][] { { "ak_test_old", "TEST" }, { "ak_live_old", "LIVE" } }) {
					s.execute("INSERT INTO partner_key (key_id, app_id, environment, public_key_pem, fingerprint, created_at) VALUES ('" + k[0]
							+ "', '00000000-0000-0000-0000-000000000001', '" + k[1] + "', 'pem', 'fp', now())");
				}

				Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

				assertThat(column(s, "SELECT environment || ':' || allowed_cidrs::text FROM app_environment ORDER BY environment"))
					.containsExactly("DEV:[\"10.0.0.1/32\"]", "PROD:[\"203.0.113.1/32\"]");
				assertThat(column(s, "SELECT key_id || ':' || environment FROM partner_key ORDER BY key_id")).containsExactly("ak_live_old:PROD", "ak_test_old:DEV");
				// the new CHECK constraints refuse the old names
				org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
						() -> s.execute("INSERT INTO app_environment VALUES ('00000000-0000-0000-0000-000000000001', 'TEST', '[]', now())"));
				org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
						() -> s.execute("UPDATE partner_key SET environment = 'LIVE'"));
			}
		}
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
