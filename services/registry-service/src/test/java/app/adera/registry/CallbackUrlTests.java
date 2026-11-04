package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** The URL where a partner wants consent callbacks (user.consent_changed, user.disconnected), per environment, set by staff. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CallbackUrlTests {

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
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("slug", "cb-" + UUID.randomUUID().toString().substring(0, 8), "name", Map.of("en", "App"), "description", Map.of("en", "d")))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	private ResultActions env(UUID id, String env, Map<String, Object> body, Role role) throws Exception {
		return mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/{e}", id, env), role).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
	}

	@Test
	void theCallbackUrlIsStoredPerEnvironmentAndShownInTheAppDetail() throws Exception {
		UUID id = app();
		env(id, "DEV", Map.of("allowedCidrs", List.of("203.0.113.7"), "callbackUrl", "https://partner.example.com/adera/events"), Role.ADMIN)
			.andExpect(status().isOk()).andExpect(jsonPath("$.callbackUrl").value("https://partner.example.com/adera/events")).andExpect(jsonPath("$.allowedCidrs[0]").value("203.0.113.7/32"));
		mvc.perform(as(get("/admin/api/v1/apps/{id}", id), Role.VIEWER)).andExpect(status().isOk())
			.andExpect(jsonPath("$.environments[?(@.environment=='DEV')].callbackUrl").value("https://partner.example.com/adera/events"))
			.andExpect(jsonPath("$.environments[?(@.environment=='PROD')].callbackUrl").value(org.hamcrest.Matchers.hasItem((Object) null)));
	}

	@Test
	void leavingItOutKeepsItAndNullClearsItAndItCanBeSetAlone() throws Exception {
		UUID id = app();
		env(id, "DEV", Map.of("allowedCidrs", List.of("203.0.113.7"), "callbackUrl", "https://partner.example.com/e"), Role.ADMIN).andExpect(status().isOk());
		env(id, "DEV", Map.of("allowedCidrs", List.of("203.0.113.8")), Role.ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.callbackUrl").value("https://partner.example.com/e"));
		env(id, "DEV", Map.of("callbackUrl", "https://partner.example.com/other"), Role.ADMIN).andExpect(status().isOk())
			.andExpect(jsonPath("$.callbackUrl").value("https://partner.example.com/other")).andExpect(jsonPath("$.allowedCidrs[0]").value("203.0.113.8/32"));
		java.util.Map<String, Object> clear = new java.util.HashMap<>();
		clear.put("callbackUrl", null);
		env(id, "DEV", clear, Role.ADMIN).andExpect(status().isOk()).andExpect(jsonPath("$.callbackUrl").isEmpty());
		env(id, "DEV", Map.of(), Role.ADMIN).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.allowedCidrs").exists());
	}

	@Test
	void theUrlIsValidated() throws Exception {
		UUID id = app();
		for (String bad : List.of("ftp://partner.example.com/e", "not a url", "https://user:pw@partner.example.com/e", "https://", "http:/partner.example.com", "https://" + "a".repeat(500) + ".example.com/e")) {
			env(id, "DEV", Map.of("callbackUrl", bad), Role.ADMIN).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.callbackUrl").exists());
		}
		env(id, "DEV", Map.of("callbackUrl", 5), Role.ADMIN).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.callbackUrl").exists());
		// a DEV deployment may use plain http (a partner's local test server); the sender still refuses private addresses unless the dev flag is on
		env(id, "DEV", Map.of("callbackUrl", "http://localhost:9000/hook"), Role.ADMIN).andExpect(status().isOk());
	}

	@Test
	void onlyAdminsMayChangeItAndItIsAudited() throws Exception {
		UUID id = app();
		env(id, "DEV", Map.of("callbackUrl", "https://partner.example.com/e"), Role.VIEWER).andExpect(status().isForbidden());
		env(id, "DEV", Map.of("allowedCidrs", List.of("203.0.113.7"), "callbackUrl", "https://partner.example.com/e"), Role.ADMIN).andExpect(status().isOk());
		String log = mvc.perform(as(get("/admin/api/v1/audit").param("appId", id.toString()).param("action", "environment.set"), Role.ADMIN)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(log, "$[0].meta.callbackUrl")).isEqualTo("https://partner.example.com/e");
		assertThat((String) JsonPath.read(log, "$[0].meta.environment")).isEqualTo("DEV");
	}

	@Test
	void theInternalContextCarriesTheUrlOfThisDeploymentsEnvironmentOnly() throws Exception {
		UUID id = app();
		env(id, "DEV", Map.of("allowedCidrs", List.of("203.0.113.7"), "callbackUrl", "https://dev.partner.example.com/e"), Role.ADMIN).andExpect(status().isOk());
		env(id, "PROD", Map.of("allowedCidrs", List.of("198.51.100.7"), "callbackUrl", "https://prod.partner.example.com/e"), Role.ADMIN).andExpect(status().isOk());
		mvc.perform(get("/internal/apps/{id}/context", id)).andExpect(status().isOk()).andExpect(jsonPath("$.callbackUrl").value("https://dev.partner.example.com/e"));
		env(id, "DEV", new java.util.HashMap<>(Map.of("callbackUrl", "https://x.example.com/e")), Role.ADMIN);
		UUID none = app();
		mvc.perform(get("/internal/apps/{id}/context", none)).andExpect(jsonPath("$.callbackUrl").isEmpty());
	}

}
