package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.TestcontainersConfiguration;
import app.adera.notification.partner.auth.JwksController;
import app.adera.notification.partner.auth.RegistryClient;
import app.adera.notification.partner.auth.Signatures;
import app.adera.notification.partner.delivery.CallbackWorker;
import com.sun.net.httpserver.HttpServer;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Step 6: user.consent_changed and user.disconnected go out through the same signed, retried callback mechanism as status reports. */
@SpringBootTest(properties = {
		"adera.partner.workers-enabled=false",
		"adera.partner.allow-private-callbacks=true",
		"adera.partner.internal-secret=int-secret",
		"adera.notification.dev-endpoints=true",
		"adera.notification.sms-provider=console",
		"adera.notification.email-provider=mailpit" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ConsentCallbackTests {

	@Autowired MockMvc mvc;
	@Autowired JsonMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired CallbackWorker callbacks;
	@Autowired JwksController jwks;
	@MockitoBean RegistryClient registry;

	private final UUID appId = UUID.randomUUID();
	private final String alias = "u_p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	private void app(boolean usable, String callbackUrl) {
		when(registry.deployment()).thenReturn("DEV");
		when(registry.context(eq(appId))).thenReturn(Optional.of(new RegistryClient.AppContext(appId, "demo", usable ? "LIVE" : "KILLED", "DEV", usable,
				usable ? null : "app_killed", List.of(), List.of(), List.of(), callbackUrl)));
	}

	private ResultActions event(String body) throws Exception {
		return mvc.perform(post("/internal/consent-events").header("X-Internal-Secret", "int-secret").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String changed(String alias, String capability, String state) {
		return "{\"event\":\"user.consent_changed\",\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"capability\":\"" + capability + "\",\"state\":\"" + state + "\",\"at\":\"2026-11-04T10:15:30Z\"}";
	}

	@Test
	void aConsentChangeIsSignedAndSentToTheAppsCallbackUrlWithoutAnyRealUserId() throws Exception {
		String[] headers = new String[5];
		String[] body = new String[1];
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/events", ex -> {
			headers[0] = ex.getRequestHeaders().getFirst("X-Adera-Event");
			headers[1] = ex.getRequestHeaders().getFirst("X-Adera-Signature");
			headers[2] = ex.getRequestHeaders().getFirst("X-Adera-Timestamp");
			headers[3] = ex.getRequestHeaders().getFirst("X-Adera-Key-Id");
			headers[4] = ex.getRequestHeaders().getFirst("Idempotency-Key");
			body[0] = new String(ex.getRequestBody().readAllBytes());
			ex.sendResponseHeaders(204, -1);
			ex.close();
		});
		server.start();
		try {
			String path = "/events?partner=1";
			app(true, "http://127.0.0.1:" + server.getAddress().getPort() + path);
			event(changed(alias, "location.fine", "GRANTED")).andExpect(status().isAccepted()).andExpect(jsonPath("$.queued").value(true));
			assertThat(callbacks.runOnce()).isGreaterThanOrEqualTo(1);
			assertThat(headers[0]).isEqualTo("user.consent_changed");
			assertThat(headers[4]).isNotBlank();
			JsonNode payload = json.readTree(body[0]);
			assertThat(payload.get("event").asString()).isEqualTo("user.consent_changed");
			assertThat(payload.get("alias").asString()).isEqualTo(alias);
			assertThat(payload.get("capability").asString()).isEqualTo("location.fine");
			assertThat(payload.get("state").asString()).isEqualTo("GRANTED");
			assertThat(payload.get("at").asString()).isEqualTo("2026-11-04T10:15:30Z");
			assertThat(payload.get("environment").asString()).isEqualTo("DEV");
			assertThat(body[0]).doesNotContain("userId").doesNotContain("appId");
			// signed exactly like every other callback: ADERA-SIG-1 over timestamp, POST, path with query and the body hash, verified with the JWKS key
			JsonNode key = json.readTree(json.writeValueAsString(jwks.jwks().getBody())).get("keys").get(0);
			assertThat(headers[3]).isEqualTo(key.get("kid").asString());
			java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(
					new java.math.BigInteger(1, java.util.Base64.getUrlDecoder().decode(key.get("n").asString())),
					new java.math.BigInteger(1, java.util.Base64.getUrlDecoder().decode(key.get("e").asString()))));
			assertThat(Signatures.verify(pub, Signatures.canonicalRequest(headers[2], "POST", path, body[0].getBytes()), headers[1])).isTrue();
			assertThat(jdbc.sql("SELECT status FROM callback WHERE payload ->> 'alias' = '" + alias + "'").query(String.class).single()).isEqualTo("DELIVERED");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void aDisconnectCarriesTheCapabilitiesThatWereTakenBack() throws Exception {
		String[] body = new String[1];
		String[] event = new String[1];
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/events", ex -> {
			event[0] = ex.getRequestHeaders().getFirst("X-Adera-Event");
			body[0] = new String(ex.getRequestBody().readAllBytes());
			ex.sendResponseHeaders(200, -1);
			ex.close();
		});
		server.start();
		try {
			app(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/events");
			event("{\"event\":\"user.disconnected\",\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"capabilities\":[\"location.fine\",\"user.basic\"],\"at\":\"2026-11-04T10:15:30Z\"}")
				.andExpect(status().isAccepted());
			callbacks.runOnce();
			assertThat(event[0]).isEqualTo("user.disconnected");
			JsonNode payload = json.readTree(body[0]);
			assertThat(payload.get("alias").asString()).isEqualTo(alias);
			assertThat(payload.get("capabilities")).hasSize(2);
			assertThat(payload.has("capability")).isFalse();
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void anAppWithoutACallbackUrlOrThatIsNotUsableGetsNothing() throws Exception {
		app(true, null);
		event(changed(alias, "location.fine", "DENIED")).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(false)).andExpect(jsonPath("$.reason").value("no_callback_url"));
		app(false, "https://partner.example.com/events");
		event(changed(alias, "location.fine", "DENIED")).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(false)).andExpect(jsonPath("$.reason").value("app_not_active"));
		when(registry.context(eq(appId))).thenReturn(Optional.empty());
		event(changed(alias, "location.fine", "DENIED")).andExpect(status().isOk()).andExpect(jsonPath("$.queued").value(false)).andExpect(jsonPath("$.reason").value("unknown_app"));
		assertThat(jdbc.sql("SELECT count(*) FROM callback WHERE payload ->> 'alias' = '" + alias + "'").query(Long.class).single()).isZero();
	}

	@Test
	void aFailingPartnerIsRetriedLikeAnyOtherCallback() throws Exception {
		int[] calls = { 0 };
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/events", ex -> {
			calls[0]++;
			ex.getRequestBody().readAllBytes();
			ex.sendResponseHeaders(calls[0] == 1 ? 503 : 200, -1);
			ex.close();
		});
		server.start();
		try {
			app(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/events");
			event(changed(alias, "notify.sms", "DENIED")).andExpect(status().isAccepted());
			callbacks.runOnce();
			assertThat(jdbc.sql("SELECT status || ':' || attempts FROM callback WHERE payload ->> 'alias' = '" + alias + "'").query(String.class).single()).isEqualTo("PENDING:1");
			jdbc.sql("UPDATE callback SET next_attempt_at = now() - interval '1 second' WHERE payload ->> 'alias' = '" + alias + "'").update();
			callbacks.runOnce();
			assertThat(calls[0]).isEqualTo(2);
			assertThat(jdbc.sql("SELECT status FROM callback WHERE payload ->> 'alias' = '" + alias + "'").query(String.class).single()).isEqualTo("DELIVERED");
		}
		finally {
			server.stop(0);
		}
	}

	@Test
	void onlyOtherServicesMaySendEventsAndTheyAreValidated() throws Exception {
		app(true, "https://partner.example.com/events");
		mvc.perform(post("/internal/consent-events").contentType(MediaType.APPLICATION_JSON).content(changed(alias, "location.fine", "GRANTED"))).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/consent-events").header("X-Internal-Secret", "nope").contentType(MediaType.APPLICATION_JSON).content(changed(alias, "location.fine", "GRANTED"))).andExpect(status().isUnauthorized());
		for (String bad : List.of("{}", changed("not-an-alias", "location.fine", "GRANTED"), changed(alias, "Bad Key!", "GRANTED"), changed(alias, "location.fine", "MAYBE"),
				changed(alias, "location.fine", "NOT_ASKED").replace("user.consent_changed", "user.exploded"),
				"{\"event\":\"user.disconnected\",\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"capabilities\":\"all\",\"at\":\"2026-11-04T10:15:30Z\"}",
				changed(alias, "location.fine", "GRANTED").replace("2026-11-04T10:15:30Z", "yesterday"))) {
			event(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
		}
	}

}
