package app.adera.user.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProfilePatchTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

	private static ProfilePatch parse(Map<String, Object> body) {
		return ProfilePatch.parse(body, MAPPER, 200, TODAY);
	}

	private static Map<String, String> errors(Map<String, Object> body) {
		return ((ValidationFailedException) catchThrowable(body)).fields();
	}

	private static Throwable catchThrowable(Map<String, Object> body) {
		return org.assertj.core.api.Assertions.catchThrowable(() -> parse(body));
	}

	@Test
	void normalisesValidValues() {
		var patch = parse(Map.of("firstName", "  Selam ", "gender", "female", "dateOfBirth", "1995-04-12", "language", "AM",
				"market", "us", "timezone", "Africa/Addis_Ababa"));
		assertThat(patch.get("firstName")).isEqualTo("Selam");
		assertThat(patch.get("gender")).isEqualTo(AppUser.Gender.FEMALE);
		assertThat(patch.get("dateOfBirth")).isEqualTo(LocalDate.of(1995, 4, 12));
		assertThat(patch.get("language")).isEqualTo("am");
		assertThat(patch.get("market")).isEqualTo("US");
	}

	@Test
	void presentNullClearsAndAbsentLeavesAlone() {
		Map<String, Object> body = new HashMap<>();
		body.put("lastName", null);
		body.put("gender", null);
		var patch = parse(body);
		assertThat(patch.has("lastName")).isTrue();
		assertThat(patch.get("lastName")).isNull();
		assertThat(patch.has("gender")).isTrue();
		assertThat(patch.has("firstName")).isFalse();
	}

	@Test
	void blankNameBecomesNull() {
		assertThat(parse(Map.of("firstName", "   ")).get("firstName")).isNull();
	}

	@Test
	void unknownFieldsAreRejectedSoTyposDoNotSilentlyDoNothing() {
		assertThat(errors(Map.of("firstNmae", "x"))).containsKey("firstNmae");
	}

	@Test
	void reportsEveryProblemAtOnce() {
		Map<String, String> e = errors(Map.of("gender", "robot", "dateOfBirth", "2030-01-01", "language", "fr", "market", "ZZ",
				"timezone", "Mars/Base"));
		assertThat(e).containsKeys("gender", "dateOfBirth", "language", "market", "timezone");
	}

	@Test
	void dateOfBirthMustBeRealAndPast() {
		assertThat(errors(Map.of("dateOfBirth", "yesterday"))).containsKey("dateOfBirth");
		assertThat(errors(Map.of("dateOfBirth", "1800-01-01"))).containsKey("dateOfBirth");
	}

	@Test
	void extraMustBeAnObjectAndSmall() {
		assertThat(errors(Map.of("extra", "string"))).containsKey("extra");
		assertThat(errors(Map.of("extra", java.util.List.of(1)))).containsKey("extra");
		assertThat(errors(Map.of("extra", Map.of("blob", "x".repeat(500))))).containsKey("extra");
		assertThat(parse(Map.of("extra", Map.of("k", "v"))).extra()).containsEntry("k", "v");
	}

	@Test
	void nameLengthIsLimited() {
		assertThatThrownBy(() -> parse(Map.of("firstName", "x".repeat(101)))).isInstanceOf(ValidationFailedException.class);
	}

}
