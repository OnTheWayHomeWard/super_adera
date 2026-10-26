package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** Review and report behaviour: author view, moderation, report handling and its audit trail. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReviewReportTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@Autowired
	JdbcClient jdbc;

	@MockitoBean
	KratosClient kratos;

	private static final UUID STAFF_ID = UUID.randomUUID();

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", STAFF_ID.toString()).header("X-Staff-Role", role.name());
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T asUser(T request, UUID userId) {
		return request.header("X-User-Id", userId.toString());
	}

	private UUID liveApp() throws Exception {
		String slug = "r-" + UUID.randomUUID().toString().substring(0, 8);
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("slug", slug, "name", Map.of("en", "App " + slug)))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(res, "$.id"));
		jdbc.sql("UPDATE mini_app SET status = 'LIVE' WHERE id = :id").param("id", id).update();
		return id;
	}

	private String report(UUID app, UUID user, String body) throws Exception {
		return mvc.perform(asUser(post("/api/v1/apps/{id}/report", app), user).contentType(MediaType.APPLICATION_JSON).content(body))
			.andReturn().getResponse().getContentAsString();
	}

	private String handle(String reportId, String body, Role role) throws Exception {
		return mvc.perform(as(patch("/admin/api/v1/reports/{id}", reportId), role).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void reopeningAReportClearsWhoHandledItAndWhen() throws Exception {
		UUID app = liveApp();
		String reportId = JsonPath.read(report(app, UUID.randomUUID(), "{\"reason\":\"SPAM\"}"), "$.reportId");

		String handled = handle(reportId, "{\"status\":\"REVIEWED\"}", Role.ADMIN);
		assertThat(JsonPath.<String>read(handled, "$.handledBy")).isEqualTo(STAFF_ID.toString());
		assertThat(JsonPath.<String>read(handled, "$.handledAt")).isNotNull();

		String reopened = handle(reportId, "{\"status\":\"OPEN\"}", Role.ADMIN);
		assertThat(JsonPath.<String>read(reopened, "$.status")).isEqualTo("OPEN");
		assertThat(JsonPath.<Object>read(reopened, "$.handledBy")).isNull();
		assertThat(JsonPath.<Object>read(reopened, "$.handledAt")).isNull();

		mvc.perform(as(get("/admin/api/v1/reports").param("appId", app.toString()), Role.VIEWER))
			.andExpect(jsonPath("$[0].status").value("OPEN")).andExpect(jsonPath("$[0].handledBy").doesNotExist());

		// the reopen is audited with the new status
		mvc.perform(as(get("/admin/api/v1/audit").param("target", reportId), Role.ADMIN)).andExpect(status().isOk())
			.andExpect(jsonPath("$[0].action").value("report.handle")).andExpect(jsonPath("$[0].meta.status").value("OPEN"));
	}

}
