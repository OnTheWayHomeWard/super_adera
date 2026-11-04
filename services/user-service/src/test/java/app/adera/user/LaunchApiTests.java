package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import app.adera.user.admin.ApiException;
import app.adera.user.auth.KratosClient;
import app.adera.user.launch.LaunchSigner;
import app.adera.user.registry.RegistryAppClient;
import app.adera.user.registry.RegistryAppClient.AppInfo;
import app.adera.user.registry.RegistryAppClient.GrantedPermission;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** POST /api/v1/me/apps/{app}/launch (docs section 14, step 5) and the internal profile the partner endpoint reads. */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=alias-test-secret", "adera.user.internal-secret=int-secret" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LaunchApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	KratosClient kratos;

	@MockitoBean
	RegistryAppClient registry;

	@MockitoBean
	LaunchSigner signer;

	private final Map<String, AppInfo> apps = new HashMap<>();
	private UUID rideId;
	private String rideSlug;

	@BeforeEach
	void setUp() {
		apps.clear();
		String s = UUID.randomUUID().toString().substring(0, 8);
		List<GrantedPermission> perms = List.of(
				new GrantedPermission("user.basic", "Your Adera id", Map.of(), "x"),
				new GrantedPermission("user.full_name", "Your name", Map.of("en", "Greet you by name."), "x"),
				new GrantedPermission("location.fine", "Your precise location", Map.of("en", "Find a driver near you."), "x"));
		AppInfo ride = new AppInfo(UUID.randomUUID(), "ride-" + s, Map.of("en", "Ride"), "LIVE", "https://ride.example.com/app", perms);
		rideId = ride.appId();
		rideSlug = ride.slug();
		register(ride);
		register(new AppInfo(UUID.randomUUID(), "query-" + s, Map.of("en", "Q"), "LIVE", "https://q.example.com/app?lang=am", perms));
		register(new AppInfo(UUID.randomUUID(), "nourl-" + s, Map.of("en", "N"), "LIVE", null, perms));
		register(new AppInfo(UUID.randomUUID(), "paused-" + s, Map.of("en", "P"), "PAUSED", "https://p.example.com", perms));
		register(new AppInfo(UUID.randomUUID(), "draft-" + s, Map.of("en", "D"), "DRAFT", null, perms));
		when(registry.app(any())).thenAnswer(inv -> Optional.ofNullable(apps.get(inv.<String>getArgument(0))));
		when(signer.sign(any(), any(), anyList())).thenReturn(new LaunchSigner.Signed("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln", 300));
	}

	private void register(AppInfo a) {
		apps.put(a.appId().toString(), a);
		apps.put(a.slug(), a);
	}

	private String slugStartingWith(String prefix) {
		return apps.values().stream().filter(a -> a.slug().startsWith(prefix)).findFirst().orElseThrow().slug();
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

	private ResultActions launch(UUID user, Object app) throws Exception {
		return mvc.perform(asUser(post("/api/v1/me/apps/{a}/launch", app), user));
	}

	private void allow(UUID user, String capability) throws Exception {
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}", rideSlug, capability), user).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"GRANTED\"}")).andExpect(status().isOk());
	}

	// ---------- launch ----------

	@Test
	void launchGivesATokenTheUrlAndTheStatesWithoutAnyDialog() throws Exception {
		UUID u = newUser();
		launch(u, rideSlug).andExpect(status().isOk())
			.andExpect(jsonPath("$.launchToken").value("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln")).andExpect(jsonPath("$.expiresInSeconds").value(300))
			.andExpect(jsonPath("$.url").value("https://ride.example.com/app?launch=eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln"))
			.andExpect(jsonPath("$.consents.length()").value(3))
			.andExpect(jsonPath("$.consents[0].capability").value("user.basic")).andExpect(jsonPath("$.consents[0].state").value("GRANTED"))
			.andExpect(jsonPath("$.consents[2].capability").value("location.fine")).andExpect(jsonPath("$.consents[2].state").value("NOT_ASKED"))
			.andExpect(jsonPath("$.consents[2].prompt").doesNotExist());
		// launching created no prompt and no consent row for anything the user was not asked about
		assertThat(jdbc.sql("SELECT count(*) FROM app_consent WHERE user_id = :u AND prompt_id IS NOT NULL").param("u", u).query(Long.class).single()).isZero();
	}

	@Test
	void theTokenIsSignedForThePseudonymousIdAndTheCurrentlyGrantedScopes() throws Exception {
		UUID u = newUser();
		allow(u, "location.fine");
		launch(u, rideId.toString()).andExpect(status().isOk());
		ArgumentCaptor<String> alias = ArgumentCaptor.forClass(String.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> scopes = ArgumentCaptor.forClass(List.class);
		verify(signer).sign(eq(rideId), alias.capture(), scopes.capture());
		assertThat(alias.getValue()).matches("u_p_[0-9a-f]{32}").doesNotContain(u.toString());
		// only what is GRANTED now, in the capability order of the app; the refused/never asked are not in the token
		assertThat(scopes.getValue()).containsExactly("user.basic", "location.fine");
		// the alias is the one partners use: stored, resolvable
		assertThat(jdbc.sql("SELECT user_id FROM app_user_alias WHERE app_id = :a AND alias = :al").param("a", rideId).param("al", alias.getValue()).query(UUID.class).single()).isEqualTo(u);
	}

	@Test
	void aRevokedPermissionIsNotAScopeAtTheNextLaunch() throws Exception {
		UUID u = newUser();
		allow(u, "location.fine");
		allow(u, "user.full_name");
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}", rideSlug, "location.fine"), u).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DENIED\"}")).andExpect(status().isOk());
		launch(u, rideSlug).andExpect(status().isOk());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> scopes = ArgumentCaptor.forClass(List.class);
		verify(signer).sign(any(), any(), scopes.capture());
		assertThat(scopes.getValue()).containsExactly("user.basic", "user.full_name");
	}

	@Test
	void openingTheAppAgainReconnectsAfterADisconnect() throws Exception {
		UUID u = newUser();
		allow(u, "location.fine");
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", rideSlug), u)).andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideSlug), u)).andExpect(jsonPath("$[0].state").value("DENIED"));
		launch(u, rideSlug).andExpect(status().isOk()).andExpect(jsonPath("$.consents[0].state").value("GRANTED")).andExpect(jsonPath("$.consents[2].state").value("DENIED"));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> scopes = ArgumentCaptor.forClass(List.class);
		verify(signer).sign(any(), any(), scopes.capture());
		assertThat(scopes.getValue()).containsExactly("user.basic");   // location stays refused: only the menu or a dialog brings it back
		assertThat(jdbc.sql("SELECT from_state || '>' || to_state || '/' || source FROM consent_event WHERE user_id = :u AND capability = 'user.basic' ORDER BY id").param("u", u).query(String.class).list())
			.containsExactly("GRANTED>DENIED/DISCONNECT", "DENIED>GRANTED/LAUNCH");
	}

	@Test
	void theUrlKeepsAnExistingQueryAndIsNullWithoutAnEntryUrl() throws Exception {
		UUID u = newUser();
		launch(u, slugStartingWith("query-")).andExpect(jsonPath("$.url").value("https://q.example.com/app?lang=am&launch=eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1In0.c2ln"));
		launch(u, slugStartingWith("nourl-")).andExpect(status().isOk()).andExpect(jsonPath("$.url").isEmpty()).andExpect(jsonPath("$.launchToken").isString());
	}

	@Test
	void notLiveAppsInactiveUsersAndASignerOutageAreRefused() throws Exception {
		UUID u = newUser();
		launch(u, slugStartingWith("paused-")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("app_not_live"));
		launch(u, slugStartingWith("draft-")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
		launch(u, "nope").andExpect(status().isNotFound());
		launch(u, rideSlug).andExpect(status().isOk());   // creates the profile row
		jdbc.sql("UPDATE app_user SET status = 'SUSPENDED' WHERE id = :u").param("u", u).update();
		launch(u, rideSlug).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("account_suspended"));
		UUID v = newUser();
		when(signer.sign(any(), any(), anyList())).thenThrow(new ApiException(503, "service_unavailable"));
		launch(v, rideSlug).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("service_unavailable"));
	}

	// ---------- the internal profile that notification-service serves to partners ----------

	private ResultActions profile(UUID user, String caps) throws Exception {
		return mvc.perform(get("/internal/users/{id}/consented-profile", user).param("appId", rideId.toString()).param("capabilities", caps).header("X-Internal-Secret", "int-secret"));
	}

	private void fill(UUID user, String first, String last) {
		jdbc.sql("UPDATE app_user SET first_name = :f, last_name = :l, country_code = 'ET', region = 'Addis Ababa', city = 'Addis Ababa', latitude = 9.03, longitude = 38.74 WHERE id = :u")
			.param("f", first).param("l", last).param("u", user).update();
	}

	@Test
	void theInternalProfileReleasesValuesOnlyForGrantedCapabilities() throws Exception {
		UUID u = newUser();
		launch(u, rideSlug).andExpect(status().isOk());   // creates the profile row from the (mocked) identity
		fill(u, "Hana", "Tesfaye");
		jdbc.sql("UPDATE app_user SET email = 'hana@example.com' WHERE id = :u").param("u", u).update();
		allow(u, "user.full_name");
		profile(u, "user.basic,user.full_name,user.contact,user.address").andExpect(status().isOk())
			.andExpect(jsonPath("$.reachable").value(true))
			.andExpect(jsonPath("$.states['user.basic']").value("GRANTED")).andExpect(jsonPath("$.states['user.full_name']").value("GRANTED"))
			.andExpect(jsonPath("$.states['user.contact']").value("NOT_ASKED")).andExpect(jsonPath("$.states['user.address']").value("NOT_ASKED"))
			.andExpect(jsonPath("$.fields.fullName").value("Hana Tesfaye"))
			.andExpect(jsonPath("$.fields.phone").doesNotExist()).andExpect(jsonPath("$.fields.email").doesNotExist()).andExpect(jsonPath("$.fields.address").doesNotExist())
			.andExpect(jsonPath("$.fields.latitude").doesNotExist());
	}

	@Test
	void contactAndAddressShapesAndAbsentValues() throws Exception {
		UUID u = newUser();
		launch(u, rideSlug).andExpect(status().isOk());
		fill(u, "Dawit", null);
		jdbc.sql("UPDATE app_user SET email = NULL WHERE id = :u").param("u", u).update();
		// the test apps in setUp do not grant contact/address; the registry list passed in is what counts, so ask for them directly
		jdbc.sql("INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source) VALUES (:u, :a, 'user.contact', 'GRANTED', 0, now(), 'DIALOG')").param("u", u).param("a", rideId).update();
		jdbc.sql("INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source) VALUES (:u, :a, 'user.address', 'GRANTED', 0, now(), 'DIALOG')").param("u", u).param("a", rideId).update();
		profile(u, "user.full_name,user.contact,user.address").andExpect(status().isOk())
			.andExpect(jsonPath("$.fields.phone").isString()).andExpect(jsonPath("$.fields.email").doesNotExist())
			.andExpect(jsonPath("$.fields.address.countryCode").value("ET")).andExpect(jsonPath("$.fields.address.city").value("Addis Ababa")).andExpect(jsonPath("$.fields.address.latitude").doesNotExist());
		// a user without a name or place releases nothing for those even when allowed
		UUID v = newUser();
		launch(v, rideSlug).andExpect(status().isOk());
		jdbc.sql("INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source) VALUES (:u, :a, 'user.address', 'GRANTED', 0, now(), 'DIALOG')").param("u", v).param("a", rideId).update();
		profile(v, "user.address").andExpect(jsonPath("$.states['user.address']").value("GRANTED")).andExpect(jsonPath("$.fields.address").doesNotExist());
	}

	@Test
	void aDisconnectedUserShowsDeniedAndUnreachableUsersShowNothing() throws Exception {
		UUID u = newUser();
		launch(u, rideSlug).andExpect(status().isOk());
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", rideSlug), u)).andExpect(status().isOk());
		profile(u, "user.basic,user.full_name").andExpect(jsonPath("$.states['user.basic']").value("DENIED")).andExpect(jsonPath("$.fields.fullName").doesNotExist());
		jdbc.sql("UPDATE app_user SET status = 'SUSPENDED' WHERE id = :u").param("u", u).update();
		profile(u, "user.basic").andExpect(status().isOk()).andExpect(jsonPath("$.reachable").value(false)).andExpect(jsonPath("$.fields").isEmpty());
		profile(UUID.randomUUID(), "user.basic").andExpect(status().isNotFound());
	}

	@Test
	void theInternalProfileNeedsTheInternalSecretAndSaneInput() throws Exception {
		UUID u = newUser();
		mvc.perform(get("/internal/users/{id}/consented-profile", u).param("appId", rideId.toString()).param("capabilities", "user.basic")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/users/{id}/consented-profile", u).param("appId", rideId.toString()).param("capabilities", "bad key!").header("X-Internal-Secret", "int-secret")).andExpect(status().isBadRequest());
	}

}
