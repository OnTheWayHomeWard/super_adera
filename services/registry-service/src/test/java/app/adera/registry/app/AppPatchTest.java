package app.adera.registry.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.adera.registry.common.Errors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AppPatchTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static Map<String, String> errors(Map<String, Object> body, boolean creating) {
		Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> AppPatch.parse(body, creating, MAPPER));
		assertThat(t).isInstanceOf(Errors.ValidationFailed.class);
		return ((Errors.ValidationFailed) t).fields();
	}

	@Test
	void validCreateIsNormalised() {
		AppPatch p = AppPatch.parse(Map.of("slug", "adera-fix", "name", Map.of("en", " Adera Fix ", "am", "አደራ ጥገና"), "brandColor", "#0f9d58",
				"markets", List.of("et", "us"), "supportEmail", "Support@Adera.APP", "priority", 5, "entryUrl", "https://fix.adera.et/"), true, MAPPER);
		assertThat(p.<Map<String, String>>as("name")).containsEntry("en", "Adera Fix");
		assertThat(p.<String>as("brandColor")).isEqualTo("#0F9D58");
		assertThat(p.<List<String>>as("markets")).containsExactly("ET", "US");
		assertThat(p.<String>as("supportEmail")).isEqualTo("support@adera.app");
	}

	@Test
	void createNeedsASlugAndAnEnglishName() {
		assertThat(errors(Map.of(), true)).containsKeys("slug", "name");
		assertThat(errors(Map.of("slug", "ok-slug", "name", Map.of("am", "x")), true)).containsKey("name");
		assertThat(errors(Map.of("slug", "Bad Slug!", "name", Map.of("en", "x")), true)).containsKey("slug");
		assertThat(errors(Map.of("slug", "-leading", "name", Map.of("en", "x")), true)).containsKey("slug");
	}

	@Test
	void theSlugIsPermanent() {
		assertThat(errors(Map.of("slug", "new-slug"), false)).containsKey("slug");
	}

	@Test
	void unknownFieldsAndBadValuesAreAllReported() {
		Map<String, String> e = errors(Map.of("nope", 1, "brandColor", "green", "markets", List.of("FR"), "priority", -1,
				"entryUrl", "ftp://x", "supportEmail", "x", "categories", List.of("Bad Cat"), "allowedOrigins", List.of("https://a.com/path")), false);
		assertThat(e).containsKeys("nope", "brandColor", "markets", "priority", "entryUrl", "supportEmail", "categories", "allowedOrigins");
	}

	@Test
	void languagesAreLimitedToSupportedOnes() {
		assertThat(errors(Map.of("name", Map.of("en", "x", "fr", "y")), false)).containsKey("name");
		assertThat(errors(Map.of("name", Map.of("en", "x".repeat(101))), false)).containsKey("name");
	}

	@Test
	void presentNullClearsOptionalFields() {
		Map<String, Object> body = new HashMap<>();
		body.put("website", null);
		body.put("brandColor", null);
		AppPatch p = AppPatch.parse(body, false, MAPPER);
		assertThat(p.has("website")).isTrue();
		assertThat(p.get("website")).isNull();
		assertThat(p.has("supportEmail")).isFalse();
	}

	@Test
	void httpIsOnlyAcceptedForLocalEntryUrls() {
		assertThat(AppPatch.parse(Map.of("entryUrl", "http://localhost:3000/"), false, MAPPER).<String>as("entryUrl")).isNotNull();
		assertThat(errors(Map.of("entryUrl", "http://evil.example/"), false)).containsKey("entryUrl");
		assertThat(errors(Map.of("website", "http://localhost/"), false)).containsKey("website");
	}

	@Test
	void textAndUrlFieldsRespectTheirColumnSizes() {
		String host = "https://example.com/";
		// website column is 300, entry_url 500, support_email 320
		AppPatch ok = AppPatch.parse(Map.of("website", host + "w".repeat(300 - host.length()), "entryUrl", host + "e".repeat(500 - host.length())), false, MAPPER);
		assertThat(ok.<String>as("website")).hasSize(300);
		assertThat(ok.<String>as("entryUrl")).hasSize(500);

		Map<String, String> e = errors(Map.of("website", host + "w".repeat(301 - host.length()), "entryUrl", host + "e".repeat(501 - host.length())), false);
		assertThat(e.get("website")).isEqualTo("max 300 characters");
		assertThat(e.get("entryUrl")).isEqualTo("max 500 characters");

		String longEmail = "a@" + "b".repeat(255) + "." + "c".repeat(100);
		assertThat(errors(Map.of("supportEmail", longEmail), false)).containsKey("supportEmail");
		assertThat(AppPatch.parse(Map.of("supportEmail", "a@" + "b".repeat(250) + ".com"), false, MAPPER).<String>as("supportEmail")).hasSize(256);

		for (String key : new String[] { "publisherName", "publisherLegalName", "supportPhone" }) {
			int max = key.equals("publisherName") ? 150 : key.equals("publisherLegalName") ? 200 : 30;
			assertThat(AppPatch.parse(Map.of(key, "x".repeat(max)), false, MAPPER).<String>as(key)).hasSize(max);
			assertThat(errors(Map.of(key, "x".repeat(max + 1)), false)).containsEntry(key, "max " + max + " characters");
		}
	}

}
