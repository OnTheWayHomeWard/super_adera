package app.adera.user.profile;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

/**
 * A validated partial update of the profile. A key that is absent leaves the field alone; a key set to null
 * clears it (where the field is optional). Unknown keys are rejected, so typos do not silently do nothing.
 */
public final class ProfilePatch {

	static final Set<String> ALLOWED = Set.of("firstName", "lastName", "gender", "dateOfBirth", "language", "market",
			"timezone", "termsAcceptedVersion", "extra");

	static final Set<String> LANGUAGES = Set.of("am", "en", "om");

	static final Set<String> MARKETS = Set.of("ET", "US");

	private final Map<String, Object> values;

	private ProfilePatch(Map<String, Object> values) {
		this.values = values;
	}

	public boolean has(String key) {
		return values.containsKey(key);
	}

	public Object get(String key) {
		return values.get(key);
	}

	@SuppressWarnings("unchecked")
	public Map<String, Object> extra() {
		return (Map<String, Object>) values.get("extra");
	}

	/** Validates and normalises. Throws {@link ValidationFailedException} listing every problem. */
	public static ProfilePatch parse(Map<String, Object> body, JsonMapper mapper, int extraMaxBytes, LocalDate today) {
		Map<String, String> errors = new LinkedHashMap<>();
		Map<String, Object> out = new LinkedHashMap<>();

		for (String key : body.keySet()) {
			if (!ALLOWED.contains(key)) {
				errors.put(key, "unknown field");
			}
		}
		text(body, out, errors, "firstName", 100);
		text(body, out, errors, "lastName", 100);
		text(body, out, errors, "termsAcceptedVersion", 32);

		if (body.containsKey("gender")) {
			Object v = body.get("gender");
			if (v == null) {
				out.put("gender", null);
			}
			else {
				try {
					out.put("gender", AppUser.Gender.valueOf(String.valueOf(v).trim().toUpperCase(Locale.ROOT)));
				}
				catch (IllegalArgumentException e) {
					errors.put("gender", "must be one of MALE, FEMALE, OTHER, PREFER_NOT_TO_SAY");
				}
			}
		}
		if (body.containsKey("dateOfBirth")) {
			Object v = body.get("dateOfBirth");
			if (v == null) {
				out.put("dateOfBirth", null);
			}
			else {
				try {
					LocalDate d = LocalDate.parse(String.valueOf(v));
					if (d.isAfter(today) || d.getYear() < 1900) {
						errors.put("dateOfBirth", "must be a real past date (YYYY-MM-DD)");
					}
					else {
						out.put("dateOfBirth", d);
					}
				}
				catch (DateTimeException e) {
					errors.put("dateOfBirth", "must be an ISO date (YYYY-MM-DD)");
				}
			}
		}
		required(body, out, errors, "language", LANGUAGES, false);
		required(body, out, errors, "market", MARKETS, true);

		if (body.containsKey("timezone")) {
			Object v = body.get("timezone");
			try {
				if (v == null) {
					throw new DateTimeException("null");
				}
				ZoneId.of(String.valueOf(v));
				out.put("timezone", String.valueOf(v));
			}
			catch (DateTimeException e) {
				errors.put("timezone", "must be a valid IANA time zone, e.g. Africa/Addis_Ababa");
			}
		}
		if (body.containsKey("extra")) {
			Object v = body.get("extra");
			if (!(v instanceof Map<?, ?>)) {
				errors.put("extra", "must be a JSON object (use null values inside it to remove keys)");
			}
			else if (mapper.writeValueAsBytes(v).length > extraMaxBytes) {
				errors.put("extra", "too large (max " + extraMaxBytes + " bytes)");
			}
			else {
				out.put("extra", v);
			}
		}
		if (!errors.isEmpty()) {
			throw new ValidationFailedException(errors);
		}
		return new ProfilePatch(out);
	}

	private static void text(Map<String, Object> body, Map<String, Object> out, Map<String, String> errors, String key,
			int max) {
		if (!body.containsKey(key)) {
			return;
		}
		Object v = body.get(key);
		if (v == null) {
			out.put(key, null);
			return;
		}
		String s = String.valueOf(v).strip();
		if (s.length() > max) {
			errors.put(key, "max " + max + " characters");
		}
		else {
			out.put(key, s.isEmpty() ? null : s);
		}
	}

	private static void required(Map<String, Object> body, Map<String, Object> out, Map<String, String> errors,
			String key, Set<String> allowed, boolean upper) {
		if (!body.containsKey(key)) {
			return;
		}
		Object v = body.get(key);
		String s = v == null ? "" : String.valueOf(v).trim();
		s = upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT);
		if (allowed.contains(s)) {
			out.put(key, s);
		}
		else {
			errors.put(key, "must be one of " + String.join(", ", new java.util.TreeSet<>(allowed)));
		}
	}

}
