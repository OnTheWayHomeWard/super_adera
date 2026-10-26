package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import app.adera.registry.common.ApiErrorAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.web.context.request.ServletWebRequest;

/** Every error a client can provoke without a controller is {"error": code}, never Spring's default JSON. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ErrorShapeTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	ApiErrorAttributes errorAttributes;

	@MockitoBean
	KratosClient kratos;

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", role.name());
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T asUser(T request) {
		return request.header("X-User-Id", UUID.randomUUID().toString());
	}

	@Test
	void aPathIdThatIsNotAUuidIs400InvalidRequest() throws Exception {
		mvc.perform(as(get("/admin/api/v1/apps/not-a-uuid"), Role.ADMIN)).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(as(patch("/admin/api/v1/reviews/not-a-uuid").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"), Role.ADMIN))
			.andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(as(get("/admin/api/v1/reviews").param("appId", "nope"), Role.VIEWER)).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}", true));
	}

	@Test
	void anUnknownRouteIs404NotFound() throws Exception {
		mvc.perform(as(get("/admin/api/v1/nothing-here"), Role.ADMIN)).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
		mvc.perform(asUser(get("/api/v1/nothing-here"))).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
		mvc.perform(as(get("/admin/api/v1/nothing-here"), Role.ADMIN).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
	}

	@Test
	void aWrongMethodIs405WithAnAllowHeader() throws Exception {
		mvc.perform(as(delete("/admin/api/v1/audit"), Role.ADMIN)).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET"))
			.andExpect(content().json("{\"error\":\"method_not_allowed\"}", true));
		mvc.perform(asUser(put("/api/v1/apps"))).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error").value("method_not_allowed"));
	}

	@Test
	void anUnsupportedContentTypeIs415() throws Exception {
		mvc.perform(as(post("/admin/api/v1/apps").contentType(MediaType.TEXT_PLAIN).content("hello"), Role.ADMIN)).andExpect(status().isUnsupportedMediaType())
			.andExpect(content().json("{\"error\":\"unsupported_media_type\"}", true));
		mvc.perform(as(post("/admin/api/v1/apps").contentType(MediaType.APPLICATION_XML).content("<a/>"), Role.ADMIN)).andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void aMissingOrUnreadableBodyIs400InvalidRequest() throws Exception {
		mvc.perform(as(post("/admin/api/v1/apps").contentType(MediaType.APPLICATION_JSON), Role.ADMIN)).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(as(post("/admin/api/v1/apps").contentType(MediaType.APPLICATION_JSON).content("{not json"), Role.ADMIN)).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(as(patch("/admin/api/v1/apps/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("[1]"), Role.ADMIN)).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON))).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
	}

	@Test
	void whatSpringsErrorPageWouldAnswerHasTheSameShape() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		for (Map.Entry<Integer, String> e : Map.of(400, "invalid_request", 401, "unauthorized", 403, "forbidden", 404, "not_found", 405, "method_not_allowed",
				415, "unsupported_media_type", 500, "internal_error", 503, "internal_error").entrySet()) {
			request.setAttribute("jakarta.servlet.error.status_code", e.getKey());
			assertThat(errorAttributes.getErrorAttributes(new ServletWebRequest(request), org.springframework.boot.web.error.ErrorAttributeOptions.defaults()))
				.as("status " + e.getKey()).isEqualTo(Map.of("error", e.getValue()));
		}
	}

}
