package app.adera.notification.partner;

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
import java.util.UUID;

import app.adera.notification.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.request.ServletWebRequest;

/** Every error a client can provoke without a controller is {"error": code}, never Spring's default JSON. */
@SpringBootTest(properties = { "adera.partner.workers-enabled=false", "adera.partner.gateway-secret=gw-secret",
		"adera.notification.dev-endpoints=true", "adera.notification.sms-provider=console", "adera.notification.email-provider=mailpit" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ErrorShapeTests {

	@Autowired MockMvc mvc;
	@Autowired ApiErrorAttributes errorAttributes;

	private static MockHttpServletRequestBuilder staff(MockHttpServletRequestBuilder b) {
		return b.header("X-Gateway-Secret", "gw-secret").header("X-Staff-Id", UUID.randomUUID().toString()).header("X-Staff-Role", "ADMIN");
	}

	private static MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder b) {
		return b.header("X-Gateway-Secret", "gw-secret").header("X-User-Id", UUID.randomUUID().toString());
	}

	@Test
	void aBadValueInThePathOrQueryIs400InvalidRequest() throws Exception {
		mvc.perform(user(put("/api/v1/me/notifications/not-a-uuid/read"))).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(user(get("/api/v1/me/notifications")).param("size", "many")).andExpect(status().isBadRequest()).andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(staff(get("/admin/api/v1/notification/notifications")).param("appId", "nope")).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
	}

	@Test
	void anUnknownRouteIs404NotFound() throws Exception {
		mvc.perform(user(get("/api/v1/me/nothing-here"))).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
		mvc.perform(staff(get("/admin/api/v1/notification/nothing-here"))).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
		mvc.perform(staff(get("/admin/api/v1/notification/nothing-here")).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound()).andExpect(content().json("{\"error\":\"not_found\"}", true));
	}

	@Test
	void aWrongMethodIs405WithAnAllowHeader() throws Exception {
		mvc.perform(staff(delete("/admin/api/v1/notification/notifications"))).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET"))
			.andExpect(content().json("{\"error\":\"method_not_allowed\"}", true));
		mvc.perform(user(post("/api/v1/me/notifications"))).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.error").value("method_not_allowed"));
	}

	@Test
	void anUnsupportedContentTypeIs415() throws Exception {
		mvc.perform(staff(post("/admin/api/v1/notification/templates").contentType(MediaType.TEXT_PLAIN).content("hello"))).andExpect(status().isUnsupportedMediaType())
			.andExpect(content().json("{\"error\":\"unsupported_media_type\"}", true));
	}

	@Test
	void aMissingOrUnreadableBodyIs400InvalidRequest() throws Exception {
		mvc.perform(staff(post("/admin/api/v1/notification/templates").contentType(MediaType.APPLICATION_JSON))).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
		mvc.perform(staff(post("/admin/api/v1/notification/templates").contentType(MediaType.APPLICATION_JSON).content("{nope"))).andExpect(status().isBadRequest())
			.andExpect(content().json("{\"error\":\"invalid_request\"}", true));
	}

	@Test
	void aCourierBodyThatFailsValidationIsAValidationError() throws Exception {
		mvc.perform(post("/internal/kratos/sms").contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"\",\"body\":\"\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.to").exists());
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
