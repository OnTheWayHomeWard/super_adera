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

/** V6 turns the old text rendering of a limit row in audit meta into real JSON fields. */
class LimitSetMetaMigrationTests {

	@Test
	void oldLimitSetEntriesBecomeStructured() throws Exception {
		try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			pg.start();
			Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("5").load().migrate();
			try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
				s.execute("INSERT INTO audit_event (id, action, target_type, target_id, meta, at) VALUES ('00000000-0000-0000-0000-000000000001', 'limit.set', 'mini_app', 'a', "
						+ "'{\"channel\":\"IN_APP\",\"limits\":\"LimitRow[channel=IN_APP, maxPerUserPerDay=20, maxPerDay=10000, maxDistinctUsersPerDay=10000, maxTotal=null]\"}', now())");
				s.execute("INSERT INTO audit_event (id, action, target_type, target_id, meta, at) VALUES ('00000000-0000-0000-0000-000000000002', 'limit.set', 'mini_app', 'a', "
						+ "'{\"channel\":\"SMS\",\"limits\":\"LimitRow[channel=SMS, maxPerUserPerDay=null, maxPerDay=5, maxDistinctUsersPerDay=null, maxTotal=1000]\"}', now())");
				s.execute("INSERT INTO audit_event (id, action, target_type, target_id, meta, at) VALUES ('00000000-0000-0000-0000-000000000003', 'app.update', 'mini_app', 'a', '{\"fields\":[\"name\"]}', now())");

				Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

				List<String> metas = new ArrayList<>();
				try (ResultSet rs = s.executeQuery("SELECT CAST(meta AS text) FROM audit_event ORDER BY id")) {
					while (rs.next()) {
						metas.add(rs.getString(1).replace(" ", ""));
					}
				}
				assertThat(metas.get(0)).contains("\"channel\":\"IN_APP\"", "\"maxPerUserPerDay\":20", "\"maxPerDay\":10000", "\"maxDistinctUsersPerDay\":10000", "\"maxTotal\":null").doesNotContain("LimitRow");
				assertThat(metas.get(1)).contains("\"channel\":\"SMS\"", "\"maxPerUserPerDay\":null", "\"maxPerDay\":5", "\"maxDistinctUsersPerDay\":null", "\"maxTotal\":1000");
				assertThat(metas.get(2)).isEqualTo("{\"fields\":[\"name\"]}");
			}
		}
	}

}
