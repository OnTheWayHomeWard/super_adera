package app.adera.user;

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

/** V4: every existing mute becomes a DENIED notify consent and the old table goes away. */
class MuteMigrationTests {

	private static final String USER_A = "00000000-0000-0000-0000-00000000000a";
	private static final String USER_B = "00000000-0000-0000-0000-00000000000b";
	private static final String APP_1 = "00000000-0000-0000-0000-0000000000a1";
	private static final String APP_2 = "00000000-0000-0000-0000-0000000000a2";

	@Test
	void mutesBecomeDeniedConsentsWithProofAndNothingElseChanges() throws Exception {
		try (PostgreSQLContainer pg = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))) {
			pg.start();
			Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("3").load().migrate();
			try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
				for (String u : new String[] { USER_A, USER_B }) {
					s.execute("INSERT INTO app_user (id, created_at, updated_at) VALUES ('" + u + "', now(), now())");
				}
				s.execute("INSERT INTO app_notification_preference (user_id, app_id, mute_in_app, mute_sms, mute_email, updated_at) VALUES ('" + USER_A + "', '" + APP_1 + "', false, true, false, '2026-10-30T10:00:00Z')");
				s.execute("INSERT INTO app_notification_preference (user_id, app_id, mute_in_app, mute_sms, mute_email, updated_at) VALUES ('" + USER_A + "', '" + APP_2 + "', true, true, true, '2026-10-31T10:00:00Z')");
				s.execute("INSERT INTO app_notification_preference (user_id, app_id, mute_in_app, mute_sms, mute_email, updated_at) VALUES ('" + USER_B + "', '" + APP_1 + "', false, false, false, now())");
				// a user who also answered a dialog for some other capability keeps that untouched
				s.execute("INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source) VALUES ('" + USER_A + "', '" + APP_1 + "', 'location.fine', 'GRANTED', 0, now(), 'DIALOG')");

				Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

				List<String> consents = new ArrayList<>();
				try (ResultSet rs = s.executeQuery("SELECT right(CAST(user_id AS text), 1) || ' ' || right(CAST(app_id AS text), 2) || ' ' || capability || ' ' || state || ' ' || denial_count || ' ' || source FROM app_consent ORDER BY 1")) {
					while (rs.next()) {
						consents.add(rs.getString(1));
					}
				}
				assertThat(consents).containsExactlyInAnyOrder(
						"a a1 location.fine GRANTED 0 DIALOG",
						"a a1 notify.sms DENIED 0 MUTE",
						"a a2 notify.inapp DENIED 0 MUTE", "a a2 notify.sms DENIED 0 MUTE", "a a2 notify.email DENIED 0 MUTE");
				// user B muted nothing: no consent rows at all (NOT_ASKED)
				try (ResultSet rs = s.executeQuery("SELECT count(*) FROM app_consent WHERE user_id = '" + USER_B + "'")) {
					rs.next();
					assertThat(rs.getInt(1)).isZero();
				}
				// proof rows keep the original time of the mute
				try (ResultSet rs = s.executeQuery("SELECT count(*) FROM consent_event WHERE source = 'MUTE' AND from_state = 'NOT_ASKED' AND to_state = 'DENIED' AND at = '2026-10-31T10:00:00Z'")) {
					rs.next();
					assertThat(rs.getInt(1)).isEqualTo(3);
				}
				try (ResultSet rs = s.executeQuery("SELECT decided_at = '2026-10-30T10:00:00Z' FROM app_consent WHERE capability = 'notify.sms' AND app_id = '" + APP_1 + "'")) {
					rs.next();
					assertThat(rs.getBoolean(1)).isTrue();
				}
				// the old table is gone
				try (ResultSet rs = s.executeQuery("SELECT to_regclass('app_notification_preference') IS NULL")) {
					rs.next();
					assertThat(rs.getBoolean(1)).isTrue();
				}
			}
		}
	}

}
