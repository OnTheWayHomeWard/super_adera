package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import app.adera.registry.staff.Staff;
import app.adera.registry.staff.StaffRepository;
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

/** The dashboard-support endpoints: counts, cross-app moderation lists, audit filters, internal audit writes. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BackofficeApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@Autowired
	StaffRepository staffRepo;

	@MockitoBean
	KratosClient kratos;

	private static final UUID STAFF_ID = UUID.randomUUID();

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", STAFF_ID.toString()).header("X-Staff-Role", role.name());
	}

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T asUser(T request, UUID userId) {
		return request.header("X-User-Id", userId.toString());
	}

	private UUID createApp() throws Exception {
		String slug = "b-" + UUID.randomUUID().toString().substring(0, 8);
		String res = mvc.perform(as(post("/admin/api/v1/apps"), Role.ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(mapper.writeValueAsString(Map.of("slug", slug, "name", Map.of("en", "App " + slug)))))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	private long dashboardNumber(String path) throws Exception {
		String res = mvc.perform(as(get("/admin/api/v1/dashboard"), Role.VIEWER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(res, path)).longValue();
	}

	/** A DRAFT app needs a logo to go live, so make one LIVE directly in the table: reviews/reports need a LIVE app. */
	private UUID liveApp(org.springframework.jdbc.core.simple.JdbcClient jdbc) throws Exception {
		UUID id = createApp();
		jdbc.sql("UPDATE mini_app SET status = 'LIVE' WHERE id = :id").param("id", id).update();
		return id;
	}

	@Autowired
	org.springframework.jdbc.core.simple.JdbcClient jdbc;

	@Test
	void dashboardCountsAreRealAndReadableByViewer() throws Exception {
		long drafts = dashboardNumber("$.apps.DRAFT");
		long total = dashboardNumber("$.apps.total");
		UUID app = createApp();
		assertThat(dashboardNumber("$.apps.DRAFT")).isEqualTo(drafts + 1);
		assertThat(dashboardNumber("$.apps.total")).isEqualTo(total + 1);
		String res = mvc.perform(as(get("/admin/api/v1/dashboard"), Role.VIEWER)).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<java.util.List<String>>read(res, "$.draftApps[?(@.id=='" + app + "')].missingForLive[*]")).containsExactly("logo", "supportEmail");
		assertThat(JsonPath.<java.util.List<?>>read(res, "$.draftApps").size()).isLessThanOrEqualTo(10);

		long openReports = dashboardNumber("$.reports.open");
		long reviews = dashboardNumber("$.reviews.total");
		UUID live = liveApp(jdbc);
		UUID user = UUID.randomUUID();
		mvc.perform(asUser(post("/api/v1/apps/{id}/report", live), user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}")).andExpect(status().isOk());
		mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", live), user).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4,\"comment\":\"ok\"}")).andExpect(status().isOk());
		assertThat(dashboardNumber("$.reports.open")).isEqualTo(openReports + 1);
		assertThat(dashboardNumber("$.reviews.total")).isEqualTo(reviews + 1);
		assertThat(dashboardNumber("$.reviews.last7Days")).isGreaterThanOrEqualTo(1);
		assertThat(dashboardNumber("$.apps.LIVE")).isGreaterThanOrEqualTo(1);
	}

	@Test
	void crossAppReviewsAndReportsCarryTheSlugAndFilter() throws Exception {
		UUID a = liveApp(jdbc);
		UUID b = liveApp(jdbc);
		UUID user = UUID.randomUUID();
		String ra = mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", a), user).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}")).andReturn().getResponse().getContentAsString();
		mvc.perform(asUser(put("/api/v1/apps/{id}/my-review", b), user).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1}"));
		mvc.perform(asUser(post("/api/v1/apps/{id}/report", b), user).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SCAM\"}"));
		mvc.perform(as(get("/admin/api/v1/reviews").param("appId", a.toString()), Role.VIEWER)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].rating").value(5)).andExpect(jsonPath("$[0].appSlug").isString())
			.andExpect(jsonPath("$[0].createdAt").isString());
		mvc.perform(as(patch("/admin/api/v1/reviews/{id}", (String) JsonPath.read(ra, "$.id")), Role.ADMIN).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HIDDEN\"}"));
		mvc.perform(as(get("/admin/api/v1/reviews").param("appId", a.toString()).param("status", "visible"), Role.VIEWER)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(as(get("/admin/api/v1/reviews").param("appId", a.toString()).param("status", "hidden"), Role.VIEWER)).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(as(get("/admin/api/v1/reviews").param("status", "bogus"), Role.VIEWER)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.status").exists());
		mvc.perform(as(get("/admin/api/v1/reports").param("appId", b.toString()), Role.VIEWER)).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].reason").value("SCAM")).andExpect(jsonPath("$[0].appSlug").isString()).andExpect(header().string("X-Total-Count", "1"));
		mvc.perform(as(get("/admin/api/v1/reviews").param("appId", b.toString()).param("size", "1"), Role.VIEWER)).andExpect(header().string("X-Total-Count", "1"));
		mvc.perform(as(get("/admin/api/v1/reports").param("appId", a.toString()), Role.VIEWER)).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void auditFiltersByActorActionTargetTypeAndDates() throws Exception {
		UUID actor = UUID.randomUUID();
		staffRepo.save(new Staff(actor, "audit.actor@example.com", Role.ADMIN, null, Instant.now()));
		UUID app = createApp();
		// write two events as that actor
		mvc.perform(post("/admin/api/v1/apps/{id}/status", app).header("X-Staff-Id", actor.toString()).header("X-Staff-Role", "ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAUSED\"}")).andExpect(status().isOk());
		mvc.perform(patch("/admin/api/v1/apps/{id}", app).header("X-Staff-Id", actor.toString()).header("X-Staff-Role", "ADMIN")
			.contentType(MediaType.APPLICATION_JSON).content("{\"priority\":7}")).andExpect(status().isOk());

		mvc.perform(as(get("/admin/api/v1/audit").param("actor", actor.toString()), Role.ADMIN)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].actorEmail").value("audit.actor@example.com"))
			.andExpect(jsonPath("$[0].action").value("app.update")).andExpect(jsonPath("$[1].action").value("app.status"));
		mvc.perform(as(get("/admin/api/v1/audit").param("actor", actor.toString()).param("action", "app.status"), Role.ADMIN)).andExpect(jsonPath("$.length()").value(1));
		mvc.perform(as(get("/admin/api/v1/audit").param("actor", actor.toString()).param("action", "app."), Role.ADMIN)).andExpect(jsonPath("$.length()").value(2));
		mvc.perform(as(get("/admin/api/v1/audit").param("target", app.toString()).param("targetType", "mini_app"), Role.ADMIN)).andExpect(jsonPath("$.length()").value(3));
		mvc.perform(as(get("/admin/api/v1/audit").param("target", app.toString()).param("targetType", "staff"), Role.ADMIN)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(as(get("/admin/api/v1/audit").param("target", app.toString()).param("limit", "1").param("offset", "1"), Role.ADMIN))
			.andExpect(header().string("X-Total-Count", "3")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].action").value("app.status"));
		// "%" is not a wildcard
		mvc.perform(as(get("/admin/api/v1/audit").param("action", "%"), Role.ADMIN)).andExpect(jsonPath("$.length()").value(0));
		// date range: everything so far is in the past of tomorrow and after yesterday; nothing is before yesterday
		String yesterday = Instant.now().minusSeconds(86_400).toString();
		String tomorrow = Instant.now().plusSeconds(86_400).toString();
		mvc.perform(as(get("/admin/api/v1/audit").param("actor", actor.toString()).param("from", yesterday).param("to", tomorrow), Role.ADMIN)).andExpect(jsonPath("$.length()").value(2));
		mvc.perform(as(get("/admin/api/v1/audit").param("actor", actor.toString()).param("to", yesterday), Role.ADMIN)).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(as(get("/admin/api/v1/audit").param("actor", actor.toString()).param("from", tomorrow), Role.ADMIN)).andExpect(jsonPath("$.length()").value(0));
		// validation and roles
		mvc.perform(as(get("/admin/api/v1/audit").param("actor", "nope").param("from", "yesterday"), Role.ADMIN)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation_failed")).andExpect(jsonPath("$.fields.actor").exists()).andExpect(jsonPath("$.fields.from").exists());
		mvc.perform(as(get("/admin/api/v1/audit"), Role.VIEWER)).andExpect(status().isForbidden());
	}

	@Test
	void internalAuditWritesShowUpInTheLogAndRejectBadInput() throws Exception {
		UUID target = UUID.randomUUID();
		mvc.perform(post("/internal/audit").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
				"actorId", STAFF_ID.toString(), "actorRole", "ADMIN", "action", "user.status", "targetType", "app_user",
				"targetId", target.toString(), "meta", Map.of("from", "ACTIVE", "to", "SUSPENDED"), "ip", "203.0.113.9"))))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString());
		mvc.perform(as(get("/admin/api/v1/audit").param("target", target.toString()), Role.ADMIN)).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].meta.to").value("SUSPENDED")).andExpect(jsonPath("$[0].ip").value("203.0.113.9"))
			.andExpect(jsonPath("$[0].actorEmail").value((Object) null));
		mvc.perform(post("/internal/audit").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"Bad Action\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.action").exists());
		mvc.perform(post("/internal/audit").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"user.status\",\"actorId\":\"x\"}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.actorId").exists());
	}

	@Test
	void staffListShowsBootstrapAndCreator() throws Exception {
		UUID creator = UUID.randomUUID();
		staffRepo.save(new Staff(creator, "plain.person@example.com", Role.VIEWER, STAFF_ID, Instant.now()));
		mvc.perform(as(get("/admin/api/v1/staff"), Role.SUPER_ADMIN)).andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.email=='plain.person@example.com')].bootstrap").value(false))
			.andExpect(jsonPath("$[?(@.email=='plain.person@example.com')].createdBy").value(STAFF_ID.toString()));
	}

}
