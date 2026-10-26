package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.imageio.ImageIO;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import app.adera.registry.key.PemKeys;
import app.adera.registry.staff.Staff;
import app.adera.registry.staff.StaffRepository;
import app.adera.registry.storage.InMemoryObjectStorage;
import app.adera.registry.storage.ObjectStorage;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** The whole registry API against a real Postgres, with fake storage and a mocked Kratos. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory", "adera.registry.logo.max-bytes=100000" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistryApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@Autowired
	StaffRepository staffRepo;

	@Autowired
	ObjectStorage storage;

	@MockitoBean
	KratosClient kratos;

	private static final UUID ADMIN_ID = UUID.randomUUID();

	// ---------- helpers ----------

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", ADMIN_ID.toString()).header("X-Staff-Role", role.name());
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T asUser(T request, UUID userId) {
		return request.header("X-User-Id", userId.toString());
	}

	private String json(Object o) {
		return mapper.writeValueAsString(o);
	}

	private static String slug() {
		return "t-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private UUID createApp(String slug) throws Exception {
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("slug", slug, "name", Map.of("en", "App " + slug, "am", "መተግበሪያ"), "description", Map.of("en", "desc")))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	private static byte[] png(int w, int h) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
		return out.toByteArray();
	}

	private void uploadLogo(UUID appId) throws Exception {
		mvc.perform(as(multipart("/admin/api/v1/apps/{id}/logo", appId).file(new MockMultipartFile("file", "l.png", "image/png", png(800, 400))), Role.ADMIN))
			.andExpect(status().isOk());
	}

	private UUID liveApp(int priority) throws Exception {
		UUID id = createApp(slug());
		mvc.perform(as(patch("/admin/api/v1/apps/{id}", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("supportEmail", "help@example.com", "priority", priority)))).andExpect(status().isOk());
		uploadLogo(id);
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LIVE\"}"))
			.andExpect(status().isOk());
		return id;
	}

	private String publicKeyPem() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		KeyPair pair = g.generateKeyPair();
		return PemKeys.pemOf(pair.getPublic().getEncoded());
	}

	// ---------- roles ----------

	@Test
	void viewerCanReadButNotChange() throws Exception {
		mvc.perform(as(get("/admin/api/v1/apps"), Role.VIEWER)).andExpect(status().isOk());
		mvc.perform(as(post("/admin/api/v1/apps"), Role.VIEWER).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("slug", slug(), "name", Map.of("en", "x")))))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("insufficient_role"));
	}

	@Test
	void onlySuperAdminManagesStaff() throws Exception {
		mvc.perform(as(get("/admin/api/v1/staff"), Role.ADMIN)).andExpect(status().isForbidden());
		mvc.perform(as(post("/admin/api/v1/staff"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.co\",\"role\":\"VIEWER\"}"))
			.andExpect(status().isForbidden());
		mvc.perform(as(get("/admin/api/v1/staff"), Role.SUPER_ADMIN)).andExpect(status().isOk());
		mvc.perform(as(get("/admin/api/v1/me"), Role.VIEWER)).andExpect(jsonPath("$.role").value("VIEWER"));
	}

	@Test
	void adminEndpointsRejectRequestsWithoutVerifiedStaffHeaders() throws Exception {
		mvc.perform(get("/admin/api/v1/apps")).andExpect(status().isUnauthorized());
	}

	// ---------- apps ----------

	@Test
	void appLifecycleCreateUpdateAndValidation() throws Exception {
		String slug = slug();
		UUID id = createApp(slug);
		mvc.perform(as(get("/admin/api/v1/apps/{id}", id), Role.VIEWER))
			.andExpect(jsonPath("$.app.slug").value(slug))
			.andExpect(jsonPath("$.app.status").value("DRAFT"))
			.andExpect(jsonPath("$.app.priority").value(100))
			.andExpect(jsonPath("$.app.name.am").value("መተግበሪያ"))
			.andExpect(jsonPath("$.environments.length()").value(2));

		mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("slug", slug, "name", Map.of("en", "dup")))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("slug_taken"));

		mvc.perform(as(patch("/admin/api/v1/apps/{id}", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("brandColor", "#abcdef", "markets", List.of("ET", "US"), "priority", 7, "extra", Map.of("owner", "team-x")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.brandColor").value("#ABCDEF"))
			.andExpect(jsonPath("$.priority").value(7))
			.andExpect(jsonPath("$.extra.owner").value("team-x"));

		mvc.perform(as(patch("/admin/api/v1/apps/{id}", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("slug", "changed", "brandColor", "green", "bogus", 1))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.slug").exists()).andExpect(jsonPath("$.fields.brandColor").exists()).andExpect(jsonPath("$.fields.bogus").exists());
		mvc.perform(as(get("/admin/api/v1/apps/{id}", UUID.randomUUID()), Role.VIEWER)).andExpect(status().isNotFound());
	}

	@Test
	void goingLiveNeedsALogoAndSupportEmailAndReviveNeedsSuperAdmin() throws Exception {
		UUID id = createApp(slug());
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LIVE\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("not_ready_for_live"))
			.andExpect(jsonPath("$.fields.missing").value("logo,supportEmail"));

		mvc.perform(as(patch("/admin/api/v1/apps/{id}", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"supportEmail\":\"h@example.com\"}"));
		uploadLogo(id);
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LIVE\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));

		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"KILLED\"}"))
			.andExpect(status().isOk());
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LIVE\"}"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("only_super_admin_can_revive_killed_app"));
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LIVE\"}"))
			.andExpect(status().isOk());
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NOPE\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void logoIsProcessedStoredReplacedAndValidated() throws Exception {
		UUID id = createApp(slug());
		InMemoryObjectStorage store = (InMemoryObjectStorage) storage;

		String first = mvc.perform(as(multipart("/admin/api/v1/apps/{id}/logo", id).file(new MockMultipartFile("file", "l.png", "image/png", png(900, 300))), Role.ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.logoUrl").value(org.hamcrest.Matchers.startsWith("http://storage.test/apps/" + id + "/logo/")))
			.andReturn().getResponse().getContentAsString();
		UUID firstImage = UUID.fromString(JsonPath.read(first, "$.logoImageId"));
		assertThat(store.objects).containsKey(ObjectStorage.logoKey(id, firstImage));

		String second = mvc.perform(as(multipart("/admin/api/v1/apps/{id}/logo", id).file(new MockMultipartFile("file", "l.png", "image/png", png(200, 200))), Role.ADMIN))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		UUID secondImage = UUID.fromString(JsonPath.read(second, "$.logoImageId"));
		assertThat(store.objects).containsKey(ObjectStorage.logoKey(id, secondImage)).doesNotContainKey(ObjectStorage.logoKey(id, firstImage));

		mvc.perform(as(multipart("/admin/api/v1/apps/{id}/logo", id).file(new MockMultipartFile("file", "x.png", "image/png", "<html>".getBytes())), Role.ADMIN))
			.andExpect(status().isBadRequest());
		mvc.perform(as(multipart("/admin/api/v1/apps/{id}/logo", id).file(new MockMultipartFile("file", "big.png", "image/png", new byte[150_000])), Role.ADMIN))
			.andExpect(status().isPayloadTooLarge());
		mvc.perform(as(multipart("/admin/api/v1/apps/{id}/logo", id).file(new MockMultipartFile("file", "l.png", "image/png", png(10, 10))), Role.VIEWER))
			.andExpect(status().isForbidden());

		mvc.perform(as(delete("/admin/api/v1/apps/{id}/logo", id), Role.ADMIN)).andExpect(jsonPath("$.logoImageId").doesNotExist());
		assertThat(store.objects).doesNotContainKey(ObjectStorage.logoKey(id, secondImage));
	}

	// ---------- permissions ----------

	@Test
	void capabilitiesNeedReasonsWhereUsersSeeThemAndNotifyChannelsGetDefaultLimits() throws Exception {
		UUID id = createApp(slug());
		mvc.perform(get("/admin/api/v1/capabilities").header("X-Staff-Id", ADMIN_ID.toString()).header("X-Staff-Role", "VIEWER"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10));

		// sensitive capability: a plain-language reason is mandatory
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/user.full_name", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"GRANTED\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reason").exists());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/user.full_name", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("state", "GRANTED", "reason", Map.of("en", "So the technician can greet you by name")))))
			.andExpect(status().isOk());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/bogus.thing", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"GRANTED\"}"))
			.andExpect(status().isBadRequest());

		// granting SMS creates conservative default limits an admin can then change
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/notify.sms", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"GRANTED\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.notificationLimits[?(@.channel=='SMS')].maxPerUserPerDay").value(3))
			.andExpect(jsonPath("$.notificationLimits[?(@.channel=='SMS')].maxTotal").value(1000));

		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/notify.sms", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"SUSPENDED\"}"))
			.andExpect(status().isOk());
		mvc.perform(as(get("/admin/api/v1/apps/{id}", id), Role.VIEWER))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='notify.sms')].state").value("SUSPENDED"))
			.andExpect(jsonPath("$.capabilities[?(@.capability=='user.full_name')].state").value("GRANTED"));
	}

	@Test
	void notificationLimitsAreValidatedAndPerChannel() throws Exception {
		UUID id = createApp(slug());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/notification-limits/sms", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"maxPerUserPerDay\":2,\"maxPerDay\":50,\"maxDistinctUsersPerDay\":25,\"maxTotal\":500}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.channel").value("SMS")).andExpect(jsonPath("$.maxTotal").value(500));
		mvc.perform(as(put("/admin/api/v1/apps/{id}/notification-limits/EMAIL", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"maxPerDay\":null}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.maxPerDay").doesNotExist());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/notification-limits/SMS", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"maxPerDay\":-1,\"extra\":1}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.maxPerDay").exists()).andExpect(jsonPath("$.fields.extra").exists());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/notification-limits/PIGEON", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void allowedServerAddressesAreNormalisedAndTheEverythingRangeIsSandboxOnly() throws Exception {
		UUID id = createApp(slug());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/PROD", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("allowedCidrs", List.of("203.0.113.10", "198.51.100.0/24", "203.0.113.10")))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.allowedCidrs.length()").value(2))
			.andExpect(jsonPath("$.allowedCidrs[0]").value("203.0.113.10/32"));
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/PROD", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("allowedCidrs", List.of("0.0.0.0/0")))))
			.andExpect(status().isBadRequest());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/DEV", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("allowedCidrs", List.of("0.0.0.0/0")))))
			.andExpect(status().isOk());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/PROD", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("allowedCidrs", List.of("not-an-ip")))))
			.andExpect(status().isBadRequest());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/STAGING", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"allowedCidrs\":[]}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void paymentSettingsAreValidated() throws Exception {
		UUID id = createApp(slug());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/payment-settings", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("allowedCurrencies", List.of("etb", "USD"), "allowHold", true, "minAmountMinor", 1000, "maxAmountMinor", 500000))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.allowedCurrencies[0]").value("ETB")).andExpect(jsonPath("$.allowHold").value(true));
		mvc.perform(as(put("/admin/api/v1/apps/{id}/payment-settings", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("allowedCurrencies", List.of("EUR"), "minAmountMinor", 10, "maxAmountMinor", 5))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.allowedCurrencies").exists()).andExpect(jsonPath("$.fields.minAmountMinor").exists());
		mvc.perform(as(get("/admin/api/v1/apps/{id}", id), Role.VIEWER)).andExpect(jsonPath("$.payment.allowedCurrencies.length()").value(2));
	}

	// ---------- partner keys ----------

	@Test
	void partnerKeysAreRegisteredFromPublicKeysOnlyAndCanBeRevoked() throws Exception {
		UUID id = createApp(slug());
		String res = mvc.perform(as(post("/admin/api/v1/apps/{id}/keys", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("environment", "DEV", "publicKeyPem", publicKeyPem(), "label", "partner server 1"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.keyId").value(org.hamcrest.Matchers.startsWith("ak_dev_")))
			.andExpect(jsonPath("$.fingerprint").exists())
			.andExpect(jsonPath("$.publicKeyPem").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String keyId = JsonPath.read(res, "$.keyId");

		// a private key pasted by mistake is refused with a helpful message
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		String privatePem = PemKeys.privatePemOf(g.generateKeyPair().getPrivate().getEncoded());
		mvc.perform(as(post("/admin/api/v1/apps/{id}/keys", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("environment", "PROD", "publicKeyPem", privatePem))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.publicKeyPem").value(org.hamcrest.Matchers.containsString("PRIVATE")));
		g.initialize(1024);
		mvc.perform(as(post("/admin/api/v1/apps/{id}/keys", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("environment", "PROD", "publicKeyPem", PemKeys.pemOf(g.generateKeyPair().getPublic().getEncoded())))))
			.andExpect(status().isBadRequest());

		mvc.perform(as(get("/admin/api/v1/apps/{id}/keys", id), Role.VIEWER)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("ACTIVE"));
		mvc.perform(as(delete("/admin/api/v1/apps/{id}/keys/{k}", id, keyId), Role.VIEWER)).andExpect(status().isForbidden());
		mvc.perform(as(delete("/admin/api/v1/apps/{id}/keys/{k}", id, keyId), Role.ADMIN)).andExpect(jsonPath("$.status").value("REVOKED"));
		mvc.perform(get("/internal/partner-keys/{k}", keyId)).andExpect(jsonPath("$.usable").value(false));
	}

	@Test
	void generatedKeyHandsOverThePrivateHalfOnceAndStoresOnlyThePublicHalf() throws Exception {
		UUID id = createApp(slug());
		String res = mvc.perform(as(post("/admin/api/v1/apps/{id}/keys/generate", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"DEV\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.privateKeyPem").value(org.hamcrest.Matchers.startsWith("-----BEGIN PRIVATE KEY-----")))
			.andExpect(jsonPath("$.warning").exists())
			.andReturn().getResponse().getContentAsString();
		String keyId = JsonPath.read(res, "$.key.keyId");
		assertThat(mvc.perform(as(get("/admin/api/v1/apps/{id}/keys", id), Role.VIEWER)).andReturn().getResponse().getContentAsString()).doesNotContain("PRIVATE");
		assertThat(mvc.perform(get("/internal/partner-keys/{k}", keyId)).andReturn().getResponse().getContentAsString())
			.contains("BEGIN PUBLIC KEY").doesNotContain("PRIVATE");
	}

	@Test
	void anAppCannotHoldMoreThanFiveActiveKeysPerEnvironment() throws Exception {
		UUID id = createApp(slug());
		for (int i = 0; i < 5; i++) {
			mvc.perform(as(post("/admin/api/v1/apps/{id}/keys/generate", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"DEV\"}"))
				.andExpect(status().isOk());
		}
		mvc.perform(as(post("/admin/api/v1/apps/{id}/keys/generate", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"DEV\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("too_many_active_keys"));
		mvc.perform(as(post("/admin/api/v1/apps/{id}/keys/generate", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"environment\":\"PROD\"}"))
			.andExpect(status().isOk());
	}

	// ---------- internal API (for the delivery side) ----------

	@Test
	void internalContextTellsTheDeliverySideWhatAnAppMayDo() throws Exception {
		UUID id = createApp(slug());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/notify.inapp", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"GRANTED\"}"));
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/notify.email", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DENIED\"}"));
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/DEV", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("allowedCidrs", List.of("203.0.113.7")))));

		// this deployment is DEV: DEV works while the app is still a draft; the deployment's environment is used even without the parameter
		mvc.perform(get("/internal/apps/{id}/context", id))
			.andExpect(jsonPath("$.usable").value(true))
			.andExpect(jsonPath("$.grantedCapabilities.length()").value(1))
			.andExpect(jsonPath("$.grantedCapabilities[0]").value("notify.inapp"))
			.andExpect(jsonPath("$.allowedCidrs[0]").value("203.0.113.7/32"))
			.andExpect(jsonPath("$.notificationLimits[?(@.channel=='IN_APP')].maxPerDay").value(10000));
		// asking for the other environment is refused, not answered
		mvc.perform(get("/internal/apps/{id}/context", id).param("environment", "PROD"))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("wrong_environment"));
		mvc.perform(get("/internal/apps/{id}/context", id).param("environment", "TEST")).andExpect(status().isBadRequest());

		// killing an app switches off DEV too
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"KILLED\"}"));
		mvc.perform(get("/internal/apps/{id}/context", id).param("environment", "DEV")).andExpect(jsonPath("$.usable").value(false)).andExpect(jsonPath("$.denyReason").value("app_killed"));
		mvc.perform(get("/internal/apps/{id}/context", UUID.randomUUID()).param("environment", "DEV")).andExpect(status().isNotFound());
		mvc.perform(get("/internal/partner-keys/ak_dev_nope")).andExpect(status().isNotFound());
	}

	// ---------- what users see ----------

	@Test
	void usersSeeOnlyLiveAppsOrderedByPriorityWithTheirPermissions() throws Exception {
		UUID second = liveApp(20);
		UUID first = liveApp(10);
		UUID draft = createApp(slug());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/location.coarse", first), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("state", "GRANTED", "reason", Map.of("en", "To show places near you")))));
		mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/pay.request", first), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(json(Map.of("state", "DENIED"))));

		String list = mvc.perform(asUser(get("/api/v1/apps"), UUID.randomUUID())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(list, "$[*].id");
		assertThat(ids).contains(first.toString(), second.toString()).doesNotContain(draft.toString());
		assertThat(ids.indexOf(first.toString())).isLessThan(ids.indexOf(second.toString()));

		String slug = JsonPath.read(mvc.perform(as(get("/admin/api/v1/apps/{id}", first), Role.VIEWER)).andReturn().getResponse().getContentAsString(), "$.app.slug");
		String detail = mvc.perform(asUser(get("/api/v1/apps/{s}", slug), UUID.randomUUID())).andExpect(status().isOk())
			.andExpect(jsonPath("$.permissions.length()").value(1))
			.andExpect(jsonPath("$.permissions[0].capability").value("location.coarse"))
			.andExpect(jsonPath("$.permissions[0].reason.en").value("To show places near you"))
			.andExpect(jsonPath("$.logoUrl").exists())
			.andReturn().getResponse().getContentAsString();
		assertThat(detail).doesNotContain("allowedOrigins").doesNotContain("createdBy").doesNotContain("extra");

		mvc.perform(asUser(get("/api/v1/apps/{id}", draft), UUID.randomUUID())).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/apps")).andExpect(status().isUnauthorized());
	}

	@Test
	void reviewsAverageOnlyVisibleRatingsAndModerationAffectsTheScore() throws Exception {
		UUID app = liveApp(50);
		UUID u1 = UUID.randomUUID();
		UUID u2 = UUID.randomUUID();

		mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), u1).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"great\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.userId").doesNotExist());
		String r2 = mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), u2).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":2}"))
			.andReturn().getResponse().getContentAsString();
		UUID review2 = UUID.fromString(JsonPath.read(r2, "$.id"));
		mvc.perform(asUser(get("/api/v1/apps/{id}", app), u1)).andExpect(jsonPath("$.ratingAvg").value(3.5)).andExpect(jsonPath("$.ratingCount").value(2));

		// editing replaces, it does not add
		mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), u2).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}"));
		mvc.perform(asUser(get("/api/v1/apps/{id}", app), u1)).andExpect(jsonPath("$.ratingAvg").value(4.5)).andExpect(jsonPath("$.ratingCount").value(2));

		// hiding an abusive review removes it from the list and from the score
		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", review2), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}")).andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/apps/{id}", app), u1)).andExpect(jsonPath("$.ratingAvg").value(5.0)).andExpect(jsonPath("$.ratingCount").value(1));
		mvc.perform(asUser(get("/api/v1/apps/{id}/reviews", app), u1)).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(as(get("/admin/api/v1/apps/{id}/reviews", app), Role.VIEWER)).andExpect(jsonPath("$.length()").value(2));

		mvc.perform(asUser(delete("/api/v1/apps/{id}/my-review", app), u1)).andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/apps/{id}", app), u1)).andExpect(jsonPath("$.ratingCount").value(0)).andExpect(jsonPath("$.ratingAvg").value(0));
		mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", app), u1)).andExpect(status().isNotFound());

		for (String bad : List.of("{\"rating\":0}", "{\"rating\":6}", "{\"rating\":\"five\"}", "{}", "{\"rating\":3,\"x\":1}")) {
			mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), u1).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
		}
	}

	@Test
	void reportsAreDeduplicatedWhileOpenAndWorkedThroughByStaff() throws Exception {
		UUID app = liveApp(60);
		UUID user = UUID.randomUUID();
		String a = mvc.perform(asUser(post("/api/v1/apps/{id}/report", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"scam\",\"details\":\"asks for my PIN\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String b = mvc.perform(asUser(post("/api/v1/apps/{id}/report", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
			.andReturn().getResponse().getContentAsString();
		String reportId = JsonPath.read(a, "$.reportId");
		assertThat(JsonPath.<String>read(b, "$.reportId")).as("same open report returned").isEqualTo(reportId);

		mvc.perform(asUser(post("/api/v1/apps/{id}/report", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"nonsense\"}")).andExpect(status().isBadRequest());
		mvc.perform(as(get("/admin/api/v1/reports").param("status", "open"), Role.VIEWER)).andExpect(jsonPath("$[?(@.id=='" + reportId + "')].reason").value("SCAM"));
		mvc.perform(as(patch("/admin/api/v1/reports/{id}", reportId), Role.VIEWER).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVIEWED\"}")).andExpect(status().isForbidden());
		mvc.perform(as(patch("/admin/api/v1/reports/{id}", reportId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REVIEWED\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVIEWED")).andExpect(jsonPath("$.handledBy").value(ADMIN_ID.toString()));

		String c = mvc.perform(asUser(post("/api/v1/apps/{id}/report", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"BROKEN\"}")).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<String>read(c, "$.reportId")).as("a new report once the old one is handled").isNotEqualTo(reportId);
	}

	// ---------- audit ----------

	@Test
	void everyChangeIsAuditedAndOnlyAdminsCanReadTheLog() throws Exception {
		UUID id = createApp(slug());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/notification-limits/SMS", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"maxPerDay\":10}"));
		mvc.perform(as(get("/admin/api/v1/audit").param("target", id.toString()), Role.ADMIN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].action").value("limit.set"))
			.andExpect(jsonPath("$[1].action").value("app.create"))
			.andExpect(jsonPath("$[0].actorId").value(ADMIN_ID.toString()))
			.andExpect(jsonPath("$[0].actorRole").value("ADMIN"));
		mvc.perform(as(get("/admin/api/v1/audit"), Role.VIEWER)).andExpect(status().isForbidden());
	}

	// ---------- staff ----------

	@Test
	void creatingStaffRefusesUnknownFieldsAndReportsEveryProblemAtOnce() throws Exception {
		org.mockito.Mockito.clearInvocations(kratos);
		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"typo@example.com\",\"role\":\"ADMIN\",\"rol\":\"VIEWER\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.rol").value("unknown field"));
		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\",\"role\":\"OWNER\",\"x\":1}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.email").exists()).andExpect(jsonPath("$.fields.role").exists()).andExpect(jsonPath("$.fields.x").value("unknown field"));
		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.email").exists());
		verify(kratos, never()).createStaffIdentity(any(), any());
	}

	@Test
	void superAdminAddsStaffThroughKratosAndDisablingRevokesAccess() throws Exception {
		UUID identity = UUID.randomUUID();
		String email = "new-" + identity + "@example.com";
		when(kratos.findByEmail(email)).thenReturn(Optional.empty());
		when(kratos.createStaffIdentity(email, Role.VIEWER)).thenReturn(new KratosClient.IdentityRef(identity, email, "VIEWER"));

		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", " " + email.toUpperCase() + " ", "role", "viewer"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(identity.toString())).andExpect(jsonPath("$.role").value("VIEWER")).andExpect(jsonPath("$.email").value(email));
		verify(kratos).createStaffIdentity(email, Role.VIEWER);

		mvc.perform(as(patch("/admin/api/v1/staff/{id}", identity), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
			.andExpect(jsonPath("$.role").value("ADMIN"));
		verify(kratos).setRole(identity, Role.ADMIN);

		mvc.perform(as(patch("/admin/api/v1/staff/{id}", identity), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
			.andExpect(jsonPath("$.status").value("DISABLED"));
		verify(kratos).setRole(identity, null);
		verify(kratos).revokeSessions(identity);

		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\",\"role\":\"ADMIN\"}")).andExpect(status().isBadRequest());
		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.co\",\"role\":\"OWNER\"}")).andExpect(status().isBadRequest());
	}

	@Test
	void theLastSuperAdminCannotBeRemovedAndNobodyCanDemoteThemselves() throws Exception {
		staffRepo.deleteAll();
		UUID only = UUID.randomUUID();
		staffRepo.save(new Staff(only, "boss@example.com", Role.SUPER_ADMIN, null, Instant.now()));

		mvc.perform(patch("/admin/api/v1/staff/{id}", only).header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", "SUPER_ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("last_super_admin"));
		mvc.perform(patch("/admin/api/v1/staff/{id}", only).header("X-Staff-Id", only.toString()).header("X-Staff-Role", "SUPER_ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("cannot_demote_self"));
	}

	@Test
	void addingAnExistingStaffEmailCannotBypassTheSuperAdminProtections() throws Exception {
		staffRepo.deleteAll();
		UUID only = UUID.randomUUID();
		String email = "boss-" + only + "@example.com";
		staffRepo.save(new Staff(only, email, Role.SUPER_ADMIN, null, Instant.now()));
		when(kratos.findByEmail(email)).thenReturn(Optional.of(new KratosClient.IdentityRef(only, email, "SUPER_ADMIN")));

		// another super admin tries to demote the only super admin by "adding" them again with a lower role
		mvc.perform(post("/admin/api/v1/staff").header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", "SUPER_ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email, "role", "VIEWER"))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("last_super_admin"));
		// the same, done by themselves
		mvc.perform(post("/admin/api/v1/staff").header("X-Staff-Id", only.toString()).header("X-Staff-Role", "SUPER_ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email, "role", "ADMIN"))))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("cannot_demote_self"));
		// nothing changed, in Kratos or in the registry
		verify(kratos, never()).setRole(any(), any());
		org.assertj.core.api.Assertions.assertThat(staffRepo.findById(only).orElseThrow().getRole()).isEqualTo(Role.SUPER_ADMIN);
	}

	@Test
	void addingAnExistingStaffEmailStillWorksWhenItIsNotALoss() throws Exception {
		staffRepo.deleteAll();
		UUID boss = UUID.randomUUID();
		staffRepo.save(new Staff(boss, "boss-" + boss + "@example.com", Role.SUPER_ADMIN, null, Instant.now()));
		UUID member = UUID.randomUUID();
		String email = "member-" + member + "@example.com";
		staffRepo.save(new Staff(member, email, Role.VIEWER, null, Instant.now()));
		when(kratos.findByEmail(email)).thenReturn(Optional.of(new KratosClient.IdentityRef(member, email, "VIEWER")));
		// promoting an existing member (or re-adding with the same role) is fine and idempotent
		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email, "role", "ADMIN"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
		// demoting one of two super admins is allowed
		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email, "role", "SUPER_ADMIN"))))
			.andExpect(status().isOk());
		mvc.perform(as(post("/admin/api/v1/staff"), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("email", email, "role", "VIEWER"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"));
	}

	// ---------- gateway verification of staff sessions ----------

	private UUID activeStaff(Role role) {
		UUID id = UUID.randomUUID();
		staffRepo.save(new Staff(id, id + "@example.com", role, null, Instant.now()));
		return id;
	}

	@Test
	void staffVerifyAcceptsOnlyActiveStaffWhoCompletedTheAuthenticatorStep() throws Exception {
		UUID admin = activeStaff(Role.ADMIN);
		when(kratos.whoami("Bearer ok")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.VALID,
				new KratosClient.Session(admin, admin + "@example.com", "ADMIN", "aal2")));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer ok"))
			.andExpect(status().isOk()).andExpect(header().string("X-Staff-Id", admin.toString())).andExpect(header().string("X-Staff-Role", "ADMIN"));

		// password-less login done but the authenticator code not yet entered (aal1)
		when(kratos.whoami("Bearer aal1")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.VALID,
				new KratosClient.Session(admin, admin + "@example.com", "ADMIN", "aal1")));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer aal1")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("mfa_required"));

		// Kratos itself says the session needs the second step
		when(kratos.whoami("Bearer needs2")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.MFA_REQUIRED, null));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer needs2")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("mfa_required"));

		when(kratos.whoami("Bearer bad")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.INVALID, null));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/staff/verify")).andExpect(status().isUnauthorized());
	}

	@Test
	void aCustomerOrADisabledStaffMemberIsNotStaff() throws Exception {
		UUID customer = UUID.randomUUID();
		when(kratos.whoami("Bearer cust")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.VALID,
				new KratosClient.Session(customer, "c@example.com", null, "aal2")));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer cust")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("not_staff"));

		// has the role in Kratos but we disabled them here
		UUID gone = UUID.randomUUID();
		Staff s = new Staff(gone, "gone@example.com", Role.ADMIN, null, Instant.now());
		s.setStatus(Staff.Status.DISABLED, Instant.now());
		staffRepo.save(s);
		when(kratos.whoami("Bearer gone")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.VALID,
				new KratosClient.Session(gone, "gone@example.com", "ADMIN", "aal2")));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer gone")).andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("not_staff"));

		// a role string nobody defined grants nothing
		UUID odd = activeStaff(Role.VIEWER);
		when(kratos.whoami("Bearer odd")).thenReturn(new KratosClient.WhoamiResult(KratosClient.Outcome.VALID,
				new KratosClient.Session(odd, "o@example.com", "OWNER", "aal2")));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer odd")).andExpect(status().isForbidden());
	}

	@Test
	void kratosBeingDownIs503NotA401() throws Exception {
		when(kratos.whoami("Bearer boom")).thenThrow(new KratosClient.KratosUnavailableException("down", null));
		mvc.perform(get("/internal/staff/verify").header("Authorization", "Bearer boom")).andExpect(status().isServiceUnavailable());
	}

	@Test
	void healthIsUp() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

}
