package app.adera.user.profile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RFC 7386 JSON Merge Patch on maps: a null value removes the key, an object merges recursively,
 * anything else replaces. This is how clients edit the free-form {@code extra} column without
 * having to resend (or accidentally erase) keys they do not know about.
 */
public final class JsonMergePatch {

	private JsonMergePatch() {
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> apply(Map<String, Object> target, Map<String, Object> patch) {
		Map<String, Object> result = new LinkedHashMap<>(target == null ? Map.of() : target);
		for (Map.Entry<String, Object> entry : patch.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();
			if (value == null) {
				result.remove(key);
			}
			else if (value instanceof Map<?, ?> nested) {
				Object existing = result.get(key);
				Map<String, Object> base = existing instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
				result.put(key, apply(base, (Map<String, Object>) nested));
			}
			else {
				result.put(key, value);
			}
		}
		return result;
	}

}
