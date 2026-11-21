package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** GET /api/v1/banners: no user needed, only banners showing now, in order, with filters, a limit and a cache header. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory", "adera.registry.banner.external-domains=adera.et,example.com",
		"adera.registry.banner.max-bytes=200000" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BannerPublicApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper mapper;

	@MockitoBean
	KratosClient kratos;

	private static final UUID STAFF = UUID.randomUUID();

	private static <T extends AbstractMockHttpServletRequestBuilder<T>> T admin(T r) {
		return r.header("X-Staff-Id", STAFF.toString()).header("X-Staff-Role", "ADMIN");
	}

	private String postJson(String path, Object body, Object... vars) throws Exception {
		return mvc.perform(admin(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path, vars))
			.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private UUID liveApp() throws Exception {
		String slug = "pb-" + UUID.randomUUID().toString().substring(0, 8);
		UUID id = UUID.fromString(JsonPath.read(postJson("/admin/api/v1/apps", Map.of("slug", slug, "name", Map.of("en", "App " + slug),
				"supportEmail", "help@example.com")), "$.id"));
		mvc.perform(admin(multipart("/admin/api/v1/apps/{id}/logo", id).file(new MockMultipartFile("file", "l.png", "image/png",
				BannerImageTests.png(64, 64, BufferedImage.TYPE_INT_RGB))))).andExpect(status().isOk());
		appStatus(id, "LIVE");
		return id;
	}

	private void appStatus(UUID id, String status) throws Exception {
		postJson("/admin/api/v1/apps/{id}/status", Map.of("status", status), id);
	}

	/** A banner with an image, in the given status. */
	private UUID banner(String status, Map<String, Object> fields) throws Exception {
		Map<String, Object> b = new HashMap<>(Map.of("title", Map.of("en", "Public " + UUID.randomUUID().toString().substring(0, 6)),
				"placement", "FEATURED", "category", "PROMOTION", "actionType", "NONE",
				"startsAt", Instant.now().minus(1, ChronoUnit.HOURS).toString()));
		b.putAll(fields);
		UUID id = UUID.fromString(JsonPath.read(postJson("/admin/api/v1/banners", b), "$.id"));
		mvc.perform(admin(multipart("/admin/api/v1/banners/{id}/image", id).file(new MockMultipartFile("file", "b.png", "image/png",
				BannerImageTests.png(1200, 500, BufferedImage.TYPE_INT_RGB))))).andExpect(status().isOk());
		if (!status.equals("DRAFT")) {
			postJson("/admin/api/v1/banners/{id}/status", Map.of("status", "PUBLISHED"), id);
		}
		if (status.equals("PAUSED")) {
			postJson("/admin/api/v1/banners/{id}/status", Map.of("status", "PAUSED"), id);
		}
		return id;
	}

	/** The public list as ids, keeping only the ones this test made (other tests share the database). */
	private List<String> visible(Set<UUID> mine, String query) throws Exception {
		String res = mvc.perform(get("/api/v1/banners" + query)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> ids = new ArrayList<>(JsonPath.<List<String>>read(res, "$[*].id"));
		ids.removeIf(i -> mine.stream().noneMatch(m -> m.toString().equals(i)));
		return ids;
	}

	@Test
	void onlyBannersShowingNowAreListedWithoutAnyUser() throws Exception {
		Instant now = Instant.now();
		UUID app = liveApp();
		UUID live = banner("PUBLISHED", Map.of("priority", 10));
		UUID liveHigh = banner("PUBLISHED", Map.of("priority", 90, "appId", app.toString(), "category", "FEATURED_APP", "actionType", "MINI_APP",
				"target", "/promo/first-ride"));
		UUID scheduled = banner("PUBLISHED", Map.of("startsAt", now.plus(1, ChronoUnit.DAYS).toString()));
		UUID ended = banner("PUBLISHED", Map.of("startsAt", now.minus(2, ChronoUnit.DAYS).toString(), "endsAt", now.minus(1, ChronoUnit.MINUTES).toString()));
		UUID paused = banner("PAUSED", Map.of());
		UUID draft = banner("DRAFT", Map.of());
		Set<UUID> mine = Set.of(live, liveHigh, scheduled, ended, paused, draft);

		assertThat(visible(mine, "?placement=FEATURED&limit=50")).as("priority first").containsExactly(liveHigh.toString(), live.toString());

		String res = mvc.perform(get("/api/v1/banners").param("placement", "featured").param("category", "FEATURED_APP").param("limit", "50"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "max-age=60, public"))
			.andReturn().getResponse().getContentAsString();
		String path = "$[?(@.id == '" + liveHigh + "')]";
		assertThat(JsonPath.<List<String>>read(res, path + ".actionType")).containsExactly("MINI_APP");
		assertThat(JsonPath.<List<String>>read(res, path + ".target")).containsExactly("/promo/first-ride");
		assertThat(JsonPath.<List<String>>read(res, path + ".app.id")).containsExactly(app.toString());
		assertThat(JsonPath.<List<String>>read(res, path + ".app.logoUrl").getFirst()).startsWith("http://storage.test/apps/" + app + "/logo/");
		assertThat(JsonPath.<List<Integer>>read(res, path + ".images.medium.width")).containsExactly(960);
		assertThat(JsonPath.<List<Object>>read(res, path + ".app.status")).as("no internal app status for users").isEmpty();
		assertThat(JsonPath.<List<Object>>read(res, path + ".status")).as("no internal fields").isEmpty();
		assertThat(JsonPath.<List<Object>>read(res, "$[*].category")).containsOnly("FEATURED_APP");

		// the linked app is paused: its banner disappears by itself, and comes back when the app is LIVE again
		appStatus(app, "PAUSED");
		assertThat(visible(mine, "?placement=FEATURED&limit=50")).containsExactly(live.toString());
		appStatus(app, "LIVE");
		assertThat(visible(mine, "?placement=FEATURED&limit=50")).containsExactly(liveHigh.toString(), live.toString());

		// extending the ended banner shows it again; nothing else changed
		mvc.perform(admin(patch("/admin/api/v1/banners/{id}", ended)).contentType(MediaType.APPLICATION_JSON).content("{\"endsAt\":null}"))
			.andExpect(status().isOk());
		assertThat(visible(mine, "?placement=FEATURED&limit=50")).contains(ended.toString()).doesNotContain(scheduled.toString(), paused.toString(), draft.toString());
	}

	@Test
	void theLimitCutsAfterOrderingAndBadQueriesAreRefused() throws Exception {
		banner("PUBLISHED", Map.of("placement", "CATALOG_TOP", "priority", 100_000));
		mvc.perform(get("/api/v1/banners").param("placement", "CATALOG_TOP").param("limit", "1")).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].priority").value(100_000));
		mvc.perform(get("/api/v1/banners").param("placement", "SIDEBAR")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("validation_failed"))
			.andExpect(jsonPath("$.fields.placement").value("must be HOME_HERO, FEATURED, PROMOTIONS or CATALOG_TOP"));
		mvc.perform(get("/api/v1/banners").param("category", "ADS")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.category").exists());
		for (String bad : new String[] { "0", "51", "ten" }) {
			mvc.perform(get("/api/v1/banners").param("limit", bad)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fields.limit").value("must be a whole number from 1 to 50"));
		}
		mvc.perform(post("/api/v1/banners")).andExpect(status().isMethodNotAllowed());
	}

	@Test
	void otherUserPathsStillNeedAUser() throws Exception {
		mvc.perform(get("/api/v1/banners")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/apps")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/bannersx")).andExpect(status().isUnauthorized());
	}

}
