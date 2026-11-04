package app.adera.notification.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.TestcontainersConfiguration;
import app.adera.notification.partner.auth.JwksController;
import app.adera.notification.partner.auth.RegistryClient;
import app.adera.notification.partner.auth.Signatures;
import app.adera.notification.partner.auth.UserDirectoryClient;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Step 5 of the bridge design: the launch token signer (internal) and the partner profile endpoint (signed, consent-filtered). */
@SpringBootTest(properties = {
		"adera.partner.workers-enabled=false",
		"adera.partner.gateway-secret=gw-secret",
		"adera.partner.internal-secret=int-secret",
		"adera.notification.dev-endpoints=true",
		"adera.notification.sms-provider=console",
		"adera.notification.email-provider=mailpit" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LaunchTokenAndProfileTests {

	@Autowired MockMvc mvc;
	@Autowired JsonMapper json;
	@Autowired JdbcClient jdbc;
	@Autowired JwksController jwks;
	@MockitoBean RegistryClient registry;
	@MockitoBean UserDirectoryClient directory;
	@MockitoBean app.adera.notification.partner.admin.AuditClient audit;

	KeyPair partnerKeys;
	UUID appId;
	String keyId;
	UUID userId;
	String alias;

	@BeforeEach
	void setUp() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		partnerKeys = g.generateKeyPair();
		appId = UUID.randomUUID();
		keyId = "k_" + UUID.randomUUID().toString().substring(0, 8);
		userId = UUID.randomUUID();
		alias = "u_p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		app("DEV", "DEV", List.of("user.basic", "user.full_name", "user.contact", "user.address", "notify.sms"));
		when(directory.resolve(eq(appId), eq(alias))).thenReturn(Optional.of(userId));
	}

	void app(String deployment, String keyEnvironment, List<String> caps) {
		when(registry.deployment()).thenReturn(deployment);
		when(registry.key(keyId)).thenReturn(Optional.of(new RegistryClient.ParsedKey(
				new RegistryClient.KeyInfo(keyId, appId, keyEnvironment, "RS256", "unused", true, keyEnvironment.equals(deployment), deployment),
				(RSAPublicKey) partnerKeys.getPublic())));
		when(registry.context(eq(appId))).thenReturn(Optional.of(new RegistryClient.AppContext(appId, "demo", "LIVE", deployment, true, null, caps,
				List.of(), List.of("203.0.113.0/24"))));
	}

	/** A signed GET: the signature covers the path WITH its query string, exactly as a partner signs it. */
	MockHttpServletRequestBuilder signed(String path) throws Exception {
		long ts = Instant.now().getEpochSecond();
		String sig = Signatures.sign(partnerKeys.getPrivate(), Signatures.canonicalRequest(String.valueOf(ts), "GET", path, new byte[0]));
		return get(path).header("X-Adera-Key-Id", keyId).header("X-Adera-Timestamp", ts).header("X-Adera-Signature", sig)
			.header("X-Gateway-Secret", "gw-secret").header("X-Client-IP", "203.0.113.9");
	}

	private void user(Map<String, String> states, Map<String, Object> fields) {
		when(directory.profile(eq(userId), eq(appId), any())).thenReturn(Optional.of(new UserDirectoryClient.ConsentedProfile(true, states, fields)));
	}

	private static final Map<String, String> ALL = Map.of("user.basic", "GRANTED", "user.full_name", "GRANTED", "user.contact", "GRANTED", "user.address", "GRANTED");

	// ---------- the launch token signer ----------

	private String signToken(String body) throws Exception {
		return mvc.perform(post("/internal/launch-tokens").header("X-Internal-Secret", "int-secret").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private JsonNode decodePart(String jwt, int part) {
		return json.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[part]));
	}

	@Test
	void theLaunchTokenIsAnRs256JwtSignedWithTheAderaKeyAndVerifiesAgainstTheJwks() throws Exception {
		String res = signToken("{\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"scopes\":[\"user.basic\",\"location.fine\"]}");
		JsonNode out = json.readTree(res);
		String jwt = out.get("token").asString();
		assertThat(out.get("expiresInSeconds").asInt()).isEqualTo(300);
		assertThat(jwt.split("\\.")).hasSize(3);

		JsonNode header = decodePart(jwt, 0);
		JsonNode claims = decodePart(jwt, 1);
		assertThat(header.get("alg").asString()).isEqualTo("RS256");
		assertThat(header.get("typ").asString()).isEqualTo("JWT");
		JsonNode key = json.readTree(json.writeValueAsString(jwks.jwks().getBody())).get("keys").get(0);
		assertThat(header.get("kid").asString()).isEqualTo(key.get("kid").asString());

		assertThat(claims.get("iss").asString()).isEqualTo("adera");
		assertThat(claims.get("aud").asString()).isEqualTo(appId.toString());
		assertThat(claims.get("sub").asString()).isEqualTo(alias);
		assertThat(claims.get("env").asString()).isEqualTo("dev");
		assertThat(claims.get("scopes")).hasSize(2);
		assertThat(claims.get("scopes").get(1).asString()).isEqualTo("location.fine");
		assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(300);
		assertThat(claims.get("jti").asString()).isNotBlank();
		// no real user id anywhere in it
		assertThat(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8)).doesNotContain(userId.toString());

		// verify exactly the way a partner does: n and e from the JWKS, RSASSA-PKCS1-v1_5 SHA-256 over "header.payload"
		java.math.BigInteger n = new java.math.BigInteger(1, Base64.getUrlDecoder().decode(key.get("n").asString()));
		java.math.BigInteger e = new java.math.BigInteger(1, Base64.getUrlDecoder().decode(key.get("e").asString()));
		java.security.PublicKey pub = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(n, e));
		java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
		verifier.initVerify(pub);
		verifier.update((jwt.split("\\.")[0] + "." + jwt.split("\\.")[1]).getBytes(StandardCharsets.US_ASCII));
		assertThat(verifier.verify(Base64.getUrlDecoder().decode(jwt.split("\\.")[2]))).isTrue();
	}

	@Test
	void twoTokensForTheSameUserDiffer() throws Exception {
		String body = "{\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"scopes\":[]}";
		assertThat(json.readTree(signToken(body)).get("token").asString()).isNotEqualTo(json.readTree(signToken(body)).get("token").asString());
	}

	@Test
	void onlyOtherServicesWithTheInternalSecretMaySign() throws Exception {
		String body = "{\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"scopes\":[]}";
		mvc.perform(post("/internal/launch-tokens").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/launch-tokens").header("X-Internal-Secret", "nope").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/launch-tokens").header("X-Gateway-Secret", "gw-secret").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
	}

	@Test
	void theSignerValidatesWhatItIsAskedToSign() throws Exception {
		for (String bad : List.of("{}", "{\"appId\":\"nope\",\"alias\":\"" + alias + "\",\"scopes\":[]}", "{\"appId\":\"" + appId + "\",\"alias\":\"not-an-alias\",\"scopes\":[]}",
				"{\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"scopes\":[\"bad key!\"]}", "{\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"scopes\":\"user.basic\"}",
				"{\"appId\":\"" + appId + "\",\"alias\":\"" + alias + "\",\"scopes\":[],\"extra\":1}")) {
			mvc.perform(post("/internal/launch-tokens").header("X-Internal-Secret", "int-secret").contentType(MediaType.APPLICATION_JSON).content(bad))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
		}
	}

	// ---------- the partner profile ----------

	@Test
	void theProfileHasOnlyWhatTheUserAllowedAndNeverNull() throws Exception {
		user(Map.of("user.basic", "GRANTED", "user.full_name", "GRANTED", "user.contact", "NOT_ASKED", "user.address", "DENIED"), Map.of("fullName", "Hana Tesfaye"));
		String body = mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isOk())
			.andExpect(header().exists("X-Adera-Signature")).andExpect(header().exists("X-Adera-Key-Id"))
			.andExpect(jsonPath("$.alias").value(alias)).andExpect(jsonPath("$.fullName").value("Hana Tesfaye"))
			.andExpect(jsonPath("$.granted.length()").value(2)).andExpect(jsonPath("$.granted[0]").value("user.basic")).andExpect(jsonPath("$.granted[1]").value("user.full_name"))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("null").doesNotContain("phone").doesNotContain("email").doesNotContain("address");
	}

	@Test
	void everythingAllowedGivesNameContactAndAddress() throws Exception {
		user(ALL, Map.of("fullName", "Hana Tesfaye", "phone", "+251900000003", "email", "hana@example.com", "address", Map.of("city", "Addis Ababa", "countryCode", "ET")));
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isOk())
			.andExpect(jsonPath("$.granted.length()").value(4)).andExpect(jsonPath("$.phone").value("+251900000003")).andExpect(jsonPath("$.email").value("hana@example.com"))
			.andExpect(jsonPath("$.address.city").value("Addis Ababa")).andExpect(jsonPath("$.address.countryCode").value("ET"));
	}

	@Test
	void aContactThatTheUserDoesNotHaveIsAbsentEvenWhenAllowed() throws Exception {
		user(ALL, Map.of("fullName", "Hana Tesfaye", "email", "hana@example.com"));
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("hana@example.com"))
			.andExpect(jsonPath("$.phone").doesNotExist()).andExpect(jsonPath("$.address").doesNotExist()).andExpect(jsonPath("$.granted.length()").value(4));
	}

	@Test
	void aMiniAppThatNeedsAFieldAsksForItAndGetsAnExactRefusal() throws Exception {
		user(Map.of("user.basic", "GRANTED", "user.full_name", "DENIED", "user.contact", "GRANTED", "user.address", "NOT_ASKED"), Map.of("phone", "+251900000003"));
		mvc.perform(signed("/v1/users/" + alias + "/profile?fields=user.full_name")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("consent_required")).andExpect(jsonPath("$.capability").value("user.full_name")).andExpect(jsonPath("$.state").value("DENIED"));
		mvc.perform(signed("/v1/users/" + alias + "/profile?fields=user.contact,user.address")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.capability").value("user.address")).andExpect(jsonPath("$.state").value("NOT_ASKED"));
		// everything asked for is allowed: fine, and only that is returned
		mvc.perform(signed("/v1/users/" + alias + "/profile?fields=user.contact")).andExpect(status().isOk()).andExpect(jsonPath("$.phone").value("+251900000003"));
	}

	@Test
	void aDisconnectedUserIsRefusedForTheWholeProfile() throws Exception {
		user(Map.of("user.basic", "DENIED", "user.full_name", "DENIED", "user.contact", "DENIED", "user.address", "DENIED"), Map.of());
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("consent_required")).andExpect(jsonPath("$.capability").value("user.basic")).andExpect(jsonPath("$.state").value("DENIED"));
	}

	@Test
	void aFieldTheAppWasNeverGrantedIsAnAppProblemNotAUserProblem() throws Exception {
		app("DEV", "DEV", List.of("user.basic", "user.full_name"));
		user(ALL, Map.of("fullName", "Hana Tesfaye"));
		mvc.perform(signed("/v1/users/" + alias + "/profile?fields=user.contact")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("capability_not_granted")).andExpect(jsonPath("$.capability").value("user.contact"));
		// without asking, only what the app holds is released even if the user allowed more
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isOk()).andExpect(jsonPath("$.granted.length()").value(2)).andExpect(jsonPath("$.phone").doesNotExist());
		app("DEV", "DEV", List.of("user.full_name"));
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("capability_not_granted")).andExpect(jsonPath("$.capability").value("user.basic"));
	}

	@Test
	void unknownAliasesAndUnreachableUsersLookTheSame() throws Exception {
		when(directory.resolve(eq(appId), eq("u_p_ghost"))).thenReturn(Optional.empty());
		mvc.perform(signed("/v1/users/u_p_ghost/profile")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("user_unreachable"));
		when(directory.profile(eq(userId), eq(appId), any())).thenReturn(Optional.of(new UserDirectoryClient.ConsentedProfile(false, Map.of(), Map.of())));
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("user_unreachable"));
		mvc.perform(signed("/v1/users/not-an-alias/profile")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
		mvc.perform(signed("/v1/users/" + alias + "/profile?fields=user.shoe_size")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.fields").exists());
	}

	@Test
	void theSignatureIsRequiredAndTheKeysEnvironmentMustMatchTheDeployment() throws Exception {
		user(ALL, Map.of("fullName", "x"));
		mvc.perform(get("/v1/users/{a}/profile", alias)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("missing_signature"));
		app("DEV", "PROD", List.of("user.basic"));
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("wrong_environment"));
	}

	@Test
	void anAliasOfAnotherAppIsNotResolved() throws Exception {
		UUID other = UUID.randomUUID();
		when(directory.resolve(eq(appId), eq("u_p_other"))).thenReturn(Optional.empty());
		when(directory.resolve(eq(other), eq("u_p_other"))).thenReturn(Optional.of(UUID.randomUUID()));
		mvc.perform(signed("/v1/users/u_p_other/profile")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("user_unreachable"));
	}

	@Test
	void syntheticUsersAnswerInDevAndAreRefusedInProd() throws Exception {
		mvc.perform(signed("/v1/users/u_p_test_ok/profile")).andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Test User"))
			.andExpect(jsonPath("$.granted.length()").value(4)).andExpect(jsonPath("$.phone").value("+251900000000"));
		mvc.perform(signed("/v1/users/u_p_test_denied/profile?fields=user.full_name")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("consent_required")).andExpect(jsonPath("$.state").value("DENIED"));
		mvc.perform(signed("/v1/users/u_p_test_noconsent/profile?fields=user.contact")).andExpect(status().isForbidden()).andExpect(jsonPath("$.state").value("NOT_ASKED"));
		mvc.perform(signed("/v1/users/u_p_test_blocked/profile?fields=user.address")).andExpect(status().isForbidden()).andExpect(jsonPath("$.state").value("BLOCKED"));
		mvc.perform(signed("/v1/users/u_p_test_unreachable/profile")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("user_unreachable"));
		app("PROD", "PROD", List.of("user.basic", "user.full_name"));
		mvc.perform(signed("/v1/users/u_p_test_ok/profile")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error").value("synthetic_user_not_allowed"));
	}

	@Test
	void everyProfileReadIsLoggedWithoutTheValues() throws Exception {
		user(Map.of("user.basic", "GRANTED", "user.full_name", "GRANTED", "user.contact", "DENIED", "user.address", "NOT_ASKED"), Map.of("fullName", "Hana Tesfaye"));
		mvc.perform(signed("/v1/users/" + alias + "/profile")).andExpect(status().isOk());
		mvc.perform(signed("/v1/users/" + alias + "/profile?fields=user.contact")).andExpect(status().isForbidden());
		List<String> rows = jdbc.sql("SELECT outcome || ' ' || coalesce(array_to_string(granted, ','), '') FROM profile_access WHERE app_id = :a ORDER BY id").param("a", appId).query(String.class).list();
		assertThat(rows).containsExactly("OK user.basic,user.full_name", "CONSENT_REQUIRED user.basic,user.full_name");
		assertThat(jdbc.sql("SELECT count(*) FROM profile_access WHERE app_id = :a AND alias = :al AND environment = 'DEV'").param("a", appId).param("al", alias).query(Long.class).single()).isEqualTo(2);
		assertThat(rows.toString()).doesNotContain("Hana");
	}

}
