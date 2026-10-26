package app.adera.registry.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.adera.registry.common.Errors;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates mini-app fields (for create and for partial update). Absent key = unchanged, null = clear (where optional).
 * Unknown keys are rejected so typos do not silently do nothing.
 */
public final class AppPatch {

	public static final Pattern SLUG = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$");
	static final Set<String> LANGS = Set.of("en", "am", "om");
	static final Set<String> MARKETS = Set.of("ET", "US");
	static final Pattern COLOR = Pattern.compile("^#[0-9A-Fa-f]{6}$");
	static final Pattern CATEGORY = Pattern.compile("^[a-z][a-z0-9_-]{1,30}$");
	static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");
	/** Column sizes the validators must respect (mini_app.website, entry_url, support_email). */
	static final int MAX_WEBSITE = 300;
	static final int MAX_ENTRY_URL = 500;
	static final int MAX_EMAIL = 320;
	static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?$");
	static final Set<String> ALLOWED = Set.of("slug", "name", "description", "brandColor", "categories", "markets",
			"publisherName", "publisherLegalName", "supportEmail", "supportPhone", "website", "entryUrl", "allowedOrigins",
			"priority", "extra");

	private final Map<String, Object> values;

	private AppPatch(Map<String, Object> values) {
		this.values = values;
	}

	public boolean has(String key) {
		return values.containsKey(key);
	}

	public Object get(String key) {
		return values.get(key);
	}

	@SuppressWarnings("unchecked")
	public <T> T as(String key) {
		return (T) values.get(key);
	}

