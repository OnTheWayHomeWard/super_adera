package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.auth.Role;
import app.adera.registry.banner.Banner;
import app.adera.registry.banner.BannerRepository;
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

/** The staff banner API against a real Postgres: roles, validation, the computed state, filters, reorder, delete, audit. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory", "adera.registry.banner.external-domains=adera.et,example.com",
		"adera.registry.banner.max-bytes=200000" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BannerAdminApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@Autowired
	BannerRepository banners;

	@MockitoBean
	KratosClient kratos;

	static final UUID STAFF = UUID.randomUUID();

	static <T extends AbstractMockHttpServletRequestBuilder<T>> T as(T request, Role role) {
		return request.header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", role.name());
	}

	private ResultActions send(AbstractMockHttpServletRequestBuilder<?> request, Role role, Object body) throws Exception {
		AbstractMockHttpServletRequestBuilder<?> r = request.header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", role.name());
		if (body != null) {
			r.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
		}
		return mvc.perform(r);
	}

	static Map<String, Object> body(Object... kv) {
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	/** A unique placement-independent marker so tests running against the shared database find their own rows. */
	private static String tag() {
		return "t-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** A DRAFT mini app (enough to link to; whether its banners show is tested in BannerPublicApiTests). */
	private UUID app() throws Exception {
		String slug = "b-" + UUID.randomUUID().toString().substring(0, 8);
		String res = send(post("/admin/api/v1/apps"), Role.ADMIN, body("slug", slug, "name", Map.of("en", "App " + slug)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	private UUID create(Map<String, Object> fields) throws Exception {
		Map<String, Object> b = body("title", Map.of("en", "Banner " + tag()), "placement", "PROMOTIONS", "category", "ANNOUNCEMENT",
				"actionType", "NONE", "startsAt", "2026-01-01T00:00:00Z");
		b.putAll(fields);
		String res = send(post("/admin/api/v1/banners"), Role.ADMIN, b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(res, "$.id"));
	}

	/** Pretend an image was uploaded (the upload itself is tested in BannerImageTests). */
	private void fakeImage(UUID id) {
		Banner b = banners.findById(id).orElseThrow();
		b.setImage(UUID.randomUUID(), Map.of("small", Map.of("width", 480, "height", 200)));
		banners.saveAndFlush(b);
	}

	// ---------- roles ----------

	@Test
	void viewersReadButOnlyAdminsWrite() throws Exception {
		UUID id = create(Map.of());
		send(get("/admin/api/v1/banners"), Role.VIEWER, null).andExpect(status().isOk());
		send(get("/admin/api/v1/banners/{id}", id), Role.VIEWER, null).andExpect(status().isOk());
		send(get("/admin/api/v1/banners/settings"), Role.VIEWER, null).andExpect(status().isOk());
		send(post("/admin/api/v1/banners"), Role.VIEWER, body("title", Map.of("en", "x"))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("insufficient_role"));
		send(patch("/admin/api/v1/banners/{id}", id), Role.VIEWER, body("priority", 1)).andExpect(status().isForbidden());
		send(post("/admin/api/v1/banners/{id}/status", id), Role.VIEWER, body("status", "PAUSED")).andExpect(status().isForbidden());
		send(post("/admin/api/v1/banners/reorder"), Role.VIEWER, body("ids", List.of(id.toString()))).andExpect(status().isForbidden());
		send(delete("/admin/api/v1/banners/{id}", id), Role.VIEWER, null).andExpect(status().isForbidden());
		send(patch("/admin/api/v1/banners/{id}", id), Role.SUPER_ADMIN, body("priority", 7)).andExpect(status().isOk());
		mvc.perform(get("/admin/api/v1/banners")).andExpect(status().isUnauthorized());
	}

	// ---------- create and validation ----------

	@Test
	void createStartsAsDraftWithTheComputedStateAndIsAudited() throws Exception {
		UUID app = app();
		String res = send(post("/admin/api/v1/banners"), Role.ADMIN, body("title", Map.of("en", "First ride free", "am", "የመጀመሪያ ጉዞ"),
				"subtitle", Map.of("en", "Up to 150 birr"), "placement", "HOME_HERO", "category", "FEATURED_APP", "appId", app.toString(),
				"actionType", "MINI_APP", "target", "/promo/first-ride", "startsAt", "2026-11-01T00:00:00+03:00", "priority", 50))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.state").value("DRAFT"))
			.andExpect(jsonPath("$.visible").value(false))
			.andExpect(jsonPath("$.placement").value("HOME_HERO"))
			.andExpect(jsonPath("$.startsAt").value("2026-10-31T21:00:00Z"))
			.andExpect(jsonPath("$.endsAt").doesNotExist())
			.andExpect(jsonPath("$.app.id").value(app.toString()))
			.andExpect(jsonPath("$.app.status").value("DRAFT"))
			.andExpect(jsonPath("$.images").doesNotExist())
			.andExpect(jsonPath("$.createdBy").value(STAFF.toString()))
			.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(res, "$.id");
		send(get("/admin/api/v1/audit").param("target", id), Role.ADMIN, null)
			.andExpect(jsonPath("$[0].action").value("banner.create"))
			.andExpect(jsonPath("$[0].targetType").value("banner"))
			.andExpect(jsonPath("$[0].meta.placement").value("HOME_HERO"));
		send(get("/admin/api/v1/audit").param("appId", app.toString()).param("action", "banner."), Role.ADMIN, null)
			.andExpect(jsonPath("$.length()").value(1));
	}

	@Test
	void everyFieldProblemIsReportedAtOnceInTheUsualShape() throws Exception {
		send(post("/admin/api/v1/banners"), Role.ADMIN, body("title", Map.of("am", "x"), "placement", "SIDEBAR", "colour", "red"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation_failed"))
			.andExpect(jsonPath("$.fields.title").exists())
			.andExpect(jsonPath("$.fields.placement").value("must be HOME_HERO, FEATURED, PROMOTIONS or CATALOG_TOP"))
			.andExpect(jsonPath("$.fields.colour").value("unknown field"))
			.andExpect(jsonPath("$.fields.category").value("required"))
			.andExpect(jsonPath("$.fields.actionType").value("required"))
			.andExpect(jsonPath("$.fields.startsAt").value("required"));
	}

	@Test
	void combinationsAreCheckedOnCreateAndOnTheResultOfAnUpdate() throws Exception {
		Map<String, Object> base = body("title", Map.of("en", "x"), "placement", "FEATURED", "category", "FEATURED_APP", "actionType", "MINI_APP",
				"startsAt", "2026-12-01T00:00:00Z", "endsAt", "2026-11-01T00:00:00Z", "target", "promo");
		send(post("/admin/api/v1/banners"), Role.ADMIN, base).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.endsAt").value("must be later than startsAt"))
			.andExpect(jsonPath("$.fields.appId").exists())
			.andExpect(jsonPath("$.fields.target").exists());
		base.put("appId", UUID.randomUUID().toString());
		base.remove("endsAt");
		base.remove("target");
		send(post("/admin/api/v1/banners"), Role.ADMIN, base).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.appId").value("no such mini app"));

		UUID id = create(body("actionType", "INTERNAL", "target", "adera://wallet"));
		send(patch("/admin/api/v1/banners/{id}", id), Role.ADMIN, body("actionType", "NONE")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.target").value("must be empty when actionType is NONE"));
		send(patch("/admin/api/v1/banners/{id}", id), Role.ADMIN, body("actionType", "NONE", "target", null)).andExpect(status().isOk())
			.andExpect(jsonPath("$.actionType").value("NONE")).andExpect(jsonPath("$.target").doesNotExist());
		send(patch("/admin/api/v1/banners/{id}", id), Role.ADMIN, body("endsAt", "2025-01-01T00:00:00Z")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.endsAt").value("must be later than startsAt"));
		send(patch("/admin/api/v1/banners/{id}", id), Role.ADMIN, body("slug", "x")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.slug").value("unknown field"));
	}

	@Test
	void externalLinksMustBeHttpsOnAnAllowedDomain() throws Exception {
		Map<String, Object> b = body("title", Map.of("en", "x"), "placement", "PROMOTIONS", "category", "PROMOTION", "actionType", "EXTERNAL",
				"startsAt", "2026-12-01T00:00:00Z", "target", "https://evil.test/login");
		send(post("/admin/api/v1/banners"), Role.ADMIN, b).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.target").value("the domain evil.test is not allowed for banners (allowed: adera.et, example.com, and their subdomains)"));
		b.put("target", "http://www.adera.et/");
		send(post("/admin/api/v1/banners"), Role.ADMIN, b).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.target").value("must be an https:// URL"));
		b.put("target", "https://news.adera.et/holiday");
		send(post("/admin/api/v1/banners"), Role.ADMIN, b).andExpect(status().isOk()).andExpect(jsonPath("$.target").value("https://news.adera.et/holiday"));
		send(get("/admin/api/v1/banners/settings"), Role.VIEWER, null)
			.andExpect(jsonPath("$.externalDomains[0]").value("adera.et"))
			.andExpect(jsonPath("$.imageSizes.large").value(1600))
			.andExpect(jsonPath("$.maxImageBytes").value(200000));
	}

	// ---------- status ----------

	@Test
	void publishingNeedsAnImageAndStatusChangesAreAudited() throws Exception {
		UUID id = create(Map.of());
		send(post("/admin/api/v1/banners/{id}/status", id), Role.ADMIN, body("status", "PUBLISHED")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("not_ready_to_publish")).andExpect(jsonPath("$.fields.missing").value("image"));
		send(post("/admin/api/v1/banners/{id}/status", id), Role.ADMIN, body("status", "ENDED")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.status").value("must be DRAFT, PUBLISHED or PAUSED"));
		fakeImage(id);
		send(post("/admin/api/v1/banners/{id}/status", id), Role.ADMIN, body("status", "published")).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.state").value("LIVE")).andExpect(jsonPath("$.visible").value(true));
		send(post("/admin/api/v1/banners/{id}/status", id), Role.ADMIN, body("status", "PAUSED")).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("PAUSED")).andExpect(jsonPath("$.visible").value(false));
		send(post("/admin/api/v1/banners/{id}/status", id), Role.ADMIN, body("status", "PAUSED")).andExpect(status().isOk());
		send(post("/admin/api/v1/banners/{id}/status", id), Role.ADMIN, body("status", "DRAFT")).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("DRAFT"));
		send(get("/admin/api/v1/audit").param("target", id.toString()).param("action", "banner.status"), Role.ADMIN, null)
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].meta.from").value("PAUSED")).andExpect(jsonPath("$[0].meta.to").value("DRAFT"));
	}

	@Test
	void theStateFollowsTheClockAndALinkedAppThatIsNotLiveHidesTheBanner() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		UUID scheduled = create(body("startsAt", now.plus(1, ChronoUnit.DAYS).toString()));
		UUID ended = create(body("startsAt", now.minus(2, ChronoUnit.DAYS).toString(), "endsAt", now.minus(1, ChronoUnit.DAYS).toString()));
		UUID app = app();
		UUID ofDraftApp = create(body("appId", app.toString(), "category", "FEATURED_APP"));
		for (UUID id : List.of(scheduled, ended, ofDraftApp)) {
			fakeImage(id);
			send(post("/admin/api/v1/banners/{id}/status", id), Role.ADMIN, body("status", "PUBLISHED")).andExpect(status().isOk());
		}
		send(get("/admin/api/v1/banners/{id}", scheduled), Role.VIEWER, null).andExpect(jsonPath("$.state").value("SCHEDULED")).andExpect(jsonPath("$.visible").value(false));
		send(get("/admin/api/v1/banners/{id}", ended), Role.VIEWER, null).andExpect(jsonPath("$.state").value("ENDED")).andExpect(jsonPath("$.visible").value(false));
		send(get("/admin/api/v1/banners/{id}", ofDraftApp), Role.VIEWER, null).andExpect(jsonPath("$.state").value("LIVE"))
			.andExpect(jsonPath("$.visible").value(false)).andExpect(jsonPath("$.app.status").value("DRAFT"));
		// extending an ended banner brings it back without touching the status
		send(patch("/admin/api/v1/banners/{id}", ended), Role.ADMIN, body("endsAt", null)).andExpect(jsonPath("$.state").value("LIVE"));
	}

	// ---------- list ----------

	@Test
	void theListFiltersByPlacementCategoryStateAndAppAndCountsTheTotal() throws Exception {
		UUID app = app();
		UUID a = create(body("placement", "CATALOG_TOP", "appId", app.toString(), "priority", 5));
		UUID b = create(body("placement", "CATALOG_TOP", "appId", app.toString(), "category", "EVENT", "priority", 9));
		UUID c = create(body("placement", "CATALOG_TOP", "appId", app.toString(), "category", "EVENT", "priority", 9));
		fakeImage(c);
		send(post("/admin/api/v1/banners/{id}/status", c), Role.ADMIN, body("status", "PAUSED")).andExpect(status().isOk());
		String all = send(get("/admin/api/v1/banners").param("appId", app.toString()), Role.VIEWER, null)
			.andExpect(status().isOk()).andExpect(header().string("X-Total-Count", "3")).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(all, "$[*].id")).as("priority first, then newest").containsExactly(c.toString(), b.toString(), a.toString());
		send(get("/admin/api/v1/banners").param("appId", app.toString()).param("category", "event"), Role.VIEWER, null)
			.andExpect(header().string("X-Total-Count", "2"));
		send(get("/admin/api/v1/banners").param("appId", app.toString()).param("state", "PAUSED"), Role.VIEWER, null)
			.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(c.toString()));
		send(get("/admin/api/v1/banners").param("appId", app.toString()).param("state", "DRAFT").param("size", "1").param("page", "1"), Role.VIEWER, null)
			.andExpect(header().string("X-Total-Count", "2")).andExpect(jsonPath("$[0].id").value(a.toString()));
		send(get("/admin/api/v1/banners").param("appId", app.toString()).param("state", "LIVE"), Role.VIEWER, null).andExpect(jsonPath("$.length()").value(0));
		send(get("/admin/api/v1/banners").param("state", "GONE"), Role.VIEWER, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.state").value("must be DRAFT, PAUSED, SCHEDULED, LIVE or ENDED"));
		send(get("/admin/api/v1/banners/{id}", UUID.randomUUID()), Role.VIEWER, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
	}

	// ---------- reorder and delete ----------

	@Test
	void reorderSetsPrioritiesTenApartInTheGivenOrder() throws Exception {
		UUID x = create(body("priority", 1));
		UUID y = create(body("priority", 2));
		UUID z = create(body("priority", 3));
		send(post("/admin/api/v1/banners/reorder"), Role.ADMIN, body("ids", List.of(x.toString(), z.toString(), y.toString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(x.toString())).andExpect(jsonPath("$[0].priority").value(30))
			.andExpect(jsonPath("$[1].priority").value(20)).andExpect(jsonPath("$[2].priority").value(10));
		send(post("/admin/api/v1/banners/reorder"), Role.ADMIN, body("ids", List.of(x.toString(), x.toString()))).andExpect(status().isBadRequest());
		send(post("/admin/api/v1/banners/reorder"), Role.ADMIN, body("ids", List.of(UUID.randomUUID().toString()))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fields.ids").value(org.hamcrest.Matchers.startsWith("no such banner")));
		send(post("/admin/api/v1/banners/reorder"), Role.ADMIN, body("ids", List.of())).andExpect(status().isBadRequest());
		send(get("/admin/api/v1/audit").param("action", "banner.reorder").param("limit", "1"), Role.ADMIN, null)
			.andExpect(jsonPath("$[0].meta.ids[0]").value(x.toString()));
	}

	@Test
	void deleteRemovesTheBannerAndIsAudited() throws Exception {
		UUID id = create(Map.of());
		send(delete("/admin/api/v1/banners/{id}", id), Role.ADMIN, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("deleted"));
		send(get("/admin/api/v1/banners/{id}", id), Role.ADMIN, null).andExpect(status().isNotFound());
		send(delete("/admin/api/v1/banners/{id}", id), Role.ADMIN, null).andExpect(status().isNotFound());
		send(get("/admin/api/v1/audit").param("target", id.toString()), Role.ADMIN, null)
			.andExpect(jsonPath("$[0].action").value("banner.delete")).andExpect(jsonPath("$[1].action").value("banner.create"));
	}

}
