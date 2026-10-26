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

	@Test
	void aSecondReportWhileOneIsOpenReturnsTheExistingOneAndSaysSo() throws Exception {
		UUID app = liveApp();
		UUID user = UUID.randomUUID();
		String first = report(app, user, "{\"reason\":\"BROKEN\"}");
		assertThat(JsonPath.<Boolean>read(first, "$.received")).isTrue();
		assertThat(JsonPath.<Boolean>read(first, "$.alreadyReported")).isFalse();
		assertThat(JsonPath.<String>read(first, "$.status")).isEqualTo("received");

		mvc.perform(asUser(post("/api/v1/apps/{id}/report", app), user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SCAM\",\"details\":\"new words\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.received").value(true)).andExpect(jsonPath("$.alreadyReported").value(true))
			.andExpect(jsonPath("$.reportId").value((String) JsonPath.read(first, "$.reportId")))
			.andExpect(jsonPath("$.reason").value("BROKEN"));

		// once staff handled it, the next report is a new one again
		handle(JsonPath.read(first, "$.reportId"), "{\"status\":\"DISMISSED\"}", Role.ADMIN);
		String third = report(app, user, "{\"reason\":\"SCAM\"}");
		assertThat(JsonPath.<Boolean>read(third, "$.alreadyReported")).isFalse();
		assertThat(JsonPath.<String>read(third, "$.reportId")).isNotEqualTo(JsonPath.<String>read(first, "$.reportId"));
	}

	@Test
	void theAuthorSeesTheStatusOfTheirReviewAndEditingDoesNotUnhideIt() throws Exception {
		UUID app = liveApp();
		UUID author = UUID.randomUUID();
		String put = mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), author).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1,\"comment\":\"bad\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VISIBLE")).andExpect(jsonPath("$.message").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		String reviewId = JsonPath.read(put, "$.id");
		mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", app), author)).andExpect(jsonPath("$.status").value("VISIBLE"));

		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", reviewId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}")).andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", app), author)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("HIDDEN")).andExpect(jsonPath("$.message").isNotEmpty());

		// editing keeps it hidden and says so; the public list and the rating still exclude it
		mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), author).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"fine\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(5)).andExpect(jsonPath("$.status").value("HIDDEN")).andExpect(jsonPath("$.message").isNotEmpty());
		mvc.perform(asUser(get("/api/v1/apps/{id}/reviews", app), UUID.randomUUID())).andExpect(jsonPath("$.length()").value(0))
			.andExpect(jsonPath("$[0].status").doesNotExist());
		mvc.perform(asUser(get("/api/v1/apps/{id}", app), author)).andExpect(jsonPath("$.ratingCount").value(0));

		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", reviewId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"VISIBLE\"}")).andExpect(status().isOk());
		mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", app), author)).andExpect(jsonPath("$.status").value("VISIBLE")).andExpect(jsonPath("$.message").doesNotExist());
	}

	private void setStatus(UUID app, String status) throws Exception {
		mvc.perform(as(post("/admin/api/v1/apps/{id}/status", app), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"))
			.andExpect(status().isOk());
	}

	@Test
	void authorsCanReadAndDeleteTheirReviewOfAPausedOrKilledAppButNotEditOrBrowse() throws Exception {
		for (String state : new String[] { "PAUSED", "KILLED" }) {
			UUID app = liveApp();
			UUID author = UUID.randomUUID();
			mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), author).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":3}")).andExpect(status().isOk());
			setStatus(app, state);

			mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", app), author)).andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(3));
			// still refused: editing, the public catalog, the public review list and reporting
			mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), author).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}")).andExpect(status().isNotFound());
			mvc.perform(asUser(get("/api/v1/apps/{id}", app), author)).andExpect(status().isNotFound());
			mvc.perform(asUser(get("/api/v1/apps/{id}/reviews", app), author)).andExpect(status().isNotFound());
			mvc.perform(asUser(post("/api/v1/apps/{id}/report", app), author).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isNotFound());

			mvc.perform(asUser(delete("/api/v1/apps/{id}/my-review", app), author)).andExpect(status().isOk());
			mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", app), author)).andExpect(status().isNotFound());
		}
		// a DRAFT app was never published: still 404
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"slug\":\"r-draft-" + UUID.randomUUID().toString().substring(0, 6) + "\",\"name\":{\"en\":\"Draft\"}}")).andReturn().getResponse().getContentAsString();
		mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", (String) JsonPath.read(res, "$.id")), UUID.randomUUID())).andExpect(status().isNotFound());
	}

	@Test
	void staffCanRecordAReasonWhenHidingAReviewOrHandlingAReport() throws Exception {
		UUID app = liveApp();
		UUID author = UUID.randomUUID();
		String reviewId = JsonPath.read(mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", app), author).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1,\"comment\":\"x\"}"))
			.andReturn().getResponse().getContentAsString(), "$.id");

		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", reviewId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\",\"reason\":\"  spam link  \"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.moderationReason").value("spam link"));
		mvc.perform(as(get("/admin/api/v1/reviews").param("appId", app.toString()), Role.VIEWER)).andExpect(jsonPath("$[0].moderationReason").value("spam link"));
		mvc.perform(as(get("/admin/api/v1/apps/{id}/reviews", app), Role.VIEWER)).andExpect(jsonPath("$[0].moderationReason").value("spam link"));
		mvc.perform(as(get("/admin/api/v1/audit").param("target", reviewId), Role.ADMIN)).andExpect(jsonPath("$[0].meta.reason").value("spam link"));
		// never visible to the user side
		String mine = mvc.perform(asUser(get("/api/v1/apps/{id}/my-review", app), author)).andReturn().getResponse().getContentAsString();
		assertThat(mine).doesNotContain("spam link").doesNotContain("moderationReason");
		assertThat(mvc.perform(asUser(get("/api/v1/apps/{id}/reviews", app), author)).andReturn().getResponse().getContentAsString()).doesNotContain("spam link");

		// a restore without a reason replaces the stored note (the audit log keeps the history)
		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", reviewId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"VISIBLE\"}"))
			.andExpect(jsonPath("$.moderationReason").doesNotExist());

		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", reviewId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\",\"reason\":\"" + "x".repeat(501) + "\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reason").exists());
		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", reviewId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\",\"reason\":5}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reason").exists());
		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", reviewId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\",\"reason\":\"" + "x".repeat(500) + "\"}"))
			.andExpect(status().isOk());

		String reportId = JsonPath.read(report(app, author, "{\"reason\":\"SCAM\"}"), "$.reportId");
		mvc.perform(as(patch("/admin/api/v1/reports/{id}", reportId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISMISSED\",\"reason\":\"checked, app is fine\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.handlingReason").value("checked, app is fine")).andExpect(jsonPath("$.reason").value("SCAM"));
		mvc.perform(as(get("/admin/api/v1/reports").param("appId", app.toString()), Role.VIEWER)).andExpect(jsonPath("$[0].handlingReason").value("checked, app is fine"));
		mvc.perform(as(get("/admin/api/v1/audit").param("target", reportId), Role.ADMIN)).andExpect(jsonPath("$[0].meta.reason").value("checked, app is fine"));
		mvc.perform(as(patch("/admin/api/v1/reports/{id}", reportId), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"OPEN\",\"reason\":\"" + "y".repeat(501) + "\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.reason").exists());
	}

}
