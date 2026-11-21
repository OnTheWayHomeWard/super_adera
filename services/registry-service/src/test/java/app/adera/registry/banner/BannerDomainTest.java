package app.adera.registry.banner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.common.Errors;
import org.junit.jupiter.api.Test;

/** Banner rules that need no database: the computed state, field checks, cross-field rules and the domain allow-list. */
class BannerDomainTest {

	private static final Instant T0 = Instant.parse("2026-12-01T00:00:00Z");
	private static final Instant T1 = Instant.parse("2026-12-31T00:00:00Z");
	private static final UUID APP = UUID.randomUUID();
	private static final ExternalDomains DOMAINS = new ExternalDomains(" Adera.et, *.example.com ,.partner.test, ");

	// ---------- state ----------

	@Test
	void stateIsComputedFromTheSwitchAndTheClock() {
		assertThat(BannerState.of(Banner.Status.DRAFT, T0, T1, T0.plusSeconds(10))).isEqualTo(BannerState.DRAFT);
		assertThat(BannerState.of(Banner.Status.PAUSED, T0, T1, T0.plusSeconds(10))).isEqualTo(BannerState.PAUSED);
		assertThat(BannerState.of(Banner.Status.PUBLISHED, T0, T1, T0.minusSeconds(1))).isEqualTo(BannerState.SCHEDULED);
		assertThat(BannerState.of(Banner.Status.PUBLISHED, T0, T1, T0)).as("the start is inclusive").isEqualTo(BannerState.LIVE);
		assertThat(BannerState.of(Banner.Status.PUBLISHED, T0, T1, T1.minusMillis(1))).isEqualTo(BannerState.LIVE);
		assertThat(BannerState.of(Banner.Status.PUBLISHED, T0, T1, T1)).as("the end is exclusive").isEqualTo(BannerState.ENDED);
		assertThat(BannerState.of(Banner.Status.PUBLISHED, T0, null, T1.plusSeconds(86_400 * 365))).as("no end = open-ended").isEqualTo(BannerState.LIVE);
		assertThat(BannerState.of(Banner.Status.PAUSED, T0, T1, T1.plusSeconds(1))).as("a paused banner stays paused after its end").isEqualTo(BannerState.PAUSED);
	}

	// ---------- fields ----------

	private static Map<String, Object> valid() {
		Map<String, Object> b = new HashMap<>();
		b.put("title", Map.of("en", "Ride for less", "am", "በቅናሽ ይጓዙ"));
		b.put("placement", "home_hero");
		b.put("category", "PROMOTION");
		b.put("actionType", "NONE");
		b.put("startsAt", "2026-12-01T09:00:00+03:00");
		return b;
	}

	private static Map<String, String> fieldErrors(Map<String, Object> body, boolean creating) {
		try {
			BannerPatch.parse(body, creating);
			return Map.of();
		}
		catch (Errors.ValidationFailed e) {
			return e.fields();
		}
	}

	@Test
	void createNeedsTheCoreFieldsAndReportsEveryProblemAtOnce() {
		BannerPatch p = BannerPatch.parse(valid(), true);
		assertThat((Object) p.as("placement")).isEqualTo(Banner.Placement.HOME_HERO);
		assertThat((Instant) p.as("startsAt")).isEqualTo(Instant.parse("2026-12-01T06:00:00Z"));
		assertThat(fieldErrors(Map.of(), true)).containsOnlyKeys("title", "placement", "category", "actionType", "startsAt");
		Map<String, Object> bad = valid();
		bad.put("placement", "SIDEBAR");
		bad.put("category", 3);
		bad.put("startsAt", "tomorrow");
		bad.put("priority", -1);
		bad.put("colour", "red");
		bad.put("appId", "not-a-uuid");
		assertThat(fieldErrors(bad, true)).containsOnlyKeys("placement", "category", "startsAt", "priority", "colour", "appId")
			.containsEntry("placement", "must be HOME_HERO, FEATURED, PROMOTIONS or CATALOG_TOP")
			.containsEntry("colour", "unknown field");
	}

