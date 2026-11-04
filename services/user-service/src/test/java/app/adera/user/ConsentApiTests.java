package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** Per-capability consent (docs section 14, section 5): the state machine, the one-time prompt, the menu, disconnect. */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=alias-test-secret" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConsentApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	KratosClient kratos;

	@MockitoBean
	RegistryAppClient registry;

	private final Map<String, AppInfo> apps = new HashMap<>();
	private UUID rideId;
	private UUID otherId;

	private static GrantedPermission perm(String cap, String label, Map<String, String> purpose, String dflt) {
		return new GrantedPermission(cap, label, purpose, dflt);
	}

	private AppInfo register(String slug, String status, List<GrantedPermission> perms) {
		AppInfo info = new AppInfo(UUID.randomUUID(), slug, Map.of("en", slug), status, perms);
		apps.put(info.appId().toString(), info);
		apps.put(slug, info);
		return info;
	}

	@BeforeEach
	void registryKnowsTheApps() {
		apps.clear();
		String s = UUID.randomUUID().toString().substring(0, 8);
		AppInfo ride = register("ride-" + s, "LIVE", List.of(
				perm("user.basic", "Your Adera id", Map.of(), "Recognise you in the app without learning who you are."),
				perm("user.full_name", "Your name", Map.of("en", "Greet you by name.", "am", "በስምዎ ለመጥራት።"), "Greet you by name."),
				perm("location.fine", "Your precise location", Map.of("en", "Find a driver near you."), "Use your exact position while the app is open."),
				perm("notify.sms", "Text messages (SMS)", Map.of(), "Send you text messages.")));
		rideId = ride.appId();
		otherId = register("other-" + s, "LIVE", List.of(perm("location.fine", "Your precise location", Map.of("en", "Show shops near you."), "x"))).appId();
		register("paused-" + s, "PAUSED", List.of(perm("location.fine", "Your precise location", Map.of("en", "Paused one."), "x")));
		register("killed-" + s, "KILLED", List.of(perm("location.fine", "Your precise location", Map.of("en", "Killed one."), "x")));
		register("draft-" + s, "DRAFT", List.of(perm("location.fine", "Your precise location", Map.of("en", "Draft one."), "x")));
		when(registry.app(any())).thenAnswer(inv -> Optional.ofNullable(apps.get(inv.<String>getArgument(0))));
		when(registry.apps(any())).thenAnswer(inv -> inv.<java.util.Collection<UUID>>getArgument(0).stream().map(id -> apps.get(id.toString())).filter(java.util.Objects::nonNull).toList());
	}

	private String slugOf(String prefix) {
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

	private ResultActions request(UUID user, Object app, String cap) throws Exception {
		return mvc.perform(asUser(post("/api/v1/me/apps/{a}/permissions/{c}/request", app, cap), user));
	}

	private ResultActions answer(UUID user, Object app, String cap, String promptId, String answer) throws Exception {
		return mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}/answer", app, cap), user).contentType(MediaType.APPLICATION_JSON)
			.content("{\"promptId\":\"" + promptId + "\",\"answer\":\"" + answer + "\"}"));
	}

	private ResultActions menu(UUID user, Object app, String cap, String state) throws Exception {
		return mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}", app, cap), user).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"" + state + "\"}"));
	}

	/** Asks and returns the prompt id (the dialog is shown). */
	private String prompt(UUID user, Object app, String cap) throws Exception {
		String body = request(user, app, cap).andExpect(status().isOk()).andExpect(jsonPath("$.prompt.promptId").isString()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.prompt.promptId");
	}

	private List<String> events(UUID user, UUID app, String cap) {
		return jdbc.sql("SELECT from_state || '>' || to_state || '/' || source FROM consent_event WHERE user_id = :u AND app_id = :a AND capability = :c ORDER BY id")
			.param("u", user).param("a", app).param("c", cap).query(String.class).list();
	}

	// ---------- listing ----------

	@Test
	void listShowsEveryGrantedCapabilityWithLabelPurposeAndThisUsersState() throws Exception {
		UUID u = newUser();
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", slugOf("ride-")), u))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(4))
			.andExpect(jsonPath("$[0].capability").value("user.basic"))
			.andExpect(jsonPath("$[0].state").value("GRANTED"))      // needs no dialog
			.andExpect(jsonPath("$[0].canAskAgain").value(false))
			.andExpect(jsonPath("$[2].capability").value("location.fine"))
			.andExpect(jsonPath("$[2].label").value("Your precise location"))
			.andExpect(jsonPath("$[2].purpose").value("Find a driver near you."))
			.andExpect(jsonPath("$[2].state").value("NOT_ASKED"))
			.andExpect(jsonPath("$[2].denialCount").value(0))
			.andExpect(jsonPath("$[2].canAskAgain").value(true))
			// no approved sentence: the catalog default is shown
			.andExpect(jsonPath("$[3].purpose").value("Send you text messages."));
		// by id works the same as by slug
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
	}

	@Test
	void purposeIsShownInTheUsersLanguageWithEnglishAsFallback() throws Exception {
		UUID u = newUser();
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(status().isOk());   // creates the profile
		jdbc.sql("UPDATE app_user SET language = 'am' WHERE id = :u").param("u", u).update();
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(jsonPath("$[1].purpose").value("በስምዎ ለመጥራት።"))
			.andExpect(jsonPath("$[2].purpose").value("Find a driver near you."));
	}

	@Test
	void notLiveOrUnknownAppsAreRefused() throws Exception {
		UUID u = newUser();
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", slugOf("paused-")), u)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("app_not_live"));
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", slugOf("killed-")), u)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("app_not_live"));
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", slugOf("draft-")), u)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", "nope"), u)).andExpect(status().isNotFound());
		request(u, slugOf("paused-"), "location.fine").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("app_not_live"));
		request(u, slugOf("killed-"), "location.fine").andExpect(status().isForbidden());
		request(u, slugOf("draft-"), "location.fine").andExpect(status().isNotFound());
		request(u, "nope", "location.fine").andExpect(status().isNotFound());
		answer(u, slugOf("paused-"), "location.fine", "pr_x", "GRANTED").andExpect(status().isForbidden());
		menu(u, slugOf("killed-"), "location.fine", "GRANTED").andExpect(status().isForbidden());
	}

	// ---------- the dialog ----------

	@Test
	void firstRequestShowsADialogAndGrantingRemembersIt() throws Exception {
		UUID u = newUser();
		request(u, rideId, "location.fine").andExpect(status().isOk())
			.andExpect(jsonPath("$.capability").value("location.fine"))
			.andExpect(jsonPath("$.state").value("NOT_ASKED"))
			.andExpect(jsonPath("$.prompt.promptId").value(org.hamcrest.Matchers.startsWith("pr_")))
			.andExpect(jsonPath("$.prompt.expiresInSeconds").value(300))
			.andExpect(jsonPath("$.prompt.label").value("Your precise location"))
			.andExpect(jsonPath("$.prompt.purpose").value("Find a driver near you."));
		String p = prompt(u, rideId, "location.fine");
		answer(u, rideId, "location.fine", p, "GRANTED").andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("GRANTED")).andExpect(jsonPath("$.denialCount").value(0)).andExpect(jsonPath("$.canAskAgain").value(false));
		// a granted answer is remembered: no dialog next time
		request(u, rideId, "location.fine").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GRANTED")).andExpect(jsonPath("$.prompt").isEmpty());
		assertThat(events(u, rideId, "location.fine")).containsExactly("NOT_ASKED>GRANTED/DIALOG");
	}

	@Test
	void twoDenialsInARowBlockForGood() throws Exception {
		UUID u = newUser();
		String p1 = prompt(u, rideId, "location.fine");
		answer(u, rideId, "location.fine", p1, "DENIED").andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DENIED")).andExpect(jsonPath("$.denialCount").value(1)).andExpect(jsonPath("$.canAskAgain").value(true));
		// denied once: the dialog may be shown one more time
		request(u, rideId, "location.fine").andExpect(jsonPath("$.state").value("DENIED")).andExpect(jsonPath("$.prompt.promptId").isString());
		String p2 = prompt(u, rideId, "location.fine");
		answer(u, rideId, "location.fine", p2, "DENIED").andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("BLOCKED")).andExpect(jsonPath("$.denialCount").value(2)).andExpect(jsonPath("$.canAskAgain").value(false));
		// blocked: no dialog, however often the app asks
		for (int i = 0; i < 3; i++) {
			request(u, rideId, "location.fine").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("BLOCKED")).andExpect(jsonPath("$.prompt").isEmpty());
		}
		assertThat(events(u, rideId, "location.fine")).containsExactly("NOT_ASKED>DENIED/DIALOG", "DENIED>BLOCKED/DIALOG");
	}

	@Test
	void aGrantAfterOneDenialResetsTheCount() throws Exception {
		UUID u = newUser();
		answer(u, rideId, "location.fine", prompt(u, rideId, "location.fine"), "DENIED").andExpect(jsonPath("$.denialCount").value(1));
		answer(u, rideId, "location.fine", prompt(u, rideId, "location.fine"), "GRANTED").andExpect(jsonPath("$.state").value("GRANTED")).andExpect(jsonPath("$.denialCount").value(0));
		// turning it off in the menu and denying once more starts counting from zero again
		menu(u, rideId, "location.fine", "DENIED").andExpect(jsonPath("$.denialCount").value(0));
		answer(u, rideId, "location.fine", prompt(u, rideId, "location.fine"), "DENIED").andExpect(jsonPath("$.state").value("DENIED")).andExpect(jsonPath("$.denialCount").value(1));
	}

	@Test
	void anAnswerWithoutAValidPromptChangesNothing() throws Exception {
		UUID u = newUser();
		// never asked: no ticket at all
		answer(u, rideId, "location.fine", "pr_made_up", "GRANTED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prompt_invalid"));
		String p = prompt(u, rideId, "location.fine");
		answer(u, rideId, "location.fine", "pr_not_the_one", "GRANTED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prompt_invalid"));
		request(u, rideId, "location.fine").andExpect(jsonPath("$.state").value("NOT_ASKED"));
		// the first (now replaced) ticket is dead: asking again issued a new one
		answer(u, rideId, "location.fine", p, "GRANTED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prompt_invalid"));
		assertThat(events(u, rideId, "location.fine")).isEmpty();
	}

	@Test
	void aPromptWorksOnlyOnce() throws Exception {
		UUID u = newUser();
		String p = prompt(u, rideId, "location.fine");
		answer(u, rideId, "location.fine", p, "DENIED").andExpect(status().isOk());
		// replaying the same ticket (even with the opposite answer) is refused and the stored state stays
		answer(u, rideId, "location.fine", p, "GRANTED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prompt_invalid"));
		answer(u, rideId, "location.fine", p, "DENIED").andExpect(status().isConflict());
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(jsonPath("$[2].state").value("DENIED")).andExpect(jsonPath("$[2].denialCount").value(1));
	}

	@Test
	void anExpiredPromptIsRefused() throws Exception {
		UUID u = newUser();
		String p = prompt(u, rideId, "location.fine");
		jdbc.sql("UPDATE app_consent SET prompt_expires_at = now() - interval '1 second' WHERE user_id = :u").param("u", u).update();
		answer(u, rideId, "location.fine", p, "GRANTED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prompt_expired"));
		request(u, rideId, "location.fine").andExpect(jsonPath("$.state").value("NOT_ASKED"));
		// the same expired id cannot come back to life
		answer(u, rideId, "location.fine", p, "GRANTED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prompt_invalid"));
	}

	@Test
	void aPromptIsTiedToItsUserAppAndCapability() throws Exception {
		UUID u = newUser();
		UUID stranger = newUser();
		String p = prompt(u, rideId, "location.fine");
		// another app (even one that grants the same capability)
		answer(u, otherId, "location.fine", p, "GRANTED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("prompt_invalid"));
		// another capability of the same app
		answer(u, rideId, "notify.sms", p, "GRANTED").andExpect(status().isConflict());
		// another user
		answer(stranger, rideId, "location.fine", p, "GRANTED").andExpect(status().isConflict());
		// and it still works for its owner afterwards
		answer(u, rideId, "location.fine", p, "GRANTED").andExpect(status().isOk());
	}

	@Test
	void aCapabilityStaffDidNotGrantIsNotAvailableAndNeverGetsADialog() throws Exception {
		UUID u = newUser();
		request(u, otherId, "notify.sms").andExpect(status().isOk()).andExpect(jsonPath("$.capability").value("notify.sms"))
			.andExpect(jsonPath("$.state").value("NOT_AVAILABLE")).andExpect(jsonPath("$.prompt").isEmpty());
		answer(u, otherId, "notify.sms", "pr_x", "GRANTED").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("capability_not_granted"));
		menu(u, otherId, "notify.sms", "GRANTED").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("capability_not_granted"));
		assertThat(jdbc.sql("SELECT count(*) FROM app_consent WHERE user_id = :u").param("u", u).query(Long.class).single()).isZero();
	}

	@Test
	void removingTheGrantMakesAnExistingConsentInertAndNotListed() throws Exception {
		UUID u = newUser();
		answer(u, rideId, "location.fine", prompt(u, rideId, "location.fine"), "GRANTED").andExpect(status().isOk());
		AppInfo old = apps.get(rideId.toString());
		AppInfo without = new AppInfo(old.appId(), old.slug(), old.name(), "LIVE", old.permissions().stream().filter(p -> !p.capability().equals("location.fine")).toList());
		apps.put(rideId.toString(), without);
		apps.put(old.slug(), without);
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(jsonPath("$.length()").value(3));
		request(u, rideId, "location.fine").andExpect(jsonPath("$.state").value("NOT_AVAILABLE"));
		// staff grant it again: the old yes is still there
		apps.put(rideId.toString(), old);
		apps.put(old.slug(), old);
		request(u, rideId, "location.fine").andExpect(jsonPath("$.state").value("GRANTED"));
	}

	// ---------- the menu ----------

	@Test
	void theMenuIsTheOnlyWayOutOfBlockedAndResetsTheCount() throws Exception {
		UUID u = newUser();
		answer(u, rideId, "notify.sms", prompt(u, rideId, "notify.sms"), "DENIED").andExpect(status().isOk());
		answer(u, rideId, "notify.sms", prompt(u, rideId, "notify.sms"), "DENIED").andExpect(jsonPath("$.state").value("BLOCKED"));
		request(u, rideId, "notify.sms").andExpect(jsonPath("$.state").value("BLOCKED")).andExpect(jsonPath("$.prompt").isEmpty());
		menu(u, rideId, "notify.sms", "GRANTED").andExpect(status().isOk())
			.andExpect(jsonPath("$.capability").value("notify.sms")).andExpect(jsonPath("$.state").value("GRANTED"))
			.andExpect(jsonPath("$.denialCount").value(0)).andExpect(jsonPath("$.decidedAt").isString());
		request(u, rideId, "notify.sms").andExpect(jsonPath("$.state").value("GRANTED"));
		// switching off in the menu gives DENIED with count 0: the app may ask again later, twice
		menu(u, rideId, "notify.sms", "DENIED").andExpect(jsonPath("$.state").value("DENIED")).andExpect(jsonPath("$.denialCount").value(0));
		request(u, rideId, "notify.sms").andExpect(jsonPath("$.prompt.promptId").isString());
		assertThat(events(u, rideId, "notify.sms")).containsExactly("NOT_ASKED>DENIED/DIALOG", "DENIED>BLOCKED/DIALOG", "BLOCKED>GRANTED/MENU", "GRANTED>DENIED/MENU");
	}

	@Test
	void theMenuCanSwitchOnSomethingNeverAsked() throws Exception {
		UUID u = newUser();
		menu(u, rideId, "location.fine", "GRANTED").andExpect(jsonPath("$.state").value("GRANTED"));
		assertThat(events(u, rideId, "location.fine")).containsExactly("NOT_ASKED>GRANTED/MENU");
	}

	@Test
	void switchingInTheMenuClosesAnOpenDialogTicket() throws Exception {
		UUID u = newUser();
		String p = prompt(u, rideId, "location.fine");
		menu(u, rideId, "location.fine", "DENIED").andExpect(status().isOk());
		answer(u, rideId, "location.fine", p, "GRANTED").andExpect(status().isConflict());
	}

	@Test
	void menuAndAnswerBodiesAreValidated() throws Exception {
		UUID u = newUser();
		menu(u, rideId, "location.fine", "BLOCKED").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.state").exists());
		menu(u, rideId, "location.fine", "NOT_ASKED").andExpect(status().isBadRequest());
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}", rideId, "location.fine"), u).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}/answer", rideId, "location.fine"), u).contentType(MediaType.APPLICATION_JSON).content("{\"answer\":\"GRANTED\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.promptId").exists());
		answer(u, rideId, "location.fine", "pr_x", "MAYBE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.answer").exists());
		request(u, rideId, "Not A Key!").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.capability").exists());
	}

	// ---------- user.basic ----------

	@Test
	void userBasicNeedsNoDialogAndIsReportedGranted() throws Exception {
		UUID u = newUser();
		request(u, rideId, "user.basic").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GRANTED")).andExpect(jsonPath("$.prompt").isEmpty());
		// it cannot be refused in a dialog or switched off in the menu (leaving the app is Disconnect)
		answer(u, rideId, "user.basic", "pr_x", "DENIED").andExpect(status().isConflict());
		menu(u, rideId, "user.basic", "DENIED").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("use_disconnect"));
		menu(u, rideId, "user.basic", "GRANTED").andExpect(status().isOk());
		// a user.basic that staff did not grant is simply not available
		request(u, otherId, "user.basic").andExpect(jsonPath("$.state").value("NOT_AVAILABLE"));
	}

	// ---------- disconnect ----------

	@Test
	void disconnectTurnsEverythingToDeniedAndIsRepeatable() throws Exception {
		UUID u = newUser();
		answer(u, rideId, "location.fine", prompt(u, rideId, "location.fine"), "GRANTED").andExpect(status().isOk());
		answer(u, rideId, "notify.sms", prompt(u, rideId, "notify.sms"), "DENIED").andExpect(status().isOk());
		answer(u, rideId, "notify.sms", prompt(u, rideId, "notify.sms"), "DENIED").andExpect(jsonPath("$.state").value("BLOCKED"));
		String open = prompt(u, rideId, "user.full_name");

		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(status().isOk())
			.andExpect(jsonPath("$.disconnected").value(true))
			.andExpect(jsonPath("$.changed.length()").value(3));   // location.fine, notify.sms and user.basic
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideId), u))
			.andExpect(jsonPath("$[0].state").value("DENIED"))     // user.basic: reachable again only by opening the app anew
			.andExpect(jsonPath("$[1].state").value("NOT_ASKED"))  // never asked stays never asked
			.andExpect(jsonPath("$[2].state").value("DENIED")).andExpect(jsonPath("$[2].denialCount").value(0))
			.andExpect(jsonPath("$[3].state").value("DENIED")).andExpect(jsonPath("$[3].denialCount").value(0));
		// an open dialog ticket does not survive it
		answer(u, rideId, "user.full_name", open, "GRANTED").andExpect(status().isConflict());
		// the app may ask again afterwards, with a dialog
		request(u, rideId, "location.fine").andExpect(jsonPath("$.state").value("DENIED")).andExpect(jsonPath("$.prompt.promptId").isString());
		request(u, rideId, "user.basic").andExpect(jsonPath("$.state").value("DENIED")).andExpect(jsonPath("$.prompt").isEmpty());
		assertThat(events(u, rideId, "notify.sms")).endsWith("BLOCKED>DENIED/DISCONNECT");
		// nothing left to change: still 200, nothing reported
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(status().isOk()).andExpect(jsonPath("$.changed.length()").value(0));
	}

	@Test
	void disconnectWorksOnAPausedAppButNotOnADraftOrUnknownOne() throws Exception {
		UUID u = newUser();
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", slugOf("paused-")), u)).andExpect(status().isOk());
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", slugOf("draft-")), u)).andExpect(status().isNotFound());
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", "nope"), u)).andExpect(status().isNotFound());
	}

	// ---------- the settings overview ----------

	@Test
	void overviewListsAppsWhereSomethingIsAllowedAndWhat() throws Exception {
		UUID u = newUser();
		mvc.perform(asUser(get("/api/v1/me/permissions"), u)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		answer(u, rideId, "location.fine", prompt(u, rideId, "location.fine"), "GRANTED").andExpect(status().isOk());
		answer(u, rideId, "notify.sms", prompt(u, rideId, "notify.sms"), "DENIED").andExpect(status().isOk());
		answer(u, otherId, "location.fine", prompt(u, otherId, "location.fine"), "DENIED").andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/me/permissions"), u)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].appId").value(rideId.toString()))
			.andExpect(jsonPath("$[0].slug").value(slugOf("ride-")))
			// what the app can see: the pseudonymous id (always on while connected) and the location, not the refused SMS
			.andExpect(jsonPath("$[0].permissions.length()").value(2))
			.andExpect(jsonPath("$[0].permissions[0].capability").value("user.basic"))
			.andExpect(jsonPath("$[0].permissions[1].capability").value("location.fine"))
			.andExpect(jsonPath("$[0].permissions[1].state").value("GRANTED"));
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/me/permissions"), u)).andExpect(jsonPath("$.length()").value(0));
	}

	// ---------- users ----------

	@Test
	void aSuspendedUserFailsTheGate() throws Exception {
		UUID u = newUser();
		answer(u, rideId, "location.fine", prompt(u, rideId, "location.fine"), "GRANTED").andExpect(status().isOk());
		jdbc.sql("UPDATE app_user SET status = 'SUSPENDED' WHERE id = :u").param("u", u).update();
		request(u, rideId, "location.fine").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("account_suspended"));
		menu(u, rideId, "location.fine", "GRANTED").andExpect(status().isForbidden());
		mvc.perform(asUser(get("/api/v1/me/apps/{a}/permissions", rideId), u)).andExpect(status().isForbidden());
	}

	@Test
	void consentIsPerUser() throws Exception {
		UUID a = newUser();
		UUID b = newUser();
		answer(a, rideId, "location.fine", prompt(a, rideId, "location.fine"), "GRANTED").andExpect(status().isOk());
		request(b, rideId, "location.fine").andExpect(jsonPath("$.state").value("NOT_ASKED"));
	}

}
