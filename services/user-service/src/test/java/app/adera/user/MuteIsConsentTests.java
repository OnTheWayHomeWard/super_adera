package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import app.adera.user.auth.KratosClient;
import app.adera.user.registry.RegistryAppClient;
import app.adera.user.registry.RegistryAppClient.AppInfo;
import app.adera.user.registry.RegistryAppClient.GrantedPermission;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
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

/** The mute switches and the notify.* consents are the same data (docs section 14, step 4). */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=alias-test-secret", "adera.user.internal-secret=int-secret" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MuteIsConsentTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	KratosClient kratos;

	@MockitoBean
	RegistryAppClient registry;

	private final UUID appId = UUID.randomUUID();
	private final String slug = "mute-" + appId.toString().substring(0, 8);

	@BeforeEach
	void registryKnowsTheApp() {
		AppInfo info = new AppInfo(appId, slug, Map.of("en", "Mute"), "LIVE", List.of(
				new GrantedPermission("notify.sms", "Text messages (SMS)", Map.of(), "Send you text messages."),
				new GrantedPermission("notify.email", "Email", Map.of(), "Send you emails.")));
		when(registry.status(any())).thenAnswer(inv -> Optional.of("LIVE"));
		when(registry.app(any())).thenAnswer(inv -> inv.getArgument(0).equals(slug) || inv.getArgument(0).equals(appId.toString()) ? Optional.of(info) : Optional.empty());
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

	private void prefs(UUID user, String json) throws Exception {
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/preferences", appId), user).contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isOk());
	}

	private String state(UUID user, String capability) throws Exception {
		String body = mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", slug), user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> states = JsonPath.read(body, "$[?(@.capability=='" + capability + "')].state");
		return states.get(0);
	}

	@Test
	void muteIsDeniedAndUnmuteIsGrantedInThePermissionList() throws Exception {
		UUID user = newUser();
		assertThat(state(user, "notify.sms")).isEqualTo("NOT_ASKED");
		prefs(user, "{\"muteSms\":true}");
		assertThat(state(user, "notify.sms")).isEqualTo("DENIED");
		assertThat(state(user, "notify.email")).isEqualTo("NOT_ASKED");
		prefs(user, "{\"muteSms\":false}");
		assertThat(state(user, "notify.sms")).isEqualTo("GRANTED");
		// and the other way round: the permission menu switch shows in the old mute view
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}", slug, "notify.email"), user).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DENIED\"}")).andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/preferences", appId), user)).andExpect(jsonPath("$.muteEmail").value(true)).andExpect(jsonPath("$.muteSms").value(false))
			.andExpect(jsonPath("$.states['notify.email']").value("DENIED")).andExpect(jsonPath("$.states['notify.sms']").value("GRANTED"));
	}

	@Test
	void notAskedIsNotMutedButAlsoNotAllowedAndTheStatesFieldSaysSo() throws Exception {
		UUID user = newUser();
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/preferences", appId), user)).andExpect(status().isOk())
			.andExpect(jsonPath("$.muteInApp").value(false)).andExpect(jsonPath("$.muteSms").value(false)).andExpect(jsonPath("$.muteEmail").value(false))
			.andExpect(jsonPath("$.states['notify.inapp']").value("NOT_ASKED")).andExpect(jsonPath("$.states['notify.sms']").value("NOT_ASKED"));
	}

	@Test
	void aBlockedChannelCountsAsMutedAndTheMuteSwitchUnblocksIt() throws Exception {
		UUID user = newUser();
		for (int i = 0; i < 2; i++) {
			String body = mvc.perform(asUser(post("/api/v1/me/apps/{a}/permissions/{c}/request", slug, "notify.sms"), user)).andReturn().getResponse().getContentAsString();
			String prompt = JsonPath.read(body, "$.prompt.promptId");
			mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}/answer", slug, "notify.sms"), user).contentType(MediaType.APPLICATION_JSON)
				.content("{\"promptId\":\"" + prompt + "\",\"answer\":\"DENIED\"}")).andExpect(status().isOk());
		}
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/preferences", appId), user)).andExpect(jsonPath("$.muteSms").value(true)).andExpect(jsonPath("$.states['notify.sms']").value("BLOCKED"));
		prefs(user, "{\"muteSms\":false}");
		assertThat(state(user, "notify.sms")).isEqualTo("GRANTED");
	}

	@Test
	void theMuteSwitchesRecordProofLikeAnyOtherConsentChange() throws Exception {
		UUID user = newUser();
		prefs(user, "{\"muteSms\":true,\"muteInApp\":true}");
		prefs(user, "{\"muteSms\":false}");
		List<String> events = jdbc.sql("SELECT capability || ' ' || from_state || '>' || to_state || ' ' || source FROM consent_event WHERE user_id = :u AND app_id = :a ORDER BY id")
			.param("u", user).param("a", appId).query(String.class).list();
		assertThat(events).containsExactlyInAnyOrder("notify.sms NOT_ASKED>DENIED MENU", "notify.inapp NOT_ASKED>DENIED MENU", "notify.sms DENIED>GRANTED MENU");
	}

	@Test
	void muteWorksEvenForAChannelStaffDidNotGrantTheAppAndStaysInertUntilTheyDo() throws Exception {
		UUID user = newUser();
		prefs(user, "{\"muteInApp\":true}");       // notify.inapp is not among this app's grants in the mock
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/preferences", appId), user)).andExpect(jsonPath("$.muteInApp").value(true));
		assertThat(jdbc.sql("SELECT state FROM app_consent WHERE user_id = :u AND app_id = :a AND capability = 'notify.inapp'").param("u", user).param("a", appId).query(String.class).single()).isEqualTo("DENIED");
	}

	@Test
	void deliveryContextCarriesTheConsentsForTheSenderToEnforce() throws Exception {
		UUID user = newUser();
		prefs(user, "{\"muteSms\":true}");
		menuGrant(user, "notify.email");
		mvc.perform(get("/internal/users/{id}/delivery-context", user).param("appId", appId.toString()).header("X-Internal-Secret", "int-secret"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.consents['notify.sms']").value("DENIED")).andExpect(jsonPath("$.consents['notify.email']").value("GRANTED"))
			.andExpect(jsonPath("$.consents['notify.inapp']").value("NOT_ASKED"));
	}

	private void menuGrant(UUID user, String capability) throws Exception {
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}", slug, capability), user).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"GRANTED\"}")).andExpect(status().isOk());
	}

}
