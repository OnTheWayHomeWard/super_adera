package app.adera.notification.partner.delivery;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code data} map of a push message. FCM only carries string values, so the partner's {@code data} is converted:
 * text stays as it is, numbers and booleans become their JSON text, and objects and arrays become JSON strings (never
 * Java's {@code {a=1}} form). A {@code null} value is left out (FCM refuses null). The server adds
 * {@code notificationId} and {@code appId}; partners may not use those two names (refused at acceptance), nor the names
 * FCM reserves.
 */
public final class PushPayload {

	/** Added by the server to every push. */
	public static final Set<String> SERVER_KEYS = Set.of("notificationId", "appId");

	private PushPayload() {
	}

	/** @return a message per problem key, or null when every key is acceptable */
	public static String reservedKeyProblem(String key) {
		if (SERVER_KEYS.contains(key)) {
			return "'" + key + "' is added by Adera to every push; choose another name";
		}
		String lower = key.toLowerCase(Locale.ROOT);
		if (lower.equals("from") || lower.equals("message_type") || lower.startsWith("google") || lower.startsWith("gcm")) {
			return "'" + key + "' is reserved by Firebase Cloud Messaging; choose another name";
		}
		return null;
	}

	public static Map<String, String> build(Map<String, Object> data, UUID notificationId, UUID appId, JsonMapper mapper) {
		Map<String, String> out = new LinkedHashMap<>();
		data.forEach((k, v) -> {
			if (v == null) {
				return;
			}
			out.put(k, v instanceof CharSequence || v instanceof Number || v instanceof Boolean ? String.valueOf(v) : mapper.writeValueAsString(v));
		});
		out.put("notificationId", notificationId.toString());
		out.put("appId", appId.toString());
		return out;
	}

}
