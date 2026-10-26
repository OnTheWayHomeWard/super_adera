package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import app.adera.user.auth.KratosClient;
import app.adera.user.avatar.AvatarStorage;
import app.adera.user.avatar.InMemoryAvatarStorage;
import app.adera.user.device.DeviceRepository;
import app.adera.user.profile.ProfileService;
import app.adera.user.profile.UserRepository;
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

/** The whole API against a real Postgres, with fake storage and a mocked Kratos. */
@SpringBootTest(properties = {
		"adera.user.storage.type=memory",
		"adera.user.alias-hmac-secret=test-alias-secret",
		"adera.user.avatar.max-bytes=100000",
		"adera.user.extra-max-bytes=400"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UserApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	DeviceRepository devices;

	@Autowired
	ProfileService profiles;

	@Autowired
	AvatarStorage storage;

	@MockitoBean
	KratosClient kratos;

	// ---------- helpers ----------

	private static UUID newUser() {
		return UUID.randomUUID();
	}

	private void kratosKnows(UUID id, String email, String phone) {
		when(kratos.identity(id)).thenReturn(Optional.of(new KratosClient.Identity(id, email, phone)));
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, UUID id) {
		return request.header("X-User-Id", id.toString());
	}

	private static String uniquePhone() {
		return "+2519" + (10_000_000 + new Random().nextInt(89_999_999));
	}

	private static byte[] png(int w, int h) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
		return out.toByteArray();
	}

	private static MockMultipartFile file(byte[] bytes) {
		return new MockMultipartFile("file", "me.png", "image/png", bytes);
	}

	private static String json(String s) {
		return s;
	}

	// ---------- identity of the caller ----------

	@Test
	void apiRequiresAUserIdFromTheGateway() throws Exception {
		mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").header("X-User-Id", "not-a-uuid")).andExpect(status().isUnauthorized());
	}

	@Test
	void profileIsCreatedOnFirstUseFromKratosAndMarketIsInferred() throws Exception {
		UUID id = newUser();
		String phone = uniquePhone();
		kratosKnows(id, null, phone);

		mvc.perform(as(get("/api/v1/me"), id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.phone").value(phone))
			.andExpect(jsonPath("$.email").doesNotExist())
			.andExpect(jsonPath("$.market").value("ET"))
			.andExpect(jsonPath("$.language").value("en"))
			.andExpect(jsonPath("$.timezone").value("Africa/Addis_Ababa"))
			.andExpect(jsonPath("$.verificationTier").value("CONTACT"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.extra").isEmpty())
			.andExpect(jsonPath("$.avatarUrl").doesNotExist())
			.andExpect(jsonPath("$.location").doesNotExist());
		assertThat(users.findById(id)).isPresent();
	}

	@Test
	void usPhoneNumbersGetTheUsMarket() throws Exception {
		UUID id = newUser();
		kratosKnows(id, null, "+1" + (2_000_000_000L + new Random().nextInt(900_000_000)));
		mvc.perform(as(get("/api/v1/me"), id)).andExpect(jsonPath("$.market").value("US"));
	}

	@Test
	void anIdentityKratosDoesNotKnowIs404() throws Exception {
		mvc.perform(as(get("/api/v1/me"), newUser())).andExpect(status().isNotFound());
	}

	// ---------- the Kratos webhook ----------

	@Test
	void registrationWebhookCreatesTheProfileOnceAndIsIdempotent() throws Exception {
		UUID id = newUser();
		String body = "{\"identityId\":\"" + id + "\",\"email\":\"hook-" + id + "@example.com\",\"phone\":null}";
		mvc.perform(post("/internal/kratos/after-registration").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("created"));
		mvc.perform(post("/internal/kratos/after-registration").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(jsonPath("$.status").value("exists"));
		// profile now exists WITHOUT asking Kratos (it was never stubbed for this id)
		mvc.perform(as(get("/api/v1/me"), id)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("hook-" + id + "@example.com"));
	}

	@Test
	void concurrentFirstRequestsDoNotFailOrDuplicate() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "race-" + id + "@example.com", null);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Object>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				Callable<Object> task = () -> profiles.getOrCreate(id).getId();
				results.add(pool.submit(task));
			}
			for (Future<Object> f : results) {
				assertThat(f.get()).isEqualTo(id);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(users.findAll().stream().filter(u -> u.getId().equals(id))).hasSize(1);
	}

	// ---------- profile ----------

	@Test
	void patchUpdatesOnlyWhatIsSentAndValidates() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "p-" + id + "@example.com", null);
		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON)
			.content(json("{\"firstName\":\"Selam\",\"lastName\":\"Tadesse\",\"gender\":\"female\",\"dateOfBirth\":\"1995-04-12\",\"language\":\"am\",\"termsAcceptedVersion\":\"1.0\"}")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.firstName").value("Selam"))
			.andExpect(jsonPath("$.gender").value("FEMALE"))
			.andExpect(jsonPath("$.dateOfBirth").value("1995-04-12"))
			.andExpect(jsonPath("$.language").value("am"))
			.andExpect(jsonPath("$.termsAcceptedAt").exists());

		// second patch changes one field, leaves the rest, clears another with null
		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"firstName\":\"Selamawit\",\"lastName\":null}"))
			.andExpect(jsonPath("$.firstName").value("Selamawit"))
			.andExpect(jsonPath("$.lastName").doesNotExist())
			.andExpect(jsonPath("$.language").value("am"))
			.andExpect(jsonPath("$.gender").value("FEMALE"));
	}

	@Test
	void invalidPatchesAre400WithFieldMessagesAndChangeNothing() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "v-" + id + "@example.com", null);
		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"gender\":\"robot\",\"language\":\"fr\",\"nope\":1}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation_failed"))
			.andExpect(jsonPath("$.fields.gender").exists())
			.andExpect(jsonPath("$.fields.language").exists())
			.andExpect(jsonPath("$.fields.nope").value("unknown field"));
		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON).content("not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("invalid_json"));
		mvc.perform(as(get("/api/v1/me"), id)).andExpect(jsonPath("$.language").value("en"));
	}

	@Test
	void extraIsAFreeFormJsonColumnWithMergeSemanticsStoredAsJsonb() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "x-" + id + "@example.com", null);

		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"extra\":{\"occupation\":\"nurse\",\"prefs\":{\"theme\":\"dark\",\"font\":14},\"tags\":[\"a\",\"b\"]}}"))
			.andExpect(jsonPath("$.extra.occupation").value("nurse"))
			.andExpect(jsonPath("$.extra.prefs.theme").value("dark"));

		// merge: change a nested key, remove one with null, add one; untouched keys survive
		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"extra\":{\"prefs\":{\"font\":16},\"occupation\":null,\"church\":\"Kidist Mariam\"}}"))
			.andExpect(jsonPath("$.extra.prefs.theme").value("dark"))
			.andExpect(jsonPath("$.extra.prefs.font").value(16))
			.andExpect(jsonPath("$.extra.occupation").doesNotExist())
			.andExpect(jsonPath("$.extra.church").value("Kidist Mariam"))
			.andExpect(jsonPath("$.extra.tags[1]").value("b"));

		// it really round-trips through the database (jsonb), not just the response
		assertThat(users.findById(id).orElseThrow().getExtra()).containsKeys("prefs", "church", "tags").doesNotContainKey("occupation");

		// size limits apply to the merged result too
		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"extra\":{\"blob\":\"" + "x".repeat(500) + "\"}}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.extra").exists());
		mvc.perform(as(patch("/api/v1/me"), id).contentType(MediaType.APPLICATION_JSON).content("{\"extra\":[1,2]}"))
			.andExpect(status().isBadRequest());
	}

	// ---------- location ----------

	@Test
	void locationCanBeSharedUpdatedAndWithdrawn() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "l-" + id + "@example.com", null);

		mvc.perform(as(put("/api/v1/me/location"), id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"countryCode\":\"et\",\"region\":\"Addis Ababa\",\"city\":\"Addis Ababa\",\"latitude\":9.0054,\"longitude\":38.7636}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.location.countryCode").value("ET"))
			.andExpect(jsonPath("$.location.city").value("Addis Ababa"))
			.andExpect(jsonPath("$.location.latitude").value(9.0054))
			.andExpect(jsonPath("$.location.updatedAt").exists());

		mvc.perform(as(put("/api/v1/me/location"), id).contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"Hawassa\"}"))
			.andExpect(jsonPath("$.location.city").value("Hawassa"))
			.andExpect(jsonPath("$.location.latitude").doesNotExist());

		mvc.perform(as(delete("/api/v1/me/location"), id)).andExpect(jsonPath("$.location").doesNotExist());
		assertThat(users.findById(id).orElseThrow().getCity()).isNull();
	}

	@Test
	void badLocationsAreRejected() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "bl-" + id + "@example.com", null);
		for (String body : List.of("{}", "{\"latitude\":9.0}", "{\"latitude\":120,\"longitude\":10}", "{\"countryCode\":\"ETH\"}")) {
			mvc.perform(as(put("/api/v1/me/location"), id).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
		}
	}

	// ---------- avatar ----------

	@Test
	void avatarUploadStoresAProcessedImageLinksItByIdAndReplacesTheOldOne() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "a-" + id + "@example.com", null);
		InMemoryAvatarStorage store = (InMemoryAvatarStorage) storage;

		String first = mvc.perform(as(multipart("/api/v1/me/avatar").file(file(png(900, 600))), id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.avatarImageId").exists())
			.andExpect(jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.startsWith("http://storage.test/avatars/" + id + "/")))
			.andReturn().getResponse().getContentAsString();
		UUID firstImage = UUID.fromString(com.jayway.jsonpath.JsonPath.read(first, "$.avatarImageId"));
		String firstKey = AvatarStorage.keyFor(id, firstImage);
		assertThat(store.objects).containsKey(firstKey);
		assertThat(store.objects.get(firstKey)).as("stored as a re-encoded JPEG").startsWith((byte) 0xFF, (byte) 0xD8);

		// replacing removes the previous file so storage does not fill with orphans
		String second = mvc.perform(as(multipart("/api/v1/me/avatar").file(file(png(300, 300))), id))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		UUID secondImage = UUID.fromString(com.jayway.jsonpath.JsonPath.read(second, "$.avatarImageId"));
		assertThat(secondImage).isNotEqualTo(firstImage);
		assertThat(store.objects).containsKey(AvatarStorage.keyFor(id, secondImage)).doesNotContainKey(firstKey);

		mvc.perform(as(delete("/api/v1/me/avatar"), id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.avatarImageId").doesNotExist())
			.andExpect(jsonPath("$.avatarUrl").doesNotExist());
		assertThat(store.objects).doesNotContainKey(AvatarStorage.keyFor(id, secondImage));
	}

	@Test
	void avatarUploadRejectsNonImagesMissingFilesAndOversizedFiles() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "ar-" + id + "@example.com", null);

		mvc.perform(as(multipart("/api/v1/me/avatar").file(new MockMultipartFile("file", "x.png", "image/png", "<html>".getBytes())), id))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.file").exists());
		mvc.perform(as(multipart("/api/v1/me/avatar"), id)).andExpect(status().isBadRequest());

		byte[] big = new byte[150_000];
		new Random(1).nextBytes(big);
		mvc.perform(as(multipart("/api/v1/me/avatar").file(file(big)), id))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.error").value("file_too_large"));
		mvc.perform(as(get("/api/v1/me"), id)).andExpect(jsonPath("$.avatarImageId").doesNotExist());
	}

	// ---------- devices / FCM tokens ----------

	private static String device(String token, String installation) {
		return "{\"fcmToken\":\"" + token + "\",\"platform\":\"ANDROID\",\"installationId\":" + (installation == null ? "null" : "\"" + installation + "\"")
				+ ",\"appVersion\":\"1.0.3\",\"deviceName\":\"Pixel 7\",\"locale\":\"am-ET\"}";
	}

	@Test
	void registeringADeviceIsIdempotentAndNeverEchoesTheToken() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "d-" + id + "@example.com", null);
		String token = "tok-" + UUID.randomUUID();

		for (int i = 0; i < 2; i++) {
			mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(token, "inst-A")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.platform").value("ANDROID"))
				.andExpect(jsonPath("$.fcmToken").doesNotExist());
		}
		mvc.perform(as(get("/api/v1/me/devices"), id)).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].deviceName").value("Pixel 7"));
		assertThat(devices.findByFcmToken(token)).isPresent();
	}

	@Test
	void aRotatedTokenReplacesTheOldOneForTheSameInstallation() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "r-" + id + "@example.com", null);
		String oldToken = "old-" + UUID.randomUUID();
		String newToken = "new-" + UUID.randomUUID();

		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(oldToken, "inst-R"))).andExpect(status().isOk());
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(newToken, "inst-R"))).andExpect(status().isOk());

		mvc.perform(as(get("/api/v1/me/devices"), id)).andExpect(jsonPath("$.length()").value(1));
		assertThat(devices.findByFcmToken(oldToken)).as("old token gone, not left behind as a dead token").isEmpty();
		assertThat(devices.findByFcmToken(newToken)).isPresent();
	}

	@Test
	void aTokenMovesToTheNewAccountWhenAnotherPersonLogsInOnTheSamePhone() throws Exception {
		UUID first = newUser();
		UUID second = newUser();
		kratosKnows(first, "f-" + first + "@example.com", null);
		kratosKnows(second, "s-" + second + "@example.com", null);
		String token = "shared-" + UUID.randomUUID();

		mvc.perform(as(put("/api/v1/me/devices"), first).contentType(MediaType.APPLICATION_JSON).content(device(token, null))).andExpect(status().isOk());
		mvc.perform(as(put("/api/v1/me/devices"), second).contentType(MediaType.APPLICATION_JSON).content(device(token, null))).andExpect(status().isOk());

		mvc.perform(as(get("/api/v1/me/devices"), first)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(as(get("/api/v1/me/devices"), second)).andExpect(jsonPath("$.length()").value(1));
		assertThat(devices.findByFcmToken(token).orElseThrow().getUserId()).isEqualTo(second);
	}

	@Test
	void deviceValidationAndRemoval() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "dv-" + id + "@example.com", null);
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"\",\"platform\":\"ANDROID\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"t\",\"platform\":\"SYMBIAN\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"))
			.andExpect(jsonPath("$.fields.platform").value("must be one of ANDROID, IOS, WEB"));
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content("{not json"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_json"));

		String token = "del-" + UUID.randomUUID();
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(token, "inst-D"))).andExpect(status().isOk());
		mvc.perform(as(delete("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\"}"))
			.andExpect(jsonPath("$.removed").value(1));
		mvc.perform(as(delete("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\"}"))
			.andExpect(jsonPath("$.removed").value(0));
		mvc.perform(as(delete("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void aDeviceCanBeRemovedWithoutABodyByQueryParameter() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "dq-" + id + "@example.com", null);
		String token = "q-" + UUID.randomUUID();
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(token, "inst-Q"))).andExpect(status().isOk());
		mvc.perform(as(delete("/api/v1/me/devices"), id).param("installationId", "inst-Q")).andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(1));
		assertThat(devices.findByFcmToken(token)).isEmpty();

		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(token, null))).andExpect(status().isOk());
		mvc.perform(as(delete("/api/v1/me/devices"), id).param("fcmToken", token)).andExpect(jsonPath("$.removed").value(1));
		mvc.perform(as(delete("/api/v1/me/devices"), id).param("fcmToken", token)).andExpect(jsonPath("$.removed").value(0));

		// the body still works, and a body value wins over the query
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(token, null))).andExpect(status().isOk());
		mvc.perform(as(delete("/api/v1/me/devices"), id).param("fcmToken", "someone-elses-token").contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\"}"))
			.andExpect(jsonPath("$.removed").value(1));

		// nothing to identify the device by: a validation error that names both ways
		mvc.perform(as(delete("/api/v1/me/devices"), id)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
	}

	@Test
	void aUserCannotRemoveSomeoneElsesDevice() throws Exception {
		UUID owner = newUser();
		UUID other = newUser();
		kratosKnows(owner, "o-" + owner + "@example.com", null);
		kratosKnows(other, "ot-" + other + "@example.com", null);
		String token = "own-" + UUID.randomUUID();
		mvc.perform(as(put("/api/v1/me/devices"), owner).contentType(MediaType.APPLICATION_JSON).content(device(token, null))).andExpect(status().isOk());

		mvc.perform(as(delete("/api/v1/me/devices"), other).contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\"}"))
			.andExpect(jsonPath("$.removed").value(0));
		assertThat(devices.findByFcmToken(token)).isPresent();
	}

	// ---------- logout ----------

	@Test
	void logoutRemovesThePushTokenAndRevokesTheKratosSession() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "lo-" + id + "@example.com", null);
		String token = "lo-" + UUID.randomUUID();
		mvc.perform(as(put("/api/v1/me/devices"), id).contentType(MediaType.APPLICATION_JSON).content(device(token, "inst-L"))).andExpect(status().isOk());

		mvc.perform(as(post("/api/v1/me/logout"), id).header("Authorization", "Bearer session-xyz")
			.contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\":\"" + token + "\"}"))
			.andExpect(status().isNoContent());

		verify(kratos).logout("session-xyz");
		assertThat(devices.findByFcmToken(token)).isEmpty();
	}

	@Test
	void logoutNeedsTheBearerTokenAndWorksWithoutADeviceBody() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "lb-" + id + "@example.com", null);
		mvc.perform(as(post("/api/v1/me/logout"), id)).andExpect(status().isBadRequest());
		mvc.perform(as(post("/api/v1/me/logout"), id).header("Authorization", "Bearer tok-only")).andExpect(status().isNoContent());
		verify(kratos).logout("tok-only");
	}

	@Test
	void logoutWhenKratosIsDownIs503NotSilentlySuccessful() throws Exception {
		UUID id = newUser();
		kratosKnows(id, "ld-" + id + "@example.com", null);
		org.mockito.Mockito.doThrow(new KratosClient.KratosUnavailableException("down", null)).when(kratos).logout("t");
		mvc.perform(as(post("/api/v1/me/logout"), id).header("Authorization", "Bearer t")).andExpect(status().isServiceUnavailable());
	}

	// ---------- gateway verify ----------

	@Test
	void verifyAnswersWithTheUserIdForAValidSession() throws Exception {
		UUID id = newUser();
		when(kratos.whoami(eq("Bearer good"), any())).thenReturn(Optional.of(id));
		mvc.perform(get("/internal/auth/verify").header("Authorization", "Bearer good"))
			.andExpect(status().isOk())
			.andExpect(header().string("X-User-Id", id.toString()));
	}

	@Test
	void verifyRejectsInvalidOrMissingSessionsAndSignalsOutagesAs503() throws Exception {
		when(kratos.whoami(eq("Bearer bad"), any())).thenReturn(Optional.empty());
		mvc.perform(get("/internal/auth/verify").header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized());
		mvc.perform(get("/internal/auth/verify")).andExpect(status().isUnauthorized());

		when(kratos.whoami(eq("Bearer boom"), any())).thenThrow(new KratosClient.KratosUnavailableException("down", null));
		mvc.perform(get("/internal/auth/verify").header("Authorization", "Bearer boom")).andExpect(status().isServiceUnavailable());
	}

	@Test
	void healthIsUp() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

}