	@Test
	void titlesAreLanguageObjectsWithEnglishAndLimitedLength() {
		Map<String, Object> b = valid();
		b.put("title", Map.of("am", "ብቻ"));
		assertThat(fieldErrors(b, true)).containsKey("title");
		b.put("title", Map.of("en", "x".repeat(81)));
		assertThat(fieldErrors(b, true)).containsEntry("title", "en is longer than 80 characters");
		b.put("title", "plain text");
		assertThat(fieldErrors(b, true)).containsKey("title");
		b.put("title", Map.of("en", "ok"));
		b.put("subtitle", Map.of("en", "y".repeat(161)));
		assertThat(fieldErrors(b, true)).containsKey("subtitle");
		assertThat(fieldErrors(Map.of("title", Map.of("fr", "Bonjour")), false)).containsKey("title");
	}

	@Test
	void patchMayClearOptionalFieldsButNotRequiredOnes() {
		Map<String, Object> clear = new HashMap<>();
		clear.put("subtitle", null);
		clear.put("appId", null);
		clear.put("target", "   ");
		clear.put("endsAt", null);
		BannerPatch p = BannerPatch.parse(clear, false);
		assertThat((Object) p.as("subtitle")).isEqualTo(Map.of());
		assertThat(p.has("appId")).isTrue();
		assertThat((Object) p.as("target")).as("blank target = none").isNull();
		Map<String, Object> required = new HashMap<>();
		required.put("title", null);
		required.put("placement", null);
		required.put("startsAt", null);
		assertThat(fieldErrors(required, false)).containsOnlyKeys("title", "placement", "startsAt");
	}

	// ---------- cross-field rules ----------

	private static Map<String, String> rules(Banner.Category category, UUID app, Banner.ActionType action, String target, Instant start, Instant end) {
		return BannerRules.check(new BannerRules.Candidate(category, app, action, target, start, end), DOMAINS);
	}

	@Test
	void theEndMustBeAfterTheStart() {
		assertThat(rules(Banner.Category.EVENT, null, Banner.ActionType.NONE, null, T0, T0)).containsKey("endsAt");
		assertThat(rules(Banner.Category.EVENT, null, Banner.ActionType.NONE, null, T1, T0)).containsKey("endsAt");
		assertThat(rules(Banner.Category.EVENT, null, Banner.ActionType.NONE, null, T0, null)).isEmpty();
	}

	@Test
	void aFeaturedAppOrAMiniAppActionNeedsAnApp() {
		assertThat(rules(Banner.Category.FEATURED_APP, null, Banner.ActionType.NONE, null, T0, null))
			.containsEntry("appId", "required when category is FEATURED_APP");
		assertThat(rules(Banner.Category.PROMOTION, null, Banner.ActionType.MINI_APP, null, T0, null))
			.containsEntry("appId", "required when actionType is MINI_APP");
		assertThat(rules(Banner.Category.FEATURED_APP, APP, Banner.ActionType.MINI_APP, null, T0, null)).as("no target = the app's home").isEmpty();
	}

	@Test
	void miniAppTargetsAreRoutesInsideTheApp() {
		for (String ok : new String[] { "/", "/promo/first-ride", "/p?ref=banner&x=1#top", "/a%20b" }) {
			assertThat(rules(Banner.Category.PROMOTION, APP, Banner.ActionType.MINI_APP, ok, T0, null)).as(ok).isEmpty();
		}
		for (String bad : new String[] { "promo", "//evil.test/x", "https://evil.test/", "/a b", "/a\"b", "/<script>", "javascript:alert(1)" }) {
			assertThat(rules(Banner.Category.PROMOTION, APP, Banner.ActionType.MINI_APP, bad, T0, null)).as(bad).containsKey("target");
		}
	}

