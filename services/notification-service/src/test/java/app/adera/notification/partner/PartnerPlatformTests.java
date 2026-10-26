package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.TestcontainersConfiguration;
import app.adera.notification.partner.auth.RegistryClient;
import app.adera.notification.partner.auth.Signatures;
import app.adera.notification.partner.auth.UserDirectoryClient;
import app.adera.notification.partner.delivery.CallbackWorker;
import app.adera.notification.partner.delivery.DeliveryWorker;
import app.adera.notification.partner.delivery.FakeFcmProvider;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** The partner platform end to end against real Postgres: signing, templates, acceptance rules, workers, inbox, callbacks. */
@SpringBootTest(properties = {
		"adera.partner.workers-enabled=false",
		"adera.partner.allow-private-callbacks=true",
		"adera.partner.gateway-secret=gw-secret",
		"adera.notification.dev-endpoints=true",
		"adera.notification.sms-provider=console",
		"adera.notification.email-provider=mailpit" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PartnerPlatformTests {

	@Autowired MockMvc mvc;
	@Autowired JsonMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired DeliveryWorker deliveries;
	@Autowired CallbackWorker callbacks;
	@Autowired FakeFcmProvider push;
	@MockitoBean RegistryClient registry;
	@MockitoBean UserDirectoryClient directory;
	@MockitoBean app.adera.notification.partner.admin.AuditClient audit;

	KeyPair partnerKeys;
	UUID appId;
	String keyId;
	UUID userId;
	String alias;
	List<String> cidrs;

	@BeforeEach
	void setUp() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		partnerKeys = g.generateKeyPair();
		appId = UUID.randomUUID();
		keyId = "k_" + UUID.randomUUID().toString().substring(0, 8);
		userId = UUID.randomUUID();
		alias = "u_p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		cidrs = List.of("203.0.113.0/24");
		app("DEV", List.of("notify.sms", "notify.email", "notify.inapp"), List.of());
		when(directory.resolve(eq(appId), eq(alias))).thenReturn(Optional.of(userId));
		when(directory.context(eq(userId), eq(appId))).thenReturn(Optional.of(new UserDirectoryClient.DeliveryContext(userId, true, "en",
				"+251911000001", "user@example.com", List.of("tok-1"), new UserDirectoryClient.Muted(false, false, false))));
	}

	/** The deployment is DEV and the key is a DEV key. */
	void app(String keyEnvironment, List<String> caps, List<RegistryClient.Limit> limits) {
		appIn("DEV", keyEnvironment, caps, limits);
	}

	/** A deployment of the given environment (ADERA_ENV) and a key registered for keyEnvironment. */
	void appIn(String deployment, String keyEnvironment, List<String> caps, List<RegistryClient.Limit> limits) {
		when(registry.deployment()).thenReturn(deployment);
		when(registry.key(keyId)).thenReturn(Optional.of(new RegistryClient.ParsedKey(
				new RegistryClient.KeyInfo(keyId, appId, keyEnvironment, "RS256", "unused", true, keyEnvironment.equals(deployment), deployment),
				(RSAPublicKey) partnerKeys.getPublic())));
		when(registry.context(eq(appId))).thenReturn(Optional.of(new RegistryClient.AppContext(appId, "demo", "LIVE", deployment, true,
				null, caps, limits, cidrs)));
	}

	MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder b, String method, String path, String body) throws Exception {
		return signedAt(b, method, path, body, Instant.now().getEpochSecond());
	}

	MockHttpServletRequestBuilder signedAt(MockHttpServletRequestBuilder b, String method, String path, String body, long ts) throws Exception {
		String sig = Signatures.sign(partnerKeys.getPrivate(), Signatures.canonicalRequest(String.valueOf(ts), method, path, body.getBytes()));
		return b.header("X-Adera-Key-Id", keyId).header("X-Adera-Timestamp", ts).header("X-Adera-Signature", sig)
			.header("X-Gateway-Secret", "gw-secret").header("X-Client-IP", "203.0.113.9")
			.contentType(MediaType.APPLICATION_JSON).content(body);
	}

	String createTemplate(String kind, String content, String extra) throws Exception {
		String body = "{\"appId\":\"" + appId + "\",\"code\":\"t-" + UUID.randomUUID().toString().substring(0, 8) + "\",\"kind\":\"" + kind
				+ "\",\"category\":\"TRANSACTIONAL\",\"params\":[{\"name\":\"name\",\"maxLength\":30}],\"content\":" + content + extra + "}";
		MvcResult r = mvc.perform(post("/admin/api/v1/notification/templates").header("X-Gateway-Secret", "gw-secret")
			.header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", "ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
		return json.readTree(r.getResponse().getContentAsString()).get("id").asString();
	}

	String directTemplate() throws Exception {
		return createTemplate("DIRECT", "{\"en\":{\"sms\":\"Hi {name}, your job is accepted\",\"body\":\"Hi {name}\",\"emailSubject\":\"Job\"}}", "");
	}

	String send(String externalId, String templateId, String user, String extraFields) throws Exception {
		String body = "{\"externalId\":\"" + externalId + "\",\"userId\":\"" + user + "\",\"templateId\":\"" + templateId
				+ "\",\"params\":{\"name\":\"Abebe\"}" + extraFields + "}";
		return body;
	}

	int sendRaw(String body) throws Exception {
		return mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", body)).andReturn().getResponse().getStatus();
	}

	String statusOf(String externalId) throws Exception {
		String path = "/v1/notifications/" + externalId;
		MvcResult r = mvc.perform(signed(get(path), "GET", path, "")).andExpect(status().isOk()).andReturn();
		return json.readTree(r.getResponse().getContentAsString()).get("status").asString();
	}

	@Test
	void requestsWithoutAValidSignatureAreRejected() throws Exception {
		String body = "{}";
		mvc.perform(post("/v1/notifications").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("missing_signature"));
		mvc.perform(signedAt(post("/v1/notifications"), "POST", "/v1/notifications", body, Instant.now().getEpochSecond() - 3600))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("stale_timestamp"));
		// signature made for a different body
		long ts = Instant.now().getEpochSecond();
		String sig = Signatures.sign(partnerKeys.getPrivate(), Signatures.canonicalRequest(String.valueOf(ts), "POST", "/v1/notifications", "{\"a\":1}".getBytes()));
		mvc.perform(post("/v1/notifications").header("X-Adera-Key-Id", keyId).header("X-Adera-Timestamp", ts).header("X-Adera-Signature", sig)
			.header("X-Gateway-Secret", "gw-secret").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("invalid_signature"));
		// signed with someone else's key
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		String forged = Signatures.sign(g.generateKeyPair().getPrivate(), Signatures.canonicalRequest(String.valueOf(ts), "POST", "/v1/notifications", body.getBytes()));
		mvc.perform(post("/v1/notifications").header("X-Adera-Key-Id", keyId).header("X-Adera-Timestamp", ts).header("X-Adera-Signature", forged)
			.header("X-Gateway-Secret", "gw-secret").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		when(registry.key("nope")).thenReturn(Optional.empty());
		mvc.perform(post("/v1/notifications").header("X-Adera-Key-Id", "nope").header("X-Adera-Timestamp", ts).header("X-Adera-Signature", sig)
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.error").value("unknown_key"));
	}

	@Test
	void serverAddressMustBeOnTheAppsAllowList() throws Exception {
		cidrs = List.of("198.51.100.0/24");
		app("DEV", List.of("notify.sms"), List.of());
		mvc.perform(signed(get("/v1/templates"), "GET", "/v1/templates", "")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("ip_not_allowed"));
		cidrs = List.of("203.0.113.0/24");
		app("DEV", List.of("notify.sms"), List.of());
		mvc.perform(signed(get("/v1/templates"), "GET", "/v1/templates", "")).andExpect(status().isOk());
	}

	@Test
	void responsesAreSignedWithOurKeyAndVerifiable() throws Exception {
		String path = "/v1/templates";
		long ts = Instant.now().getEpochSecond();
		MockHttpServletRequestBuilder request = signedAt(get(path), "GET", path, "", ts);
		MvcResult r = mvc.perform(request).andExpect(status().isOk()).andExpect(header().exists("X-Adera-Signature")).andReturn();
		String requestSig = r.getRequest().getHeader("X-Adera-Signature");
		MvcResult jwks = mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk()).andExpect(jsonPath("$.keys[0].kty").value("RSA")).andReturn();
		var key = json.readTree(jwks.getResponse().getContentAsString()).get("keys").get(0);
		var spec = new java.security.spec.RSAPublicKeySpec(new java.math.BigInteger(1, java.util.Base64.getUrlDecoder().decode(key.get("n").asString())),
				new java.math.BigInteger(1, java.util.Base64.getUrlDecoder().decode(key.get("e").asString())));
		var publicKey = java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
		String canonical = Signatures.canonicalResponse(r.getResponse().getHeader("X-Adera-Timestamp"), 200, r.getResponse().getContentAsByteArray(), requestSig);
		assertThat(Signatures.verify(publicKey, canonical, r.getResponse().getHeader("X-Adera-Signature"))).isTrue();
	}

	@Test
	void staffEndpointsNeedTheGatewayAndRole() throws Exception {
		mvc.perform(get("/admin/api/v1/notification/templates").param("appId", appId.toString())).andExpect(status().isUnauthorized());
		mvc.perform(post("/admin/api/v1/notification/templates").header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", UUID.randomUUID().toString())
			.header("X-Staff-Role", "VIEWER").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
	}

	@Test
	void creatingAndEditingATemplateIsAudited() throws Exception {
		UUID staffId = UUID.randomUUID();
		String body = "{\"appId\":\"" + appId + "\",\"code\":\"aud-" + UUID.randomUUID().toString().substring(0, 6) + "\",\"kind\":\"DIRECT\",\"category\":\"TRANSACTIONAL\","
				+ "\"params\":[{\"name\":\"name\",\"maxLength\":30}],\"content\":{\"en\":{\"sms\":\"Hi {name}\",\"body\":\"Hi {name}\"}}}";
		MvcResult created = mvc.perform(post("/admin/api/v1/notification/templates").header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", staffId.toString())
			.header("X-Staff-Role", "ADMIN").header("X-Client-IP", "198.51.100.7").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
		String id = json.readTree(created.getResponse().getContentAsString()).get("id").asString();
		org.mockito.Mockito.verify(audit).record(eq(staffId.toString()), eq("ADMIN"), eq("template.create"), eq("template"), eq(id), any(), eq("198.51.100.7"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/admin/api/v1/notification/templates/" + id)
			.header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", staffId.toString()).header("X-Staff-Role", "ADMIN").header("X-Client-IP", "198.51.100.7")
			.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
		org.mockito.Mockito.verify(audit).record(eq(staffId.toString()), eq("ADMIN"), eq("template.update"), eq("template"), eq(id), any(), eq("198.51.100.7"));
	}

	@Test
	void ifTheAuditEntryCannotBeWrittenTheTemplateChangeIsUndone() throws Exception {
		org.mockito.Mockito.doThrow(new ApiException(503, "audit_unavailable")).when(audit).record(any(), any(), any(), any(), any(), any(), any());
		String code = "noaud-" + UUID.randomUUID().toString().substring(0, 6);
		String body = "{\"appId\":\"" + appId + "\",\"code\":\"" + code + "\",\"kind\":\"DIRECT\",\"category\":\"TRANSACTIONAL\",\"params\":[],\"content\":{\"en\":{\"sms\":\"Hi\",\"body\":\"Hi\"}}}";
		mvc.perform(post("/admin/api/v1/notification/templates").header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", UUID.randomUUID().toString())
			.header("X-Staff-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").value("audit_unavailable"));
		assertThat(jdbc.sql("SELECT COUNT(*) FROM template WHERE code = :c").param("c", code).query(Integer.class).single()).isZero();
	}

	@Test
	void templateValidationRejectsUndeclaredPlaceholdersAndMissingBody() throws Exception {
		mvc.perform(post("/admin/api/v1/notification/templates").header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", UUID.randomUUID().toString())
			.header("X-Staff-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON)
			.content("{\"appId\":\"" + appId + "\",\"code\":\"bad-one\",\"kind\":\"DIRECT\",\"category\":\"TRANSACTIONAL\",\"params\":[],\"content\":{\"en\":{\"body\":\"Hi {who}\"}}}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
	}

	@Test
	void directNotificationGoesBySmsThenIsDeliveredAndReplayIsIdempotent() throws Exception {
		String template = directTemplate();
		String ext = "job-" + UUID.randomUUID();
		String body = send(ext, template, alias, "");
		assertThat(sendRaw(body)).isEqualTo(202);
		assertThat(sendRaw(body)).isEqualTo(200); // replay
		assertThat(sendRaw(send(ext, template, alias, ",\"ttlSeconds\":120"))).isEqualTo(409); // same id, different request
		assertThat(statusOf(ext)).isEqualTo("QUEUED");
		assertThat(deliveries.runOnce()).isGreaterThanOrEqualTo(1);
		assertThat(statusOf(ext)).isEqualTo("SENT");
		assertThat(jdbc.sql("SELECT channel FROM notification WHERE external_id = :e").param("e", ext).query(String.class).single()).isEqualTo("SMS");
	}

	@Test
	void fallsBackToEmailWhenTheAppMayNotSendSms() throws Exception {
		app("DEV", List.of("notify.email"), List.of());
		String ext = "mail-" + UUID.randomUUID();
		assertThat(sendRaw(send(ext, directTemplate(), alias, ""))).isEqualTo(202);
		assertThat(jdbc.sql("SELECT channel FROM notification WHERE external_id = :e").param("e", ext).query(String.class).single()).isEqualTo("EMAIL");
	}

	@Test
	void unknownOrUnreachableUsersAreAGenericUnprocessable() throws Exception {
		String template = directTemplate();
		when(directory.resolve(eq(appId), eq("u_p_ghost"))).thenReturn(Optional.empty());
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("g1", template, "u_p_ghost", "")))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("user_unreachable"));
		when(directory.context(eq(userId), eq(appId))).thenReturn(Optional.of(new UserDirectoryClient.DeliveryContext(userId, false, "en", null, null,
				List.of(), new UserDirectoryClient.Muted(false, false, false))));
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("g2", template, alias, "")))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("user_unreachable"));
	}

	@Test
	void undeclaredParamsAndForeignTemplatesAreRefused() throws Exception {
		String template = directTemplate();
		String bad = "{\"externalId\":\"p1\",\"userId\":\"" + alias + "\",\"templateId\":\"" + template + "\",\"params\":{\"name\":\"x\",\"evil\":\"y\"}}";
		assertThat(sendRaw(bad)).isEqualTo(422);
		String link = "{\"externalId\":\"p2\",\"userId\":\"" + alias + "\",\"templateId\":\"" + template + "\",\"params\":{\"name\":\"visit http://evil.example\"}}";
		assertThat(sendRaw(link)).isEqualTo(422);
		assertThat(sendRaw(send("p3", UUID.randomUUID().toString(), alias, ""))).isEqualTo(404);
	}

	@Test
	void inactiveAndExpiredTemplatesCannotBeUsed() throws Exception {
		String template = directTemplate();
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/admin/api/v1/notification/templates/" + template)
			.header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", "ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("i1", template, alias, "")))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("template_inactive"));
	}

	@Test
	void limitsAreEnforcedPerUserAndNullMeansUnlimitedZeroMeansNone() throws Exception {
		app("DEV", List.of("notify.sms"), List.of(new RegistryClient.Limit("SMS", 1, null, null, null)));
		String template = directTemplate();
		assertThat(sendRaw(send("l1", template, alias, ""))).isEqualTo(202);
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("l2", template, alias, "")))
			.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("quota_exceeded"));
		app("DEV", List.of("notify.sms"), List.of(new RegistryClient.Limit("SMS", null, 0, null, null)));
		assertThat(sendRaw(send("l3", template, alias, ""))).isEqualTo(429);
		// the refused request left nothing behind and did not use up quota
		assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE external_id IN ('l2','l3')").query(Integer.class).single()).isZero();
	}

	@Test
	void capabilityNotGrantedIsForbidden() throws Exception {
		app("DEV", List.of("notify.email"), List.of());
		when(directory.context(eq(userId), eq(appId))).thenReturn(Optional.of(new UserDirectoryClient.DeliveryContext(userId, true, "en", "+251911000001",
				null, List.of(), new UserDirectoryClient.Muted(false, false, false))));
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("c1", directTemplate(), alias, "")))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("capability_not_granted"));
	}

	@Test
	void mutedUsersAreSuppressedWithoutBeingTold() throws Exception {
		when(directory.context(eq(userId), eq(appId))).thenReturn(Optional.of(new UserDirectoryClient.DeliveryContext(userId, true, "en", "+251911000001",
				"user@example.com", List.of(), new UserDirectoryClient.Muted(false, true, false))));
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("m1", directTemplate(), alias, "")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUPPRESSED"));
	}

	@Test
	void inAppNotificationLandsInTheInboxAndPushesToDevices() throws Exception {
		String template = createTemplate("IN_APP", "{\"en\":{\"title\":\"Hello {name}\",\"body\":\"Your ride is here\"}}", "");
		String ext = "app-" + UUID.randomUUID();
		assertThat(sendRaw(send(ext, template, alias, ""))).isEqualTo(202);
		deliveries.runOnce();
		assertThat(statusOf(ext)).isEqualTo("SENT");
		assertThat(push.sent).anySatisfy(p -> assertThat(p.token()).isEqualTo("tok-1"));
		mvc.perform(get("/api/v1/me/notifications").header("X-Gateway-Secret", "gw-secret").header("X-User-Id", userId.toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Hello Abebe"));
		mvc.perform(get("/api/v1/me/notifications/unread-count").header("X-Gateway-Secret", "gw-secret").header("X-User-Id", userId.toString()))
			.andExpect(jsonPath("$.count").value(1));
		mvc.perform(put("/api/v1/me/notifications/read-all").header("X-Gateway-Secret", "gw-secret").header("X-User-Id", userId.toString()))
			.andExpect(jsonPath("$.markedRead").value(1));
		// somebody else's inbox is empty
		mvc.perform(get("/api/v1/me/notifications").header("X-Gateway-Secret", "gw-secret").header("X-User-Id", UUID.randomUUID().toString()))
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void aMessageThatWouldNotFitTheInboxColumnsIsRefusedAtAcceptance() throws Exception {
		String body = "{\"appId\":\"" + appId + "\",\"code\":\"big-" + UUID.randomUUID().toString().substring(0, 8)
				+ "\",\"kind\":\"IN_APP\",\"category\":\"TRANSACTIONAL\",\"params\":[{\"name\":\"name\",\"maxLength\":500}],"
				+ "\"content\":{\"en\":{\"title\":\"Hi {name}\",\"body\":\"Hello\"}}}";
		String template = json.readTree(mvc.perform(post("/admin/api/v1/notification/templates").header("X-Gateway-Secret", "gw-secret")
			.header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", "ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
		String tooLong = "{\"externalId\":\"big-1\",\"userId\":\"" + alias + "\",\"templateId\":\"" + template + "\",\"params\":{\"name\":\"" + "x".repeat(300) + "\"}}";
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", tooLong)).andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error").value("param_invalid")).andExpect(jsonPath("$.fields.title").exists());
		String fits = tooLong.replace("x".repeat(300), "x".repeat(150)).replace("big-1", "big-2");
		assertThat(sendRaw(fits)).isEqualTo(202);
		deliveries.runOnce();
		assertThat(statusOf("big-2")).isEqualTo("SENT");
	}

	@Test
	void aKeyOfTheOtherEnvironmentIsRefusedWithWrongEnvironment() throws Exception {
		String template = directTemplate();
		appIn("DEV", "PROD", List.of("notify.sms"), List.of());
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("we-1", template, alias, "")))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("wrong_environment"));
		mvc.perform(signed(get("/v1/templates"), "GET", "/v1/templates", "")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("wrong_environment"));
		appIn("PROD", "DEV", List.of("notify.sms"), List.of());
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("we-2", template, alias, "")))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("wrong_environment"));
		// nothing was stored
		assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE external_id IN ('we-1', 'we-2')").query(Integer.class).single()).isZero();
		// the key's own environment, in the matching deployment, works
		appIn("PROD", "PROD", List.of("notify.sms"), List.of());
		assertThat(sendRaw(send("we-3", template, alias, ""))).isEqualTo(202);
		assertThat(jdbc.sql("SELECT environment FROM notification WHERE external_id = 'we-3'").query(String.class).single()).isEqualTo("PROD");
	}

	@Test
	void devSyntheticUsersAreSimulatedWithoutSendingAndRealUsersGoThroughTheDevProviders() throws Exception {
		app("DEV", List.of("notify.sms"), List.of());
		String template = directTemplate();
		String ext = "sb-" + UUID.randomUUID();
		assertThat(sendRaw(send(ext, template, "u_p_test_ok", ""))).isEqualTo(202);
		deliveries.runOnce();
		assertThat(statusOf(ext)).isEqualTo("SENT");
		// simulated: the attempt is recorded as "sandbox", not as a send by the console provider
		assertThat(jdbc.sql("SELECT a.provider FROM notification_attempt a JOIN notification n ON n.id = a.notification_id WHERE n.external_id = :e")
			.param("e", ext).query(String.class).single()).isEqualTo("sandbox");
		mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("sb2", template, "u_p_test_unreachable", "")))
			.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("user_unreachable"));
		assertThat(sendRaw(send("sb3", template, "u_p_test_muted", ""))).isEqualTo(200);
		assertThat(statusOf("sb3")).isEqualTo("SUPPRESSED");
		// a real user in DEV is delivered for real by the dev provider (console SMS), not simulated
		String real = "real-" + UUID.randomUUID();
		assertThat(sendRaw(send(real, template, alias, ""))).isEqualTo(202);
		deliveries.runOnce();
		assertThat(jdbc.sql("SELECT a.provider FROM notification_attempt a JOIN notification n ON n.id = a.notification_id WHERE n.external_id = :e")
			.param("e", real).query(String.class).single()).isEqualTo("console");
	}

	@Test
	void syntheticUsersAreRefusedInProd() throws Exception {
		appIn("PROD", "PROD", List.of("notify.sms"), List.of());
		String template = directTemplate();
		for (String synthetic : List.of("u_p_test_ok", "u_p_test_unreachable", "u_p_test_muted")) {
			mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", send("p-" + synthetic, template, synthetic, "")))
				.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("synthetic_user_not_allowed"));
		}
		assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE external_id LIKE 'p-u_p_test_%'").query(Integer.class).single()).isZero();
	}

	@Test
	void aBadCallbackUrlIsRefusedImmediatelyNotSilentlyDroppedLater() throws Exception {
		String template = directTemplate();
		for (String url : new String[] { "https://user:pw@example.com/hook", "ftp://example.com/hook", "not a url" }) {
			String body = send("cbu-" + UUID.randomUUID(), template, alias, ",\"callbackUrl\":\"" + url + "\"");
			mvc.perform(signed(post("/v1/notifications"), "POST", "/v1/notifications", body)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.callbackUrl").exists());
		}
		assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE external_id LIKE 'cbu-%'").query(Integer.class).single()).isZero();
	}

	@Test
	void statusCallbackIsSignedAndRetriedUntilThePartnerAnswers() throws Exception {
		int[] calls = { 0 };
		String[] captured = new String[3];
		HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/hook", ex -> {
			calls[0]++;
			captured[0] = ex.getRequestHeaders().getFirst("X-Adera-Signature");
			captured[1] = ex.getRequestHeaders().getFirst("X-Adera-Timestamp");
			captured[2] = new String(ex.getRequestBody().readAllBytes());
			ex.sendResponseHeaders(calls[0] == 1 ? 500 : 200, -1);
			ex.close();
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
			String ext = "cb-" + UUID.randomUUID();
			assertThat(sendRaw(send(ext, directTemplate(), alias, ",\"callbackUrl\":\"" + url + "\""))).isEqualTo(202);
			deliveries.runOnce();
			assertThat(callbacks.runOnce()).isEqualTo(1);
			assertThat(calls[0]).isEqualTo(1);
			// first answer was 500: it is rescheduled for later, not lost
			assertThat(jdbc.sql("SELECT status FROM callback").query(String.class).list()).contains("PENDING");
			jdbc.sql("UPDATE callback SET next_attempt_at = now() - interval '1 second' WHERE status = 'PENDING'").update();
			callbacks.runOnce();
			assertThat(calls[0]).isEqualTo(2);
			assertThat(captured[2]).contains(ext).contains("SENT");
			assertThat(jdbc.sql("SELECT COUNT(*) FROM callback WHERE status = 'DELIVERED'").query(Integer.class).single()).isGreaterThanOrEqualTo(1);
			assertThat(captured[0]).isNotBlank();
		}
		finally {
			server.stop(0);
		}
	}

}
