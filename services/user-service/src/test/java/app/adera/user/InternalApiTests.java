package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import app.adera.user.auth.KratosClient;
import com.jayway.jsonpath.JsonPath;
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

/** The internal API other services use: pseudonymous aliases, delivery context, dead tokens; plus per-app muting. */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=alias-test-secret",
		"adera.user.internal-secret=int-secret" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InternalApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	KratosClient kratos;

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T internal(T request) {
		return request.header("X-Internal-Secret", "int-secret");
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T asUser(T request, UUID id) {
		return request.header("X-User-Id", id.toString());
	}

	private UUID newUser() {
		UUID id = UUID.randomUUID();
		String phone = "+2519" + (10_000_000 + new Random().nextInt(89_999_999));
		when(kratos.identity(id)).thenReturn(Optional.of(new KratosClient.Identity(id, "u-" + id + "@example.com", phone)));
		return id;
	}

	private String mint(UUID app, UUID user) throws Exception {
		String res = mvc.perform(internal(post("/internal/aliases")).contentType(MediaType.APPLICATION_JSON)
			.content("{\"appId\":\"" + app + "\",\"userId\":\"" + user + "\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(res, "$.alias");
	}

	// ---------- aliases ----------

	@Test
	void aliasesArePseudonymousStablePerAppAndResolvable() throws Exception {
		UUID user = newUser();
		UUID other = newUser();
		UUID appA = UUID.randomUUID();
		UUID appB = UUID.randomUUID();

		String a = mint(appA, user);
		assertThat(a).matches("u_p_[0-9a-f]{32}").doesNotContain(user.toString());
		assertThat(mint(appA, user)).as("minting twice gives the same alias").isEqualTo(a);
		assertThat(mint(appB, user)).as("a different alias in every app, so partners cannot join their data").isNotEqualTo(a);
		assertThat(mint(appA, other)).as("different people get different aliases").isNotEqualTo(a);

		mvc.perform(internal(get("/internal/aliases/resolve")).param("appId", appA.toString()).param("alias", a))
			.andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(user.toString()));
		// an alias only works for the app it was minted for
		mvc.perform(internal(get("/internal/aliases/resolve")).param("appId", appB.toString()).param("alias", a)).andExpect(status().isNotFound());
		mvc.perform(internal(get("/internal/aliases/resolve")).param("appId", appA.toString()).param("alias", "u_p_" + "0".repeat(32))).andExpect(status().isNotFound());
	}

	@Test
	void aliasesAreNotGuessableFromTheUserId() throws Exception {
		UUID user = newUser();
		UUID app = UUID.randomUUID();
		String alias = mint(app, user);
		assertThat(alias).doesNotContain(user.toString().replace("-", "").substring(0, 8));
		assertThat(alias.substring(4)).isNotEqualTo(user.toString().replace("-", ""));
	}

	// ---------- delivery context ----------

	@Test
	void deliveryContextTellsTheSenderHowToReachAnActiveUser() throws Exception {
		UUID user = newUser();
		UUID app = UUID.randomUUID();
		mvc.perform(asUser(put("/api/v1/me/devices"), user).contentType(MediaType.APPLICATION_JSON)
			.content("{\"fcmToken\":\"tok-ctx-" + user + "\",\"platform\":\"ANDROID\"}")).andExpect(status().isOk());

		mvc.perform(internal(get("/internal/users/{id}/delivery-context", user)).param("appId", app.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reachable").value(true))
			.andExpect(jsonPath("$.phone").exists())
			.andExpect(jsonPath("$.email").exists())
			.andExpect(jsonPath("$.language").value("en"))
			.andExpect(jsonPath("$.pushTokens[0]").value("tok-ctx-" + user))
			.andExpect(jsonPath("$.muted.muteSms").value(false));
	}

	@Test
	void suspendedOrDeletedUsersAreUnreachableAndExposeNothing() throws Exception {
		UUID app = UUID.randomUUID();
		UUID suspended = newUser();
		mvc.perform(asUser(get("/api/v1/me"), suspended)).andExpect(status().isOk());
		jdbc.sql("UPDATE app_user SET status = 'SUSPENDED' WHERE id = :id").param("id", suspended).update();
		mvc.perform(internal(get("/internal/users/{id}/delivery-context", suspended)).param("appId", app.toString()))
			.andExpect(jsonPath("$.reachable").value(false)).andExpect(jsonPath("$.phone").doesNotExist())
			.andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.pushTokens.length()").value(0));

		UUID deleted = newUser();
		mvc.perform(asUser(get("/api/v1/me"), deleted)).andExpect(status().isOk());
		jdbc.sql("UPDATE app_user SET deleted_at = now() WHERE id = :id").param("id", deleted).update();
		mvc.perform(internal(get("/internal/users/{id}/delivery-context", deleted)).param("appId", app.toString()))
			.andExpect(jsonPath("$.reachable").value(false)).andExpect(jsonPath("$.phone").doesNotExist());

		mvc.perform(internal(get("/internal/users/{id}/delivery-context", UUID.randomUUID())).param("appId", app.toString())).andExpect(status().isNotFound());
	}

	// ---------- muting an app ----------

	@Test
	void usersCanMuteChannelsPerAppAndTheSenderSeesIt() throws Exception {
		UUID user = newUser();
		UUID app = UUID.randomUUID();
		UUID otherApp = UUID.randomUUID();
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/preferences", app), user)).andExpect(jsonPath("$.muteSms").value(false)).andExpect(jsonPath("$.muteInApp").value(false));

		mvc.perform(asUser(put("/api/v1/me/apps/{a}/preferences", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"muteSms\":true}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.muteSms").value(true)).andExpect(jsonPath("$.muteEmail").value(false));
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/preferences", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"muteInApp\":true}"))
			.andExpect(jsonPath("$.muteInApp").value(true)).andExpect(jsonPath("$.muteSms").value(true));

		mvc.perform(internal(get("/internal/users/{id}/delivery-context", user)).param("appId", app.toString()))
			.andExpect(jsonPath("$.muted.muteSms").value(true)).andExpect(jsonPath("$.muted.muteInApp").value(true));
		mvc.perform(internal(get("/internal/users/{id}/delivery-context", user)).param("appId", otherApp.toString()))
			.andExpect(jsonPath("$.muted.muteSms").value(false));

		mvc.perform(asUser(put("/api/v1/me/apps/{a}/preferences", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"muteSms\":false}"))
			.andExpect(jsonPath("$.muteSms").value(false)).andExpect(jsonPath("$.muteInApp").value(true));
		for (String bad : new String[] { "{\"muteSms\":\"yes\"}", "{\"muteFax\":true}" }) {
			mvc.perform(asUser(put("/api/v1/me/apps/{a}/preferences", app), user).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
		}
	}

	// ---------- dead tokens ----------

	@Test
	void aDeadPushTokenIsDeactivatedAndNoLongerOffered() throws Exception {
		UUID user = newUser();
		UUID app = UUID.randomUUID();
		String token = "dead-" + UUID.randomUUID();
		mvc.perform(asUser(put("/api/v1/me/devices"), user).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\",\"platform\":\"IOS\"}")).andExpect(status().isOk());

		mvc.perform(internal(post("/internal/devices/deactivate")).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\"}"))
			.andExpect(jsonPath("$.deactivated").value(true));
		mvc.perform(internal(get("/internal/users/{id}/delivery-context", user)).param("appId", app.toString())).andExpect(jsonPath("$.pushTokens.length()").value(0));
		mvc.perform(asUser(get("/api/v1/me/devices"), user)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(internal(post("/internal/devices/deactivate")).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"never-seen\"}"))
			.andExpect(jsonPath("$.deactivated").value(false));

		// registering the same token again (the app got a fresh start) makes it live again
		mvc.perform(asUser(put("/api/v1/me/devices"), user).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\",\"platform\":\"IOS\"}")).andExpect(status().isOk());
		mvc.perform(internal(get("/internal/users/{id}/delivery-context", user)).param("appId", app.toString())).andExpect(jsonPath("$.pushTokens.length()").value(1));
	}

	// ---------- who may call it ----------

	@Test
	void internalEndpointsNeedTheInternalSecret() throws Exception {
		UUID user = newUser();
		UUID app = UUID.randomUUID();
		mvc.perform(post("/internal/aliases").contentType(MediaType.APPLICATION_JSON).content("{\"appId\":\"" + app + "\",\"userId\":\"" + user + "\"}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/aliases/resolve").param("appId", app.toString()).param("alias", "x")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/users/{id}/delivery-context", user).param("appId", app.toString())).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/devices/deactivate").contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"t\"}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/users/{id}/delivery-context", user).param("appId", app.toString()).header("X-Internal-Secret", "wrong")).andExpect(status().isUnauthorized());
		// the gateway secret is a different key and must not open the internal door
		mvc.perform(get("/internal/users/{id}/delivery-context", user).param("appId", app.toString()).header("X-Gateway-Secret", "int-secret")).andExpect(status().isUnauthorized());
	}

}
