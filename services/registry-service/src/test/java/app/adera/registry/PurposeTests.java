package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** The purpose sentence staff approve per granted capability (shown to users in the permission dialog). */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PurposeTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@MockitoBean
	KratosClient kratos;

	private static final UUID STAFF = UUID.randomUUID();

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", role.name());
	}

	private UUID app() throws Exception {
		String slug = "p-" + UUID.randomUUID().toString().substring(0, 8);
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("slug", slug, "name", Map.of("en", "App"), "description", Map.of("en", "d")))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	private org.springframework.test.web.servlet.ResultActions grant(UUID id, String cap, Map<String, Object> body) throws Exception {
		return mvc.perform(as(put("/admin/api/v1/apps/{id}/capabilities/{c}", id, cap), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(body)));
	}

	@Test
	void purposeIsStoredPerLanguageAndReturnedOnTheAppDetail() throws Exception {
		UUID id = app();
		grant(id, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "Find a driver near you.", "am", "ቅርብ ሹፌር ለማግኘት።")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capabilities[0].purpose.en").value("Find a driver near you."))
			.andExpect(jsonPath("$.capabilities[0].purpose.am").value("ቅርብ ሹፌር ለማግኘት።"))
			.andExpect(jsonPath("$.capabilities[0].reason").doesNotExist());
	}

	@Test
	void oldFieldNameReasonStillWorksAsAnInputAlias() throws Exception {
		UUID id = app();
		grant(id, "location.fine", Map.of("state", "GRANTED", "reason", Map.of("en", "Find a driver near you.")))
			.andExpect(status().isOk()).andExpect(jsonPath("$.capabilities[0].purpose.en").value("Find a driver near you."));
		grant(id, "location.fine", Map.of("state", "GRANTED", "reason", Map.of("en", "a"), "purpose", Map.of("en", "b")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.purpose").exists());
	}

	@Test
	void purposeIsValidated() throws Exception {
		UUID id = app();
		grant(id, "location.fine", Map.of("state", "GRANTED")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"))
			.andExpect(jsonPath("$.fields.purpose").exists());
		grant(id, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "short"))).andExpect(status().isBadRequest());
		grant(id, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "x".repeat(161)))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.purpose").value(org.hamcrest.Matchers.containsString("160")));
		grant(id, "notify.sms", Map.of("state", "GRANTED", "purpose", Map.of("en", "See https://evil.example/x to continue")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.purpose").value(org.hamcrest.Matchers.containsString("link")));
		grant(id, "notify.sms", Map.of("state", "GRANTED", "purpose", Map.of("en", "Visit www.evil.example now")))
			.andExpect(status().isBadRequest());
		grant(id, "notify.sms", Map.of("state", "GRANTED", "purpose", Map.of("en", "Line one\nline two is here")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.purpose").value(org.hamcrest.Matchers.containsString("one line")));
		grant(id, "notify.sms", Map.of("state", "GRANTED", "purpose", Map.of("fr", "Bonjour tout le monde")))
			.andExpect(status().isBadRequest());
		grant(id, "notify.sms", Map.of("state", "GRANTED", "purpose", "not an object")).andExpect(status().isBadRequest());
	}

	@Test
	void aGrantWithoutPurposeIsAllowedWhereNotRequired() throws Exception {
		UUID id = app();
		grant(id, "notify.sms", Map.of("state", "GRANTED")).andExpect(status().isOk())
			.andExpect(jsonPath("$.capabilities[0].purpose").isEmpty());
		// the dialog then shows the catalog text: the internal context carries label, purpose and the default
		mvc.perform(get("/internal/apps/{id}/context", id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.permissions.length()").value(1))
			.andExpect(jsonPath("$.permissions[0].capability").value("notify.sms"))
			.andExpect(jsonPath("$.permissions[0].label").value("Text messages (SMS)"))
			.andExpect(jsonPath("$.permissions[0].purpose").isEmpty())
			.andExpect(jsonPath("$.permissions[0].defaultPurpose").value("Send you text messages."));
	}

	@Test
	void internalContextListsOnlyGrantedCapabilitiesWithTheirPurpose() throws Exception {
		UUID id = app();
		grant(id, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "Find a driver near you."))).andExpect(status().isOk());
		grant(id, "notify.email", Map.of("state", "DENIED")).andExpect(status().isOk());
		grant(id, "user.basic", Map.of("state", "SUSPENDED")).andExpect(status().isOk());
		mvc.perform(get("/internal/apps/{id}/context", id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.permissions.length()").value(1))
			.andExpect(jsonPath("$.permissions[0].capability").value("location.fine"))
			.andExpect(jsonPath("$.permissions[0].label").value("Your precise location"))
			.andExpect(jsonPath("$.permissions[0].purpose.en").value("Find a driver near you."))
			.andExpect(jsonPath("$.grantedCapabilities.length()").value(1));
	}

	@Test
	void purposeChangesAreOnTheAuditTrailWithOldAndNewText() throws Exception {
		UUID id = app();
		grant(id, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "Find a driver near you."))).andExpect(status().isOk());
		grant(id, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "Find the closest driver."))).andExpect(status().isOk());
		String log = mvc.perform(as(get("/admin/api/v1/audit").param("appId", id.toString()).param("action", "capability.set"), Role.ADMIN))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((java.util.List<?>) JsonPath.read(log, "$")).hasSize(2);
		assertThat((String) JsonPath.read(log, "$[0].meta.purpose.en")).isEqualTo("Find the closest driver.");
		assertThat((String) JsonPath.read(log, "$[0].meta.previousPurpose.en")).isEqualTo("Find a driver near you.");
		assertThat((String) JsonPath.read(log, "$[0].meta.previousState")).isEqualTo("GRANTED");
		assertThat((String) JsonPath.read(log, "$[1].meta.purpose.en")).isEqualTo("Find a driver near you.");
		assertThat((Object) JsonPath.read(log, "$[1].meta.previousState")).isNull();
	}

	@Test
	void internalPermissionsEndpointAnswersByIdOrSlugWithStatusAndGrantedCapabilities() throws Exception {
		UUID id = app();
		grant(id, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "Find a driver near you."))).andExpect(status().isOk());
		grant(id, "notify.email", Map.of("state", "SUSPENDED")).andExpect(status().isOk());
		String slug = JsonPath.read(mvc.perform(as(get("/admin/api/v1/apps/{id}", id), Role.VIEWER)).andReturn().getResponse().getContentAsString(), "$.app.slug");
		mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/admin/api/v1/apps/{id}", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"entryUrl\":\"https://ride.example.com/app\"}")).andExpect(status().isOk());
		for (String key : new String[] { id.toString(), slug }) {
			mvc.perform(get("/internal/apps/{k}/permissions", key)).andExpect(status().isOk())
				.andExpect(jsonPath("$.appId").value(id.toString())).andExpect(jsonPath("$.slug").value(slug))
				.andExpect(jsonPath("$.name.en").value("App")).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.entryUrl").value("https://ride.example.com/app"))
				.andExpect(jsonPath("$.permissions.length()").value(1))
				.andExpect(jsonPath("$.permissions[0].capability").value("location.fine"))
				.andExpect(jsonPath("$.permissions[0].label").value("Your precise location"))
				.andExpect(jsonPath("$.permissions[0].purpose.en").value("Find a driver near you."))
				.andExpect(jsonPath("$.permissions[0].defaultPurpose").value("Use your exact position while the app is open."));
		}
		mvc.perform(get("/internal/apps/{k}/permissions", "no-such-app")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
		mvc.perform(get("/internal/apps/{k}/permissions", UUID.randomUUID())).andExpect(status().isNotFound());
	}

	@Test
	void internalAppsByIdsReturnsTheKnownOnesAndSkipsTheRest() throws Exception {
		UUID a = app();
		UUID b = app();
		grant(a, "location.fine", Map.of("state", "GRANTED", "purpose", Map.of("en", "Find a driver near you."))).andExpect(status().isOk());
		String body = mvc.perform(get("/internal/apps").param("ids", a + "," + b + "," + UUID.randomUUID())).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat((java.util.List<?>) JsonPath.read(body, "$")).hasSize(2);
		assertThat((java.util.List<String>) JsonPath.read(body, "$[*].appId")).containsExactlyInAnyOrder(a.toString(), b.toString());
		mvc.perform(get("/internal/apps").param("ids", "not-a-uuid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed"));
		mvc.perform(get("/internal/apps").param("ids", "")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

}
