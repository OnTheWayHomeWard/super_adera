package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.user.admin.ApiException;
import app.adera.user.admin.AuditClient;
import app.adera.user.auth.KratosClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** Staff view of registered users: search, masking, status changes (audited), and what a suspended user can no longer do. */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=alias-test-secret",
		"adera.user.internal-secret=int-secret", "adera.user.gateway-secret=gw-secret" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminUsersTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	KratosClient kratos;

	@MockitoBean
	AuditClient audit;

	private static final UUID STAFF = UUID.randomUUID();

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, String role) {
		return request.header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", role);
	}

	private static String tag() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	/** Inserts a user with realistic data; returns the id. */
	private UUID user(String first, String last, String email, String phone) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO app_user (id, email, phone_e164, first_name, last_name, gender, date_of_birth, extra, country_code, region, city,
				                      latitude, longitude, location_updated_at, created_at, updated_at)
				VALUES (:id, :email, :phone, :first, :last, 'FEMALE', DATE '1990-05-17', CAST('{"likes":"tea","tier":3}' AS jsonb),
				        'ET', 'Addis Ababa', 'Addis Ababa', 9.03, 38.74, now(), now(), now())""")
			.param("id", id).param("email", email).param("phone", phone).param("first", first).param("last", last).update();
		return id;
	}

	// ---------- trust and roles ----------

	@Test
	void theAdminApiNeedsTheGatewaySecretAndWellFormedStaffHeaders() throws Exception {
		mvc.perform(get("/admin/api/v1/users").header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", "ADMIN")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/api/v1/users").header("X-Gateway-Secret", "gw-secret")).andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/api/v1/users").header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", "nope").header("X-Staff-Role", "ADMIN"))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/admin/api/v1/users").header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", "GOD"))
			.andExpect(status().isUnauthorized());
		mvc.perform(as(get("/admin/api/v1/users"), "VIEWER")).andExpect(status().isOk());
	}

	// ---------- list and search ----------

	@Test
	void searchFindsByNameEmailPhoneAndIdAndPagesWithATotal() throws Exception {
		String t = tag();
		UUID a = user("Selam" + t, "Bekele", "selam." + t + "@example.com", "+251911" + (100000 + Math.abs(t.hashCode()) % 899999));
		UUID b = user("Dawit" + t, "Tesfaye", "dawit." + t + "@example.com", null);
		mvc.perform(as(get("/admin/api/v1/users").param("q", "selam" + t.toUpperCase()), "ADMIN")).andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].id").value(a.toString()))
			.andExpect(jsonPath("$.items[0].email").value("selam." + t + "@example.com")).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(25));
		mvc.perform(as(get("/admin/api/v1/users").param("q", "Dawit" + t + " Tes"), "ADMIN")).andExpect(jsonPath("$.items[0].id").value(b.toString()));
		mvc.perform(as(get("/admin/api/v1/users").param("q", b.toString()), "ADMIN")).andExpect(jsonPath("$.total").value(1));
		mvc.perform(as(get("/admin/api/v1/users").param("q", t), "ADMIN")).andExpect(jsonPath("$.total").value(2));
		mvc.perform(as(get("/admin/api/v1/users").param("q", t).param("size", "1").param("page", "1"), "ADMIN"))
			.andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.size").value(1));
		// LIKE wildcards are plain characters
		mvc.perform(as(get("/admin/api/v1/users").param("q", "%"), "ADMIN")).andExpect(jsonPath("$.total").value(0));
		mvc.perform(as(get("/admin/api/v1/users").param("q", "s_lam" + t), "ADMIN")).andExpect(jsonPath("$.total").value(0));
		// phone typed with spaces and dashes
		jdbc.sql("UPDATE app_user SET phone_e164 = '+251912345678' WHERE id = :id").param("id", b).update();
		jdbc.sql("UPDATE app_user SET phone_e164 = NULL WHERE phone_e164 = '+251912345678' AND id <> :id").param("id", b).update();
		mvc.perform(as(get("/admin/api/v1/users").param("q", "251 91-2345"), "ADMIN")).andExpect(jsonPath("$.items[0].id").value(b.toString()));
		mvc.perform(as(get("/admin/api/v1/users").param("q", t).param("status", "SUSPENDED"), "ADMIN")).andExpect(jsonPath("$.total").value(0));
		mvc.perform(as(get("/admin/api/v1/users").param("status", "bogus"), "ADMIN")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.status").exists());
		mvc.perform(as(get("/admin/api/v1/users").param("size", "5000"), "ADMIN")).andExpect(jsonPath("$.size").value(100));
	}

	@Test
	void viewersGetMaskedContactDetailsAndSearchOnlyByNameOrId() throws Exception {
		String t = tag();
		UUID id = user("Hana" + t, "Girma", "hana." + t + "@example.com", "+251911234567");
		mvc.perform(as(get("/admin/api/v1/users").param("q", "Hana" + t + " Gir"), "VIEWER")).andExpect(jsonPath("$.items[0].id").value(id.toString()))
			.andExpect(jsonPath("$.items[0].email").value("h***@e***.com")).andExpect(jsonPath("$.items[0].phone").value("+251*****4567"));
		mvc.perform(as(get("/admin/api/v1/users").param("q", id.toString()), "VIEWER")).andExpect(jsonPath("$.total").value(1));
		// contact details are masked for a VIEWER, so searching them would reveal them: it finds nothing
		for (String q : new String[] { "hana." + t, "hana." + t + "@example.com", "+251911234567", "251 91-1234", "911234567" }) {
			mvc.perform(as(get("/admin/api/v1/users").param("q", q), "VIEWER")).andExpect(jsonPath("$.total").value(0));
		}
		// an ADMIN can see the full value, so can search it
		mvc.perform(as(get("/admin/api/v1/users").param("q", "hana." + t), "ADMIN")).andExpect(jsonPath("$.items[0].id").value(id.toString()));
		mvc.perform(as(get("/admin/api/v1/users/{id}", id), "VIEWER")).andExpect(status().isOk())
			.andExpect(jsonPath("$.masked").value(true)).andExpect(jsonPath("$.email").value("h***@e***.com")).andExpect(jsonPath("$.phone").value("+251*****4567"))
			.andExpect(jsonPath("$.dateOfBirth").value((Object) null)).andExpect(jsonPath("$.gender").value((Object) null))
			.andExpect(jsonPath("$.extra").value((Object) null)).andExpect(jsonPath("$.extraKeys[0]").value("likes"));
		mvc.perform(as(get("/admin/api/v1/users/{id}", id), "ADMIN")).andExpect(jsonPath("$.masked").value(false))
			.andExpect(jsonPath("$.email").value("hana." + t + "@example.com")).andExpect(jsonPath("$.phone").value("+251911234567"))
			.andExpect(jsonPath("$.dateOfBirth").value("1990-05-17")).andExpect(jsonPath("$.gender").value("FEMALE"))
			.andExpect(jsonPath("$.extra.likes").value("tea")).andExpect(jsonPath("$.extra.tier").value(3));
	}

	// ---------- detail ----------

	@Test
	void detailSummarisesLocationDevicesAliasesAndMutesWithoutTokensOrCoordinates() throws Exception {
		UUID id = user("Lemma", "Abebe", "l." + tag() + "@example.com", null);
		UUID app = UUID.randomUUID();
		jdbc.sql("INSERT INTO device (id, user_id, platform, fcm_token, is_active, created_at, last_seen_at, token_updated_at) VALUES (:d, :u, 'ANDROID', 'SECRET-TOKEN-1', true, now(), now(), now())")
			.param("d", UUID.randomUUID()).param("u", id).update();
		jdbc.sql("INSERT INTO device (id, user_id, platform, fcm_token, is_active, created_at, last_seen_at, token_updated_at) VALUES (:d, :u, 'IOS', 'SECRET-TOKEN-2', false, now(), now(), now())")
			.param("d", UUID.randomUUID()).param("u", id).update();
		jdbc.sql("INSERT INTO app_user_alias (app_id, alias, user_id, created_at) VALUES (:a, 'u_p_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', :u, now())")
			.param("a", app).param("u", id).update();
		jdbc.sql("INSERT INTO app_notification_preference (user_id, app_id, mute_in_app, mute_sms, mute_email, updated_at) VALUES (:u, :a, false, true, false, now())")
			.param("u", id).param("a", app).update();

		String body = mvc.perform(as(get("/admin/api/v1/users/{id}", id), "ADMIN")).andExpect(status().isOk())
			.andExpect(jsonPath("$.location.city").value("Addis Ababa")).andExpect(jsonPath("$.location.hasPreciseLocation").value(true))
			.andExpect(jsonPath("$.devices.active").value(1)).andExpect(jsonPath("$.devices.total").value(2))
			.andExpect(jsonPath("$.devices.platforms.length()").value(1)).andExpect(jsonPath("$.devices.platforms[0]").value("ANDROID"))
			.andExpect(jsonPath("$.aliasCount").value(1))
			.andExpect(jsonPath("$.mutePreferences[0].appId").value(app.toString())).andExpect(jsonPath("$.mutePreferences[0].muteSms").value(true))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("SECRET-TOKEN").doesNotContain("u_p_").doesNotContain("9.03").doesNotContain("38.74")
			.doesNotContain("latitude").doesNotContain("fcm");
		mvc.perform(as(get("/admin/api/v1/users/{id}", UUID.randomUUID()), "ADMIN")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
	}

	@Test
	void statsCountRealRows() throws Exception {
		mvc.perform(as(get("/admin/api/v1/users/stats"), "VIEWER")).andExpect(status().isOk()).andExpect(jsonPath("$.total").isNumber())
			.andExpect(jsonPath("$.createdLast7Days").isNumber());
		long before = ((Number) com.jayway.jsonpath.JsonPath.read(mvc.perform(as(get("/admin/api/v1/users/stats"), "VIEWER")).andReturn().getResponse().getContentAsString(), "$.active")).longValue();
		user("Stat", "User", "stat." + tag() + "@example.com", null);
		mvc.perform(as(get("/admin/api/v1/users/stats"), "VIEWER")).andExpect(jsonPath("$.active").value(before + 1));
	}

	// ---------- status changes ----------

	@Test
	void suspendingIsAuditedRevokesSessionsAndBlocksTheGatewayVerifyAndDelivery() throws Exception {
		UUID id = user("Sus", "Pect", "sus." + tag() + "@example.com", "+251911000111");
		when(kratos.whoami(eq("Bearer sus"), any())).thenReturn(Optional.of(id));
		mvc.perform(get("/internal/auth/verify").header("X-Gateway-Secret", "gw-secret").header("Authorization", "Bearer sus")).andExpect(status().isOk());

		// reason is required, role ADMIN is required
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reason").exists());
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "VIEWER").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\",\"reason\":\"fraud reports\"}"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("insufficient_role"));
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WEIRD\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.status").exists());
		verify(audit, never()).record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").header("X-Client-IP", "203.0.113.7").contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"SUSPENDED\",\"reason\":\"fraud reports\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.email").exists());
		verify(audit).record(eq(STAFF.toString()), eq("ADMIN"), eq("user.status"), eq("app_user"), eq(id.toString()),
				eq(Map.<String, Object>of("from", "ACTIVE", "to", "SUSPENDED", "reason", "fraud reports")), eq("203.0.113.7"));
		verify(kratos).revokeSessions(id);

		// a suspended user cannot pass the gateway check, with a reason the app can show
		mvc.perform(get("/internal/auth/verify").header("X-Gateway-Secret", "gw-secret").header("Authorization", "Bearer sus"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("account_suspended")).andExpect(header().doesNotExist("X-User-Id"));
		// and the partner platform sees them as unreachable
		mvc.perform(get("/internal/users/{id}/delivery-context", id).header("X-Internal-Secret", "int-secret").param("appId", UUID.randomUUID().toString()))
			.andExpect(jsonPath("$.reachable").value(false)).andExpect(jsonPath("$.phone").doesNotExist()).andExpect(jsonPath("$.email").doesNotExist());

		// same status again: 200, nothing new audited
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\",\"reason\":\"again\"}"))
			.andExpect(status().isOk());
		verify(audit, org.mockito.Mockito.times(1)).record(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());

		// reactivate: no reason needed
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(get("/internal/auth/verify").header("X-Gateway-Secret", "gw-secret").header("Authorization", "Bearer sus")).andExpect(status().isOk());
		mvc.perform(get("/internal/users/{id}/delivery-context", id).header("X-Internal-Secret", "int-secret").param("appId", UUID.randomUUID().toString()))
			.andExpect(jsonPath("$.reachable").value(true));
	}

	@Test
	void markingDeletedIsFinalAndBlocksEverything() throws Exception {
		UUID id = user("Del", "Eted", "del." + tag() + "@example.com", null);
		when(kratos.whoami(eq("Bearer del"), any())).thenReturn(Optional.of(id));
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "SUPER_ADMIN").contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"DELETED\",\"reason\":\"requested by the user\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED")).andExpect(jsonPath("$.deletedAt").isString());
		mvc.perform(get("/internal/auth/verify").header("X-Gateway-Secret", "gw-secret").header("Authorization", "Bearer del"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("account_deleted"));
		mvc.perform(get("/internal/users/{id}/delivery-context", id).header("X-Internal-Secret", "int-secret").param("appId", UUID.randomUUID().toString()))
			.andExpect(jsonPath("$.reachable").value(false));
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("user_deleted"));
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\",\"reason\":\"x y z\"}"))
			.andExpect(status().isConflict());
		mvc.perform(as(get("/admin/api/v1/users/{id}", id), "ADMIN")).andExpect(jsonPath("$.status").value("DELETED"));
		mvc.perform(as(get("/admin/api/v1/users").param("status", "deleted").param("q", id.toString()), "ADMIN")).andExpect(jsonPath("$.total").value(1));
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", UUID.randomUUID()), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	void ifTheAuditEntryCannotBeWrittenTheChangeIsRolledBack() throws Exception {
		UUID id = user("Roll", "Back", "roll." + tag() + "@example.com", null);
		org.mockito.Mockito.doThrow(new ApiException(503, "audit_unavailable")).when(audit).record(anyString(), anyString(), anyString(), anyString(), eq(id.toString()), any(), any());
		mvc.perform(as(post("/admin/api/v1/users/{id}/status", id), "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\",\"reason\":\"testing\"}"))
			.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("audit_unavailable"));
		assertThat(jdbc.sql("SELECT status FROM app_user WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("ACTIVE");
		verify(kratos, never()).revokeSessions(id);
	}

	@Test
	void aUserWithoutAProfileRowYetCanStillPassTheGateway() throws Exception {
		UUID id = UUID.randomUUID();
		when(kratos.whoami(eq("Bearer fresh"), any())).thenReturn(Optional.of(id));
		mvc.perform(get("/internal/auth/verify").header("X-Gateway-Secret", "gw-secret").header("Authorization", "Bearer fresh"))
			.andExpect(status().isOk()).andExpect(header().string("X-User-Id", id.toString()));
	}

}
