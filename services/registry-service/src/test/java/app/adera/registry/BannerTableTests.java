package app.adera.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.auth.KratosClient;
import app.adera.registry.banner.Banner;
import app.adera.registry.banner.BannerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** V9: the banner table, its mapping (Hibernate validates it at start) and the database's own safety checks. */
@SpringBootTest(properties = { "adera.registry.storage.type=memory" })
@Import(TestcontainersConfiguration.class)
class BannerTableTests {

	@Autowired
	BannerRepository banners;

	@MockitoBean
	KratosClient kratos;

	private static Banner banner(Banner.Category category, Banner.ActionType action, Instant start, Instant end) {
		Banner b = new Banner(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
		b.setTitle(Map.of("en", "Hello"));
		b.setPlacement(Banner.Placement.PROMOTIONS);
		b.setCategory(category);
		b.setActionType(action);
		b.setStartsAt(start);
		b.setEndsAt(end);
		return b;
	}

	@Test
	void aBannerIsStoredWithItsLanguagesAndImageSizes() {
		Banner b = banner(Banner.Category.ANNOUNCEMENT, Banner.ActionType.NONE, Instant.parse("2026-12-01T00:00:00Z"), null);
		b.setSubtitle(Map.of("en", "Sub", "am", "ንዑስ"));
		b.setImage(UUID.randomUUID(), Map.of("small", Map.of("width", 480, "height", 200)));
		banners.saveAndFlush(b);
		Banner read = banners.findById(b.getId()).orElseThrow();
		assertThat(read.getSubtitle()).containsEntry("am", "ንዑስ");
		assertThat(read.getImageVariants().get("small")).containsEntry("width", 480);
		assertThat(read.getStatus()).isEqualTo(Banner.Status.DRAFT);
	}

	@Test
	void theDatabaseRefusesAnEndBeforeTheStartAFeaturedAppWithoutAnAppAndATargetForNone() {
		Instant t = Instant.parse("2026-12-01T00:00:00Z");
		assertThatThrownBy(() -> banners.saveAndFlush(banner(Banner.Category.EVENT, Banner.ActionType.NONE, t, t)))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> banners.saveAndFlush(banner(Banner.Category.FEATURED_APP, Banner.ActionType.NONE, t, null)))
			.isInstanceOf(DataIntegrityViolationException.class);
		Banner none = banner(Banner.Category.EVENT, Banner.ActionType.NONE, t, null);
		none.setTarget("/x");
		assertThatThrownBy(() -> banners.saveAndFlush(none)).isInstanceOf(DataIntegrityViolationException.class);
		Banner unknownApp = banner(Banner.Category.FEATURED_APP, Banner.ActionType.NONE, t, null);
		unknownApp.setAppId(UUID.randomUUID());
		assertThatThrownBy(() -> banners.saveAndFlush(unknownApp)).as("the app must exist").isInstanceOf(DataIntegrityViolationException.class);
	}

}
