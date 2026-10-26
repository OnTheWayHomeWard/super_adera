package app.adera.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.user.auth.ApiErrorAttributes;
import app.adera.user.auth.KratosClient;
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
@SpringBootTest(properties = { "adera.user.storage.type=memory", "adera.user.alias-hmac-secret=test-alias-secret", "adera.user.internal-secret=int-secret" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ErrorShapeTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	ApiErrorAttributes errorAttributes;

	@MockitoBean
	KratosClient kratos;

	private final UUID user = UUID.randomUUID();

	private <T extends AbstractMockHttpServletRequestBuilder<T>> T asUser(T request) {
		org.mockito.Mockito.when(kratos.identity(user)).thenReturn(Optional.of(new KratosClient.Identity(user, "u-" + user + "@example.com", "+251911" + (100000 + Math.abs(user.hashCode()) % 899999))));
		return request.header("X-User-Id", user.toString());
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T internal(T request) {
		return request.header("X-Internal-Secret", "int-secret");
	}

	@Test
	void aPathIdThatIsNotAUuidIs400InvalidRequest() throws Exception {
		mvc.perform(asUser(get("/api/v1/me/apps/demo-ride/preferences"))).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(internal(get("/internal/aliases/resolve")).param("appId", "nope").param("alias", "u_p_x")).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
	}

	@Test
	void aMissingQueryParameterIsAValidationError() throws Exception {
		mvc.perform(internal(get("/internal/aliases/resolve")).param("alias", "u_p_x")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.appId").value("is required"));
	}

	@Test
	void anUnknownRouteIs404NotFound() throws Exception {
		mvc.perform(asUser(get("/api/v1/me/nothing-here"))).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
		mvc.perform(internal(get("/internal/nothing-here"))).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
		mvc.perform(asUser(get("/api/v1/me/nothing-here")).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
	}

	@Test
	void aWrongMethodIs405WithAnAllowHeader() throws Exception {
		mvc.perform(asUser(delete("/api/v1/me"))).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error").value("method_not_allowed")).andExpect(header().exists("Allow"));
		mvc.perform(asUser(post("/api/v1/me/location"))).andExpect(status().isMethodNotAllowed()).andExpect(content().json("{\"error\":\"method_not_allowed\"}", true));
	}

	@Test
	void anUnsupportedContentTypeIs415() throws Exception {
		mvc.perform(asUser(put("/api/v1/me/devices").contentType(MediaType.TEXT_PLAIN).content("hello"))).andExpect(status().isUnsupportedMediaType())
			.andExpect(content().json("{\"error\":\"unsupported_media_type\"}", true));
	}

	@Test
	void aMissingOrUnreadableBodyIs400() throws Exception {
		// user-service has always called an unreadable body invalid_json; the shape is the point here
		mvc.perform(asUser(put("/api/v1/me/devices").contentType(MediaType.APPLICATION_JSON))).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_json\"}", true));
		mvc.perform(asUser(put("/api/v1/me/devices").contentType(MediaType.APPLICATION_JSON).content("{nope"))).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_json\"}", true));
	}

	@Test
	void whatSpringsErrorPageWouldAnswerHasTheSameShape() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		for (Map.Entry<Integer, String> e : Map.of(400, "invalid_request", 401, "unauthorized", 403, "forbidden", 404, "not_found", 405, "method_not_allowed",
				415, "unsupported_media_type", 500, "internal_error").entrySet()) {
			request.setAttribute("jakarta.servlet.error.status_code", e.getKey());
			assertThat(errorAttributes.getErrorAttributes(new ServletWebRequest(request), org.springframework.boot.web.error.ErrorAttributeOptions.defaults()))
				.as("status " + e.getKey()).isEqualTo(Map.of("error", e.getValue()));
		}
	}

}
