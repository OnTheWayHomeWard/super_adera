package app.adera.notification.partner.template;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.adera.notification.partner.ApiException;

/**
 * Validation of what an admin writes into a template, and of the values a partner fills in. Templates are the guard
 * against abuse: partners cannot send free text, only the declared values, and those are length-limited and link-checked.
 */
public final class TemplateRules {

	public static final Set<String> KINDS = Set.of("DIRECT", "IN_APP");
	public static final Set<String> CATEGORIES = Set.of("TRANSACTIONAL", "REMINDER", "PROMOTIONAL");
	static final Set<String> LANGS = Set.of("en", "am", "om");
	static final Set<String> TEXT_KEYS = Set.of("title", "body", "sms", "emailSubject", "emailHtml");
	static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,58}[a-z0-9]$");
	static final Pattern PARAM_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,29}$");
	static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)}");
	/** Top-level domains that make a dotted word look like a host. */
	private static final String TLDS = "com|net|org|et|app|io|co|me|ly|xyz|info|biz|link|click";
	/** Of those, the ones no ordinary name or word ends with: a bare {@code evil.com} is a link, {@code Hana.co} is a name. */
	private static final String STRONG_TLDS = "com|net|org|et|app|info|biz|xyz|link|click";
	/**
	 * What counts as a link inside a partner-supplied value (case-insensitive). Blocked:
	 * <ol>
	 * <li>a scheme: {@code http:}, {@code https:} or any {@code xyz://};</li>
	 * <li>{@code www.};</li>
	 * <li>a host with a known TLD followed by a path, query, fragment or port: {@code name.tld/path}, {@code name.co?x=1},
	 * {@code name.com:8080};</li>
	 * <li>a bare host with a strong TLD (com, net, org, et, app, info, biz, xyz, link, click): {@code evil.com};</li>
	 * <li>a host of three or more labels ending in any known TLD: {@code login.evil.co}.</li>
	 * </ol>
	 * Allowed: ordinary text, and a single word with an ambiguous TLD ({@code co, me, io, ly}) and nothing after it, such as
	 * {@code Hana.co} or {@code Sam.me}, which are names far more often than hosts.
	 */
	static final Pattern LINK_LIKE = Pattern.compile("(?i)(?:\\bhttps?:|://|\\bwww\\.|\\b[a-z0-9-]+\\.(?:" + TLDS + ")(?:[/?#]|:\\d)"
			+ "|\\b[a-z0-9-]+\\.(?:" + STRONG_TLDS + ")\\b|\\b(?:[a-z0-9-]+\\.){2,}(?:" + TLDS + ")\\b)");
	static final Map<String, Integer> MAX_LEN = Map.of("title", 100, "body", 1000, "sms", 320, "emailSubject", 150, "emailHtml", 20_000);

	private TemplateRules() {
	}

	public record Parsed(String code, String kind, String category, List<Map<String, Object>> params,
			Map<String, Map<String, String>> content, Boolean active, Instant validFrom, Instant expiresAt,
			Set<String> present) {
	}

	/** @param creating on create: code, kind, category and content are required, and kind/code can be set (immutable later) */
	@SuppressWarnings("unchecked")
	public static Parsed parse(Map<String, Object> body, boolean creating, String kindForUpdate,
			List<Map<String, Object>> existingParams, Instant now) {
		Map<String, String> errors = new LinkedHashMap<>();
		Set<String> allowed = creating ? Set.of("appId", "code", "kind", "category", "params", "content", "active", "validFrom", "expiresAt")
				: Set.of("category", "params", "content", "active", "validFrom", "expiresAt");
		for (String key : body.keySet()) {
			if (!allowed.contains(key)) {
				errors.put(key, creating || !Set.of("code", "kind", "appId").contains(key) ? "unknown field" : "cannot be changed after creation");
			}
		}
		String code = null;
		String kind = kindForUpdate;
		if (creating) {
			Object c = body.get("code");
			if (!(c instanceof String s) || !CODE.matcher(s).matches()) {
				errors.put("code", "required: 2-60 chars of lowercase letters, digits, '_', '.', '-' (e.g. job-accepted)");
			}
			else {
				code = s;
			}
			Object k = body.get("kind");
			kind = k == null ? null : String.valueOf(k).toUpperCase(Locale.ROOT);
			if (kind == null || !KINDS.contains(kind)) {
				errors.put("kind", "required: DIRECT (SMS or email) or IN_APP (inbox + push)");
			}
		}
		String category = null;
		if (body.containsKey("category") || creating) {
			Object c = body.get("category");
			category = c == null ? null : String.valueOf(c).toUpperCase(Locale.ROOT);
			if (category == null || !CATEGORIES.contains(category)) {
				errors.put("category", "must be TRANSACTIONAL, REMINDER or PROMOTIONAL");
			}
		}
		List<Map<String, Object>> params = null;
		if (body.containsKey("params") || creating) {
			params = params(body.get("params") == null && creating ? List.of() : body.get("params"), errors);
		}
		Map<String, Map<String, String>> content = null;
		if (body.containsKey("content") || creating) {
			content = content(body.get("content"), kind, params != null ? params : existingParams, errors);
		}
		Boolean active = null;
		if (body.containsKey("active")) {
			if (body.get("active") instanceof Boolean b) {
				active = b;
			}
			else {
				errors.put("active", "must be true or false");
			}
		}
		Instant validFrom = instant(body, "validFrom", errors);
		Instant expiresAt = instant(body, "expiresAt", errors);
		if (validFrom != null && expiresAt != null && !expiresAt.isAfter(validFrom)) {
			errors.put("expiresAt", "must be after validFrom");
		}
		if (creating && expiresAt != null && !expiresAt.isAfter(now)) {
			errors.put("expiresAt", "must be in the future");
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}
		return new Parsed(code, kind, category, params, content, active, validFrom, expiresAt, Set.copyOf(body.keySet()));
	}

	private static Instant instant(Map<String, Object> body, String key, Map<String, String> errors) {
		Object v = body.get(key);
		if (v == null) {
			return null;
		}
		try {
			return Instant.parse(String.valueOf(v));
		}
		catch (DateTimeParseException e) {
			errors.put(key, "must be an ISO-8601 instant like 2027-01-01T00:00:00Z");
			return null;
		}
	}

	private static List<Map<String, Object>> params(Object value, Map<String, String> errors) {
		if (!(value instanceof List<?> list) || list.size() > 10) {
			errors.put("params", "must be a list of at most 10 declared values");
			return null;
		}
		List<Map<String, Object>> out = new ArrayList<>();
		Set<String> names = new LinkedHashSet<>();
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> m)) {
				errors.put("params", "each item must be an object like {\"name\":\"name\",\"maxLength\":50}");
				return null;
			}
			Object name = m.get("name");
			if (!(name instanceof String n) || !PARAM_NAME.matcher(n).matches() || !names.add(n)) {
				errors.put("params", "each name must be unique, start with a letter and use letters, digits or '_' (max 30)");
				return null;
			}
			Object maxLength = m.get("maxLength") == null ? 100 : m.get("maxLength");
			if (!(maxLength instanceof Integer ml) || ml < 1 || ml > 500) {
				errors.put("params", "maxLength of '" + n + "' must be 1-500");
				return null;
			}
			Map<String, Object> clean = new LinkedHashMap<>();
			clean.put("name", n);
			clean.put("maxLength", ml);
			clean.put("required", !Boolean.FALSE.equals(m.get("required")));
			clean.put("allowLinks", Boolean.TRUE.equals(m.get("allowLinks")));
			out.add(clean);
		}
		return out;
	}

	/** Re-checks already stored content against a changed set of params (every {placeholder} must still be declared). */
	public static Map<String, String> placeholderErrors(Map<String, Map<String, String>> content, List<Map<String, Object>> params) {
		Set<String> declared = new LinkedHashSet<>();
		params.forEach(p -> declared.add((String) p.get("name")));
		Map<String, String> errors = new LinkedHashMap<>();
		content.forEach((lang, texts) -> texts.forEach((key, text) -> {
			Matcher m = PLACEHOLDER.matcher(text);
			while (m.find()) {
				if (!declared.contains(m.group(1))) {
					errors.put("content." + lang + "." + key, "uses {" + m.group(1) + "} which is not a declared param");
				}
			}
		}));
		return errors;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Map<String, String>> content(Object value, String kind, List<Map<String, Object>> params, Map<String, String> errors) {
		if (!(value instanceof Map<?, ?> languages) || languages.isEmpty()) {
			errors.put("content", "required: {\"en\": {\"title\": \"...\", \"body\": \"...\"}, \"am\": {...}}");
			return null;
		}
		Set<String> declared = new LinkedHashSet<>();
		if (params != null) {
			params.forEach(p -> declared.add((String) p.get("name")));
		}
		Map<String, Map<String, String>> out = new LinkedHashMap<>();
		for (Map.Entry<String, Object> lang : ((Map<String, Object>) languages).entrySet()) {
			if (!LANGS.contains(lang.getKey())) {
				errors.put("content", "unsupported language '" + lang.getKey() + "' (use en, am, om)");
				return null;
			}
			if (!(lang.getValue() instanceof Map<?, ?> texts)) {
				errors.put("content." + lang.getKey(), "must be an object");
				return null;
			}
			Map<String, String> clean = new LinkedHashMap<>();
			for (Map.Entry<String, Object> t : ((Map<String, Object>) texts).entrySet()) {
				if (!TEXT_KEYS.contains(t.getKey())) {
					errors.put("content." + lang.getKey(), "unknown text '" + t.getKey() + "' (use title, body, sms, emailSubject, emailHtml)");
					return null;
				}
				String text = t.getValue() == null ? "" : String.valueOf(t.getValue());
				if (text.isBlank()) {
					continue;
				}
				if (text.length() > MAX_LEN.get(t.getKey())) {
					errors.put("content." + lang.getKey() + "." + t.getKey(), "longer than " + MAX_LEN.get(t.getKey()) + " characters");
					return null;
				}
				Matcher m = PLACEHOLDER.matcher(text);
				while (m.find()) {
					if (!declared.contains(m.group(1))) {
						errors.put("content." + lang.getKey() + "." + t.getKey(), "uses {" + m.group(1) + "} which is not a declared param");
						return null;
					}
				}
				clean.put(t.getKey(), text);
			}
			out.put(lang.getKey(), clean);
		}
		Map<String, String> en = out.get("en");
		if (en == null || en.getOrDefault("body", "").isBlank()) {
			errors.put("content.en.body", "an English body is required");
			return null;
		}
		if ("IN_APP".equals(kind) && en.getOrDefault("title", "").isBlank()) {
			errors.put("content.en.title", "an English title is required for IN_APP templates");
			return null;
		}
		return out;
	}

	/**
	 * Checks the values a partner sent against the declared params. Throws 422 with a code a partner can act on.
	 * Values are plain text: length-limited, no control characters, and no links unless the param allows them
	 * (stops a "name" field being used to deliver a phishing link inside an official message).
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, String> checkValues(List<Map<String, Object>> declared, Map<String, Object> sent) {
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		Map<String, String> clean = new LinkedHashMap<>();
		Set<String> names = new LinkedHashSet<>();
		for (Map<String, Object> p : declared) {
			names.add((String) p.get("name"));
		}
		for (String key : sent.keySet()) {
			if (!names.contains(key)) {
				fieldErrors.put(key, "not a parameter of this template");
			}
		}
		for (Map<String, Object> p : declared) {
			String name = (String) p.get("name");
			Object raw = sent.get(name);
			if (raw == null) {
				if (Boolean.TRUE.equals(p.get("required"))) {
					fieldErrors.put(name, "required");
				}
				continue;
			}
			if (!(raw instanceof String) && !(raw instanceof Number) && !(raw instanceof Boolean)) {
				fieldErrors.put(name, "must be text or a number");
				continue;
			}
			String text = String.valueOf(raw).strip();
			if (text.isEmpty() && Boolean.TRUE.equals(p.get("required"))) {
				fieldErrors.put(name, "required");
			}
			else if (text.length() > (Integer) p.get("maxLength")) {
				fieldErrors.put(name, "longer than " + p.get("maxLength") + " characters");
			}
			else if (text.chars().anyMatch(Character::isISOControl)) {
				fieldErrors.put(name, "contains control characters");
			}
			else if (!Boolean.TRUE.equals(p.get("allowLinks")) && LINK_LIKE.matcher(text).find()) {
				fieldErrors.put(name, "links are not allowed in this value");
			}
			else {
				clean.put(name, text);
			}
		}
		if (!fieldErrors.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("fields", fieldErrors);
			throw new ApiException(422, "param_invalid", details);
		}
		return clean;
	}

}
