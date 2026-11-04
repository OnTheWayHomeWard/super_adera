package app.adera.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.user.admin.AuditClient;
import app.adera.user.auth.KratosClient;
import app.adera.user.registry.RegistryAppClient;
import app.adera.user.registry.RegistryAppClient.AppInfo;
import app.adera.user.registry.RegistryAppClient.GrantedPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** GET /admin/api/v1/apps/{id}/permissions: per capability, how many users granted, denied or were blocked. Counts only. */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=alias-test-secret", "adera.user.gateway-secret=gw-secret" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminConsentCountsTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	KratosClient kratos;

	@MockitoBean
	AuditClient audit;

	@MockitoBean
	RegistryAppClient registry;

	private final UUID appId = UUID.randomUUID();
	private final String slug = "counts-" + appId.toString().substring(0, 8);

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, String role) {
		return request.header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", role);
	}

	@BeforeEach
	void registryKnowsTheApp() {
		AppInfo info = new AppInfo(appId, slug, Map.of("en", "Counts"), "LIVE", List.of(
				new GrantedPermission("user.basic", "Your Adera id", Map.of(), "x"),
				new GrantedPermission("location.fine", "Your precise location", Map.of("en", "Find a driver near you."), "x"),
				new GrantedPermission("notify.sms", "Text messages (SMS)", Map.of(), "x")));
		when(registry.app(any())).thenAnswer(inv -> {
			String key = inv.getArgument(0);
			return key.equals(appId.toString()) || key.equals(slug) ? Optional.of(info) : Optional.empty();
		});
	}

	private UUID user(String status) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO app_user (id, status, created_at, updated_at) VALUES (:id, :s, now(), now())").param("id", id).param("s", status).update();
		return id;
	}

	private void consent(UUID user, String capability, String state, int count) {
		jdbc.sql("INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source) VALUES (:u, :a, :c, :s, :n, now(), 'DIALOG')")
			.param("u", user).param("a", appId).param("c", capability).param("s", state).param("n", count).update();
	}

	private void alias(UUID user, String alias) {
		jdbc.sql("INSERT INTO app_user_alias (app_id, alias, user_id, created_at) VALUES (:a, :al, :u, now())").param("a", appId).param("al", alias).param("u", user).update();
	}

	@Test
	void countsPerGrantedCapabilityAndNothingElse() throws Exception {
		UUID a = user("ACTIVE"), b = user("ACTIVE"), c = user("ACTIVE"), d = user("SUSPENDED"), gone = user("ACTIVE");
		jdbc.sql("UPDATE app_user SET deleted_at = now(), status = 'DELETED' WHERE id = :u").param("u", gone).update();
		consent(a, "location.fine", "GRANTED", 0);
		consent(b, "location.fine", "DENIED", 1);
		consent(c, "location.fine", "BLOCKED", 2);
		consent(d, "location.fine", "GRANTED", 0);          // a suspended user still counts
		consent(gone, "location.fine", "GRANTED", 0);       // a deleted one does not
		consent(a, "notify.sms", "GRANTED", 0);
		consent(b, "user.full_name", "GRANTED", 0);   // not granted to the app by staff: not listed
		consent(c, "notify.sms", "NOT_ASKED", 0);            // an open ticket only: counts as nothing

		mvc.perform(as(get("/admin/api/v1/apps/{id}/permissions", appId), "VIEWER")).andExpect(status().isOk())
			.andExpect(jsonPath("$.appId").value(appId.toString())).andExpect(jsonPath("$.slug").value(slug))
			.andExpect(jsonPath("$.capabilities.length()").value(3))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='location.fine')].granted").value(2))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='location.fine')].denied").value(1))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='location.fine')].blocked").value(1))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='location.fine')].label").value("Your precise location"))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='notify.sms')].granted").value(1))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='notify.sms')].denied").value(0))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='notify.sms')].blocked").value(0))
			// counts only: no user ids anywhere
			.andExpect(jsonPath("$.users").doesNotExist());
	}

	@Test
	void userBasicCountsUsersWithAnAliasMinusThoseWhoDisconnected() throws Exception {
		UUID a = user("ACTIVE"), b = user("ACTIVE"), c = user("ACTIVE");
		alias(a, "u_p_aaaa"); alias(b, "u_p_bbbb"); alias(c, "u_p_cccc");
		consent(c, "user.basic", "DENIED", 0);   // disconnected
		mvc.perform(as(get("/admin/api/v1/apps/{id}/permissions", slug), "ADMIN")).andExpect(status().isOk())
			.andExpect(jsonPath("$.capabilities[?(@.capability=='user.basic')].granted").value(2))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='user.basic')].denied").value(1))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='user.basic')].blocked").value(0));
	}

	@Test
	void anAppNobodyAnsweredYetShowsZeros() throws Exception {
		mvc.perform(as(get("/admin/api/v1/apps/{id}/permissions", appId), "SUPER_ADMIN")).andExpect(status().isOk())
			.andExpect(jsonPath("$.capabilities[?(@.capability=='location.fine')].granted").value(0));
	}

	@Test
	void unknownAppAndBadHeadersAreRefused() throws Exception {
		mvc.perform(as(get("/admin/api/v1/apps/{id}/permissions", UUID.randomUUID()), "VIEWER")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
		mvc.perform(get("/admin/api/v1/apps/{id}/permissions", appId)).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/api/v1/apps/{id}/permissions", appId).header("X-Gateway-Secret", "gw-secret")).andExpect(status().isUnauthorized());
	}

}
