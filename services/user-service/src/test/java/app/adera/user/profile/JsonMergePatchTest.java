package app.adera.user.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonMergePatchTest {

	@Test
	void addsChangesAndRemovesKeys() {
		Map<String, Object> target = new LinkedHashMap<>(Map.of("a", 1, "b", "x", "c", true));
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("b", "y");
		patch.put("c", null); // remove
		patch.put("d", 4); // add
		assertThat(JsonMergePatch.apply(target, patch)).containsOnly(Map.entry("a", 1), Map.entry("b", "y"), Map.entry("d", 4));
	}

	@Test
	void nestedObjectsMergeInsteadOfReplace() {
		Map<String, Object> target = Map.of("prefs", Map.of("theme", "dark", "font", 14));
		Map<String, Object> patch = Map.of("prefs", Map.of("font", 16));
		assertThat(JsonMergePatch.apply(target, patch)).isEqualTo(Map.of("prefs", Map.of("theme", "dark", "font", 16)));
	}

	@Test
	void arraysAndScalarsReplaceWholesale() {
		Map<String, Object> target = Map.of("tags", java.util.List.of("a", "b"));
		assertThat(JsonMergePatch.apply(target, Map.of("tags", java.util.List.of("c")))).isEqualTo(Map.of("tags", java.util.List.of("c")));
	}

	@Test
	void doesNotMutateItsInputs() {
		Map<String, Object> target = new LinkedHashMap<>(Map.of("a", 1));
		JsonMergePatch.apply(target, Map.of("a", 2));
		assertThat(target).containsEntry("a", 1);
	}

}
