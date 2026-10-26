package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import app.adera.notification.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The staff delivery log across apps: filters, paging, counts, and that no personal data leaks into the rows. */
@SpringBootTest(properties = { "adera.partner.workers-enabled=false", "adera.partner.gateway-secret=gw-secret",
		"adera.notification.dev-endpoints=true", "adera.notification.sms-provider=console", "adera.notification.email-provider=mailpit" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminNotificationQueryTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b) {
		return b.header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", "VIEWER");
	}

	private UUID insert(UUID app, String environment, String channel, String status, String kind, Instant createdAt) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO notification (id, app_id, environment, external_id, request_hash, user_id, user_alias, template_id, template_version,
				  params, data, kind, channel, status, attempts, next_attempt_at, expires_at, created_at, updated_at)
				VALUES (:id, :app, :env, :ext, 'h', :user, 'u_p_secretalias', :tpl, 1, CAST('{"name":"PRIVATE-NAME"}' AS jsonb), '{}'::jsonb,
				  :kind, :ch, :st, 0, :at, :at, :at, :at)""")
			.param("id", id).param("app", app).param("env", environment).param("ext", "e-" + id).param("user", UUID.randomUUID())
			.param("tpl", UUID.randomUUID()).param("kind", kind).param("ch", channel).param("st", status)
			.param("at", java.sql.Timestamp.from(createdAt)).update();
		return id;
	}

	@Test
	void listsAcrossAppsFiltersAndPagesWithoutPersonalData() throws Exception {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		Instant now = Instant.now();
		insert(a, "PROD", "SMS", "SENT", "DIRECT", now.minusSeconds(40));
		insert(a, "PROD", "EMAIL", "FAILED", "DIRECT", now.minusSeconds(30));
		insert(b, "DEV", "IN_APP", "SENT", "IN_APP", now.minusSeconds(20));
		insert(b, "PROD", "SMS", "UNREACHABLE", "DIRECT", now.minusSeconds(10));

		String all = mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("limit", "200")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(all).contains(a.toString(), b.toString()).doesNotContain("PRIVATE-NAME").doesNotContain("u_p_secretalias").doesNotContain("userId").doesNotContain("userAlias");

		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", b.toString())).andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].status").value("UNREACHABLE")).andExpect(jsonPath("$[0].appId").value(b.toString()));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", a.toString()).param("channel", "sms")).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", a.toString()).param("status", "failed")).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].channel").value("EMAIL"));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", b.toString()).param("environment", "dev")).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", b.toString()).param("kind", "IN_APP")).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", a.toString()).param("limit", "1").param("offset", "1"))
			.andExpect(header().string("X-Total-Count", "2")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].channel").value("SMS"));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", a.toString())
			.param("from", now.minusSeconds(35).toString()).param("to", now.toString())).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("from", "yesterday")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.from").exists());
	}

	@Test
	void statsCountEverythingTheLogHolds() throws Exception {
		UUID app = UUID.randomUUID();
		Instant now = Instant.now();
		insert(app, "PROD", "SMS", "SENT", "DIRECT", now.minusSeconds(60));
		insert(app, "PROD", "SMS", "SENT", "DIRECT", now.minusSeconds(50));
		insert(app, "PROD", "EMAIL", "FAILED", "DIRECT", now.minusSeconds(40));
		insert(app, "PROD", "EMAIL", "SENT", "DIRECT", now.minusSeconds(3 * 86_400));

		mvc.perform(staff(get("/admin/api/v1/notification/stats")).param("appId", app.toString())).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.byStatus.SENT").value(3)).andExpect(jsonPath("$.byStatus.FAILED").value(1))
			.andExpect(jsonPath("$.byChannel.SMS").value(2)).andExpect(jsonPath("$.byChannel.EMAIL").value(2))
			.andExpect(jsonPath("$.last24h.total").value(3)).andExpect(jsonPath("$.last24h.byStatus.SENT").value(2));
		// without appId it covers every app, so it is at least this app's rows
		String global = mvc.perform(staff(get("/admin/api/v1/notification/stats"))).andReturn().getResponse().getContentAsString();
		assertThat(((Number) com.jayway.jsonpath.JsonPath.read(global, "$.total")).longValue()).isGreaterThanOrEqualTo(4);
		mvc.perform(staff(get("/admin/api/v1/notification/stats")).param("appId", UUID.randomUUID().toString())).andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.last24h.total").value(0));
	}

	@Test
	void anUnknownFilterValueIsRefusedInsteadOfAnEmptyList() throws Exception {
		for (String param : new String[] { "status", "channel", "environment", "kind" }) {
			mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param(param, "bogus")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields." + param).exists());
		}
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("status", "sent").param("channel", "sms").param("environment", "dev").param("kind", "direct"))
			.andExpect(status().isOk());
	}

	@Test
	void usageUsesCamelCaseKeys() throws Exception {
		UUID app = UUID.randomUUID();
		jdbc.sql("INSERT INTO usage_total (app_id, environment, channel, total) VALUES (:a, 'DEV', 'SMS', 7)").param("a", app).update();
		for (int i = 0; i < 2; i++) {
			jdbc.sql("INSERT INTO usage_daily (app_id, environment, channel, day, user_id, sent) VALUES (:a, 'DEV', 'SMS', current_date, :u, 3)").param("a", app).param("u", UUID.randomUUID()).update();
		}
		String body = mvc.perform(staff(get("/admin/api/v1/notification/usage")).param("appId", app.toString()).param("environment", "DEV")).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].channel").value("SMS")).andExpect(jsonPath("$[0].lifetime").value(7)).andExpect(jsonPath("$[0].today").value(6))
			.andExpect(jsonPath("$[0].distinctUsersToday").value(2)).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("distinct_users_today").doesNotContain("_");
	}

}