	/** @param creating true on create: slug is required, name is required, and slug may be set (it is immutable later) */
	public static AppPatch parse(Map<String, Object> body, boolean creating, JsonMapper mapper) {
		Map<String, String> errors = new LinkedHashMap<>();
		Map<String, Object> out = new LinkedHashMap<>();

		for (String key : body.keySet()) {
			if (!ALLOWED.contains(key)) {
				errors.put(key, "unknown field");
			}
			else if (key.equals("slug") && !creating) {
				errors.put(key, "the slug is permanent and cannot be changed");
			}
		}
		if (creating) {
			Object slug = body.get("slug");
			if (!(slug instanceof String s) || !SLUG.matcher(s).matches() || s.length() < 2) {
				errors.put("slug", "required: 2-40 chars, lowercase letters, digits and dashes (e.g. adera-fix)");
			}
			else {
				out.put("slug", s);
			}
			if (!body.containsKey("name")) {
				errors.put("name", "required (at least an English name)");
			}
		}
		if (body.containsKey("name")) {
			Map<String, String> v = i18n(body.get("name"), "name", 100, errors);
			if (v != null && !v.containsKey("en")) {
				errors.put("name", "an English ('en') name is required");
			}
			else if (v != null) {
				out.put("name", v);
			}
		}
		if (body.containsKey("description")) {
			Map<String, String> v = i18n(body.get("description"), "description", 1000, errors);
			if (v != null) {
				out.put("description", v);
			}
		}
		if (body.containsKey("brandColor")) {
			Object v = body.get("brandColor");
			if (v == null) {
				out.put("brandColor", null);
			}
			else if (v instanceof String s && COLOR.matcher(s).matches()) {
				out.put("brandColor", s.toUpperCase());
			}
			else {
				errors.put("brandColor", "must look like #0F9D58");
			}
		}
		if (body.containsKey("categories")) {
			List<String> v = stringList(body.get("categories"), "categories", 10, CATEGORY, "lowercase words like 'home' or 'travel'", errors);
			if (v != null) {
				out.put("categories", v);
			}
		}
		if (body.containsKey("markets")) {
			List<String> v = stringList(body.get("markets"), "markets", 5, null, null, errors);
			if (v != null) {
				List<String> upper = v.stream().map(String::toUpperCase).distinct().toList();
				if (upper.isEmpty() || !MARKETS.containsAll(upper)) {
					errors.put("markets", "must be a non-empty list of ET, US");
				}
				else {
					out.put("markets", new ArrayList<>(upper));
				}
			}
		}
		text(body, out, errors, "publisherName", 150);
		text(body, out, errors, "publisherLegalName", 200);
		text(body, out, errors, "supportPhone", 30);
		if (body.containsKey("supportEmail")) {
			Object v = body.get("supportEmail");
			if (v == null) {
				out.put("supportEmail", null);
			}
			else if (v instanceof String s && s.trim().length() <= MAX_EMAIL && EMAIL.matcher(s.trim()).matches()) {
				out.put("supportEmail", s.trim().toLowerCase());
			}
			else {
				errors.put("supportEmail", "must be a valid email address (max " + MAX_EMAIL + " characters)");
			}
		}
		url(body, out, errors, "website", false, MAX_WEBSITE);
		url(body, out, errors, "entryUrl", true, MAX_ENTRY_URL);
		if (body.containsKey("allowedOrigins")) {
			List<String> v = stringList(body.get("allowedOrigins"), "allowedOrigins", 10, ORIGIN, "origins like https://fix.adera.et (no path)", errors);
			if (v != null) {
				out.put("allowedOrigins", v);
			}
		}
		if (body.containsKey("priority")) {
			Object v = body.get("priority");
			if (v instanceof Integer i && i >= 0 && i <= 100_000) {
				out.put("priority", i);
			}
			else {
				errors.put("priority", "must be a whole number from 0 to 100000 (lower is shown first)");
			}
		}
		if (body.containsKey("extra")) {
			Object v = body.get("extra");
			if (!(v instanceof Map<?, ?>)) {
				errors.put("extra", "must be a JSON object");
			}
			else if (mapper.writeValueAsBytes(v).length > 16_384) {
				errors.put("extra", "too large (max 16384 bytes)");
			}
			else {
				out.put("extra", v);
			}
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		return new AppPatch(out);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> i18n(Object value, String field, int max, Map<String, String> errors) {
		if (!(value instanceof Map<?, ?> map)) {
			errors.put(field, "must be an object like {\"en\": \"...\", \"am\": \"...\"}");
			return null;
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
			if (!LANGS.contains(e.getKey())) {
				errors.put(field, "unsupported language '" + e.getKey() + "' (use en, am, om)");
				return null;
			}
			String text = e.getValue() == null ? "" : String.valueOf(e.getValue()).strip();
			if (text.length() > max) {
				errors.put(field, e.getKey() + " is longer than " + max + " characters");
				return null;
			}
			if (!text.isEmpty()) {
				out.put(e.getKey(), text);
			}
		}
		return out;
	}

	private static List<String> stringList(Object value, String field, int max, Pattern each, String hint, Map<String, String> errors) {
		if (!(value instanceof List<?> list) || list.size() > max) {
			errors.put(field, "must be a list of at most " + max + " items");
			return null;
		}
		List<String> out = new ArrayList<>();
		for (Object item : list) {
			if (!(item instanceof String s) || s.isBlank() || (each != null && !each.matcher(s.trim()).matches())) {
				errors.put(field, hint == null ? "invalid item" : "each item must be " + hint);
				return null;
			}
			out.add(s.trim());
		}
		return out;
	}

	private static void text(Map<String, Object> body, Map<String, Object> out, Map<String, String> errors, String key, int max) {
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

	private static void url(Map<String, Object> body, Map<String, Object> out, Map<String, String> errors, String key, boolean allowLocalHttp, int max) {
		if (!body.containsKey(key)) {
			return;
		}
		Object v = body.get(key);
		if (v == null) {
			out.put(key, null);
			return;
		}
		String s = String.valueOf(v).trim();
		boolean https = s.startsWith("https://");
		boolean localHttp = allowLocalHttp && (s.startsWith("http://localhost") || s.startsWith("http://127.0.0.1"));
		if (s.length() > max) {
			errors.put(key, "max " + max + " characters");
		}
		else if (!(https || localHttp) || s.chars().anyMatch(Character::isWhitespace)) {
			errors.put(key, "must be an https:// URL");
		}
		else {
			out.put(key, s);
		}
	}

}
