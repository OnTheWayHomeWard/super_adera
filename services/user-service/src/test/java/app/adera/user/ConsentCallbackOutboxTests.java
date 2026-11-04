package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import app.adera.user.admin.ApiException;
import app.adera.user.alias.AliasService;
import app.adera.user.auth.KratosClient;
import app.adera.user.consent.ConsentCallbackDispatcher;
import app.adera.user.consent.ConsentEventClient;
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
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/** Step 6: every change of a user's permission, and a disconnect, is queued for the partner (user.consent_changed / user.disconnected). */
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=alias-test-secret", "adera.user.callbacks-enabled=false" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConsentCallbackOutboxTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired AliasService aliases;
	@Autowired ConsentCallbackDispatcher dispatcher;
	@MockitoBean KratosClient kratos;
	@MockitoBean RegistryAppClient registry;
	@MockitoBean LaunchSigner signer;
	@MockitoBean ConsentEventClient client;

	private UUID appId;
	private String slug;

	@BeforeEach
	void setUp() {
		slug = "cb-" + UUID.randomUUID().toString().substring(0, 8);
		AppInfo info = new AppInfo(UUID.randomUUID(), slug, Map.of("en", "CB"), "LIVE", "https://cb.example.com", List.of(
				new GrantedPermission("user.basic", "Your Adera id", Map.of(), "x"),
				new GrantedPermission("user.full_name", "Your name", Map.of("en", "Greet you by name."), "x"),
				new GrantedPermission("location.fine", "Your precise location", Map.of("en", "Find a driver near you."), "x"),
				new GrantedPermission("notify.sms", "SMS", Map.of(), "x")));
		appId = info.appId();
		when(registry.app(any())).thenAnswer(inv -> inv.getArgument(0).equals(slug) || inv.getArgument(0).equals(appId.toString()) ? Optional.of(info) : Optional.empty());
		when(registry.status(any())).thenReturn(Optional.of("LIVE"));
		when(signer.sign(any(), any(), any())).thenReturn(new LaunchSigner.Signed("a.b.c", 300));
		reset(client);
		jdbc.sql("DELETE FROM partner_event_outbox").update();
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T asUser(T request, UUID id) {
		return request.header("X-User-Id", id.toString());
	}

	/** A user who opened the app (so the partner knows an alias). */
	private UUID connectedUser() throws Exception {
		UUID id = UUID.randomUUID();
		String phone = "+2519" + (10_000_000 + new Random().nextInt(89_999_999));
		when(kratos.identity(id)).thenReturn(Optional.of(new KratosClient.Identity(id, "u-" + id + "@example.com", phone)));
		mvc.perform(asUser(post("/api/v1/me/apps/{a}/launch", slug), id)).andExpect(status().isOk());
		jdbc.sql("DELETE FROM partner_event_outbox").update();   // launching a first time is not a change of any permission
		return id;
	}

	private String request(UUID user, String cap) throws Exception {
		String b = mvc.perform(asUser(post("/api/v1/me/apps/{a}/permissions/{c}/request", slug, cap), user)).andReturn().getResponse().getContentAsString();
		return JsonPath.read(b, "$.prompt.promptId");
	}

	private void answer(UUID user, String cap, String answer) throws Exception {
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}/answer", slug, cap), user).contentType(MediaType.APPLICATION_JSON)
			.content("{\"promptId\":\"" + request(user, cap) + "\",\"answer\":\"" + answer + "\"}")).andExpect(status().isOk());
	}

	private void menu(UUID user, String cap, String state) throws Exception {
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/permissions/{c}", slug, cap), user).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"" + state + "\"}")).andExpect(status().isOk());
	}

	private List<String> queued() {
		return jdbc.sql("SELECT event || ' ' || coalesce(payload ->> 'capability', '-') || ' ' || coalesce(payload ->> 'state', '-') FROM partner_event_outbox ORDER BY id").query(String.class).list();
	}

	@Test
	void aDialogAnswerQueuesAConsentChangeWithTheNewState() throws Exception {
		UUID u = connectedUser();
		answer(u, "location.fine", "GRANTED");
		assertThat(queued()).containsExactly("user.consent_changed location.fine GRANTED");
		dispatcher.runOnce();
		ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
		verify(client).send(sent.capture());
		Map<String, Object> event = sent.getValue();
		assertThat(event.get("event")).isEqualTo("user.consent_changed");
		assertThat(event.get("appId")).isEqualTo(appId.toString());
		assertThat((String) event.get("alias")).matches("u_p_[0-9a-f]{32}");
		assertThat(event.get("capability")).isEqualTo("location.fine");
		assertThat(event.get("state")).isEqualTo("GRANTED");
		assertThat((String) event.get("at")).matches("\\d{4}-\\d\\d-\\d\\dT.*Z");
		assertThat(event.toString()).doesNotContain(u.toString());
		assertThat(jdbc.sql("SELECT count(*) FROM partner_event_outbox WHERE sent_at IS NULL").query(Long.class).single()).isZero();
	}

	@Test
	void everyRealChangeIsQueuedAndNothingElseIs() throws Exception {
		UUID u = connectedUser();
		answer(u, "location.fine", "DENIED");        // NOT_ASKED -> DENIED
		answer(u, "location.fine", "DENIED");        // DENIED -> BLOCKED
		menu(u, "location.fine", "GRANTED");         // BLOCKED -> GRANTED
		menu(u, "location.fine", "GRANTED");         // nothing changes: no event
		menu(u, "location.fine", "DENIED");          // GRANTED -> DENIED
		menu(u, "location.fine", "DENIED");          // DENIED -> DENIED (count already 0): no event
		assertThat(queued()).containsExactly("user.consent_changed location.fine DENIED", "user.consent_changed location.fine BLOCKED", "user.consent_changed location.fine GRANTED",
				"user.consent_changed location.fine DENIED");
	}

	@Test
	void theOldMuteSwitchesAreConsentChangesToo() throws Exception {
		UUID u = connectedUser();
		UUID app = appId;
		mvc.perform(asUser(put("/api/v1/me/apps/{a}/preferences", app), u).contentType(MediaType.APPLICATION_JSON).content("{\"muteSms\":true}")).andExpect(status().isOk());
		assertThat(queued()).containsExactly("user.consent_changed notify.sms DENIED");
	}

	@Test
	void disconnectIsOneEventNotOnePerPermission() throws Exception {
		UUID u = connectedUser();
		answer(u, "location.fine", "GRANTED");
		menu(u, "user.full_name", "GRANTED");
		jdbc.sql("DELETE FROM partner_event_outbox").update();
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", slug), u)).andExpect(status().isOk());
		assertThat(queued()).containsExactly("user.disconnected - -");
		dispatcher.runOnce();
		ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
		verify(client).send(sent.capture());
		assertThat(sent.getValue().get("event")).isEqualTo("user.disconnected");
		assertThat((List<String>) sent.getValue().get("capabilities")).containsExactlyInAnyOrder("location.fine", "user.full_name", "user.basic");
		// disconnecting again changes nothing: nothing queued
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", slug), u)).andExpect(status().isOk());
		assertThat(queued()).hasSize(1);
	}

	@Test
	void openingTheAppAgainAfterADisconnectReconnectsAndTellsThePartner() throws Exception {
		UUID u = connectedUser();
		mvc.perform(asUser(delete("/api/v1/me/apps/{a}/permissions", slug), u)).andExpect(status().isOk());
		jdbc.sql("DELETE FROM partner_event_outbox").update();
		mvc.perform(asUser(post("/api/v1/me/apps/{a}/launch", slug), u)).andExpect(status().isOk());
		assertThat(queued()).containsExactly("user.consent_changed user.basic GRANTED");
	}

	@Test
	void aUserTheAppNeverMetCannotBeReportedBecauseThereIsNoAliasToReport() throws Exception {
		UUID u = UUID.randomUUID();
		when(kratos.identity(u)).thenReturn(Optional.of(new KratosClient.Identity(u, "x-" + u + "@example.com", "+251911222333")));
		menu(u, "location.fine", "GRANTED");   // never launched: the partner has no id for this user
		assertThat(queued()).isEmpty();
	}

	@Test
	void aFailingDeliveryIsRetriedLaterAndNotLost() throws Exception {
		UUID u = connectedUser();
		answer(u, "location.fine", "GRANTED");
		doThrow(new ApiException(503, "service_unavailable")).when(client).send(any());
		assertThat(dispatcher.runOnce()).isEqualTo(1);
		Map<String, Object> row = jdbc.sql("SELECT attempts, sent_at, next_attempt_at > now() AS later FROM partner_event_outbox").query().singleRow();
		assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
		assertThat(row.get("sent_at")).isNull();
		assertThat(row.get("later")).isEqualTo(true);
		// not due yet: the next tick leaves it alone
		assertThat(dispatcher.runOnce()).isZero();
		// once due and the service is back, it goes out
		reset(client);
		jdbc.sql("UPDATE partner_event_outbox SET next_attempt_at = now() - interval '1 second'").update();
		assertThat(dispatcher.runOnce()).isEqualTo(1);
		verify(client).send(any());
		assertThat(jdbc.sql("SELECT count(*) FROM partner_event_outbox WHERE sent_at IS NOT NULL").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void anEventThatCouldNotBeDeliveredForADayIsDroppedAndOldSentOnesAreCleanedUp() throws Exception {
		UUID u = connectedUser();
		answer(u, "location.fine", "GRANTED");
		jdbc.sql("UPDATE partner_event_outbox SET created_at = now() - interval '25 hours'").update();
		assertThat(dispatcher.runOnce()).isEqualTo(1);
		verify(client, never()).send(any());
		assertThat(jdbc.sql("SELECT last_error FROM partner_event_outbox").query(String.class).single()).isEqualTo("expired");
		jdbc.sql("UPDATE partner_event_outbox SET sent_at = now() - interval '8 days'").update();
		dispatcher.runOnce();
		assertThat(jdbc.sql("SELECT count(*) FROM partner_event_outbox").query(Long.class).single()).isZero();
	}

}