	@Test
	void internalTargetsAreAderaLinks() {
		assertThat(rules(Banner.Category.ANNOUNCEMENT, null, Banner.ActionType.INTERNAL, "adera://wallet", T0, null)).isEmpty();
		assertThat(rules(Banner.Category.ANNOUNCEMENT, null, Banner.ActionType.INTERNAL, "adera://wallet/top-up?amount=100", T0, null)).isEmpty();
		assertThat(rules(Banner.Category.ANNOUNCEMENT, null, Banner.ActionType.INTERNAL, null, T0, null)).containsKey("target");
		for (String bad : new String[] { "adera://", "wallet", "https://adera.et/wallet", "adera:wallet", "adera://wal let", "ADERA2://x" }) {
			assertThat(rules(Banner.Category.ANNOUNCEMENT, null, Banner.ActionType.INTERNAL, bad, T0, null)).as(bad).containsKey("target");
		}
	}

	@Test
	void externalTargetsAreHttpsOnAllowedDomainsOnly() {
		for (String ok : new String[] { "https://adera.et/news", "https://WWW.Adera.ET/", "https://shop.example.com/x?y=1", "https://a.b.partner.test" }) {
			assertThat(rules(Banner.Category.PROMOTION, null, Banner.ActionType.EXTERNAL, ok, T0, null)).as(ok).isEmpty();
		}
		assertThat(rules(Banner.Category.PROMOTION, null, Banner.ActionType.EXTERNAL, "https://evil.test/", T0, null).get("target"))
			.startsWith("the domain evil.test is not allowed");
		for (String bad : new String[] { "http://adera.et/", "https://notadera.et/", "https://adera.et.evil.test/", "https://user:pw@adera.et/",
				"ftp://adera.et/", "https:///nohost", "//adera.et/", "https://adera.et/a b", "adera.et" }) {
			assertThat(rules(Banner.Category.PROMOTION, null, Banner.ActionType.EXTERNAL, bad, T0, null)).as(bad).containsKey("target");
		}
		assertThat(BannerRules.check(new BannerRules.Candidate(Banner.Category.PROMOTION, null, Banner.ActionType.EXTERNAL, "https://adera.et/", T0, null),
				new ExternalDomains("")).get("target")).contains("switched off");
	}

	@Test
	void noneMeansNoTarget() {
		assertThat(rules(Banner.Category.ANNOUNCEMENT, null, Banner.ActionType.NONE, "/x", T0, null))
			.containsEntry("target", "must be empty when actionType is NONE");
	}

	@Test
	void theAllowListIsNormalised() {
		assertThat(DOMAINS.list()).containsExactly("adera.et", "example.com", "partner.test");
		assertThat(DOMAINS.allows("adera.et.")).isTrue();
		assertThat(DOMAINS.allows("example.com")).isTrue();
		assertThat(DOMAINS.allows("badexample.com")).isFalse();
		assertThat(DOMAINS.allows(null)).isFalse();
	}

	@Test
	void mergingAPatchKeepsWhatItDoesNotMention() {
		Banner b = new Banner(UUID.randomUUID(), null, T0);
		b.setCategory(Banner.Category.FEATURED_APP);
		b.setAppId(APP);
		b.setActionType(Banner.ActionType.MINI_APP);
		b.setTarget("/x");
		b.setStartsAt(T0);
		Map<String, Object> body = new HashMap<>();
		body.put("appId", null);
		BannerRules.Candidate c = BannerPatch.parse(body, false).merge(b);
		assertThat(c.appId()).isNull();
		assertThat(c.target()).isEqualTo("/x");
		assertThat(BannerRules.check(c, DOMAINS)).containsKey("appId");
		assertThatThrownBy(() -> BannerPatch.parse(Map.of("priority", "5"), false)).isInstanceOf(Errors.ValidationFailed.class);
	}

}
