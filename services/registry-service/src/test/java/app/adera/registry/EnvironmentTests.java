package app.adera.registry;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** A deployment that is PROD (ADERA_ENV=prod): only PROD is active, and it needs the app status LIVE. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory", "adera.registry.environment=prod" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EnvironmentTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@MockitoBean
	KratosClient kratos;

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", role.name());
	}

	private UUID createApp() throws Exception {
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("slug", "e-" + UUID.randomUUID().toString().substring(0, 8), "name", Map.of("en", "Env app"),
					"description", Map.of("en", "d")))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	@Test
	void systemEndpointSaysWhichEnvironmentThisDeploymentIsForAnyStaffRole() throws Exception {
		mvc.perform(as(get("/admin/api/v1/system"), Role.VIEWER)).andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("PROD"));
	}

	@Test
	void prodDeploymentUsesProdAndRefusesToAnswerForDev() throws Exception {
		UUID id = createApp();
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/PROD", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedCidrs\":[\"203.0.113.9\"]}")).andExpect(status().isOk());
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/DEV", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"allowedCidrs\":[\"198.51.100.1\"]}")).andExpect(status().isOk());

		// a draft app is not usable in PROD, and the PROD addresses (not the DEV ones) are returned, with or without the parameter
		mvc.perform(get("/internal/apps/{id}/context", id)).andExpect(status().isOk())
			.andExpect(jsonPath("$.environment").value("PROD")).andExpect(jsonPath("$.usable").value(false))
			.andExpect(jsonPath("$.denyReason").value("app_draft")).andExpect(jsonPath("$.allowedCidrs[0]").value("203.0.113.9/32"));
		mvc.perform(get("/internal/apps/{id}/context", id).param("environment", "prod")).andExpect(jsonPath("$.environment").value("PROD"));
		mvc.perform(get("/internal/apps/{id}/context", id).param("environment", "DEV")).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("wrong_environment"));

		// keys can be registered for both environments; the key info says whether it is active here
		String devKey = JsonPath.read(mvc.perform(as(post("/admin/api/v1/apps/{id}/keys/generate", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"environment\":\"DEV\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.key.keyId");
		String prodKey = JsonPath.read(mvc.perform(as(post("/admin/api/v1/apps/{id}/keys/generate", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"environment\":\"PROD\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.key.keyId");
		mvc.perform(get("/internal/partner-keys/{k}", devKey)).andExpect(jsonPath("$.environment").value("DEV")).andExpect(jsonPath("$.activeHere").value(false))
			.andExpect(jsonPath("$.deploymentEnvironment").value("PROD")).andExpect(jsonPath("$.usable").value(true));
		mvc.perform(get("/internal/partner-keys/{k}", prodKey)).andExpect(jsonPath("$.environment").value("PROD")).andExpect(jsonPath("$.activeHere").value(true));

		// PROD works only while the app status is LIVE (DEV, in the other deployment, works for anything but KILLED)
		mvc.perform(as(patch("/admin/api/v1/apps/{id}", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"supportEmail\":\"help@example.com\"}"))
			.andExpect(status().isOk());
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(300, 300, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
		mvc.perform(as(multipart("/admin/api/v1/apps/{id}/logo", id).file(new org.springframework.mock.web.MockMultipartFile("file", "l.png", "image/png", out.toByteArray())), Role.ADMIN))
			.andExpect(status().isOk());
		setStatus(id, "LIVE");
		mvc.perform(get("/internal/apps/{id}/context", id)).andExpect(jsonPath("$.usable").value(true)).andExpect(jsonPath("$.denyReason").doesNotExist());
		setStatus(id, "PAUSED");
		mvc.perform(get("/internal/apps/{id}/context", id)).andExpect(jsonPath("$.usable").value(false)).andExpect(jsonPath("$.denyReason").value("app_paused"));
		setStatus(id, "KILLED");
		mvc.perform(get("/internal/apps/{id}/context", id)).andExpect(jsonPath("$.usable").value(false)).andExpect(jsonPath("$.denyReason").value("app_killed"));
	}

	private void setStatus(UUID id, String status) throws Exception {
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", id), Role.SUPER_ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void environmentsOfBothKindsAreStoredForEveryNewApp() throws Exception {
		UUID id = createApp();
		mvc.perform(as(get("/admin/api/v1/apps/{id}", id), Role.VIEWER)).andExpect(jsonPath("$.environments.length()").value(2))
			.andExpect(jsonPath("$.environments[0].environment").value("DEV")).andExpect(jsonPath("$.environments[1].environment").value("PROD"));
		// the old names are gone
		mvc.perform(as(put("/admin/api/v1/apps/{id}/environments/LIVE", id), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("allowedCidrs", List.of())))).andExpect(status().isBadRequest());
	}

}
