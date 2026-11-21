package app.adera.registry.banner;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import app.adera.registry.app.AppPatch;
import app.adera.registry.common.Errors;

/**
 * Checks each banner field on its own (create and partial update). Absent key = unchanged, null = clear (optional fields
 * only). Unknown keys are refused so a typo never silently does nothing. Rules that combine fields (a MINI_APP action
 * needs an app, the end is after the start, ...) are in {@link BannerRules}, applied to the result after merging.
 */
public final class BannerPatch {

	static final int MAX_TITLE = 80;
	static final int MAX_SUBTITLE = 160;
	static final int MAX_TARGET = 500;
	static final int MAX_PRIORITY = 100_000;
	static final Set<String> ALLOWED = Set.of("title", "subtitle", "placement", "category", "appId", "actionType", "target",
			"startsAt", "endsAt", "priority");

	private final Map<String, Object> values;

	private BannerPatch(Map<String, Object> values) {
		this.values = values;
	}

	public boolean has(String key) {
		return values.containsKey(key);
	}

	@SuppressWarnings("unchecked")
	public <T> T as(String key) {
		return (T) values.get(key);
	}

	/** Field names in the request, for the audit log. */
	public Set<String> keys() {
		return values.keySet();
	}

	/** @param creating true on create: title, placement, category, actionType and startsAt are required */
	public static BannerPatch parse(Map<String, Object> body, boolean creating) {
		Map<String, String> errors = new LinkedHashMap<>();
		Map<String, Object> out = new LinkedHashMap<>();
		for (String key : body.keySet()) {
			if (!ALLOWED.contains(key)) {
				errors.put(key, "unknown field");
			}
		}
		if (creating) {
			for (String required : List.of("title", "placement", "category", "actionType", "startsAt")) {
				if (body.get(required) == null) {
					errors.put(required, "required");
				}
			}
		}
		if (body.containsKey("title") && body.get("title") != null) {
			Map<String, String> v = AppPatch.i18n(body.get("title"), "title", MAX_TITLE, errors);
			if (v != null && !v.containsKey("en")) {
				errors.put("title", "an English ('en') title is required");
			}
			else if (v != null) {
				out.put("title", v);
			}
		}
		else if (body.containsKey("title") && !creating) {
			errors.put("title", "cannot be removed (an English title is required)");
		}
		if (body.containsKey("subtitle")) {
			Object raw = body.get("subtitle");
			Map<String, String> v = raw == null ? new LinkedHashMap<>() : AppPatch.i18n(raw, "subtitle", MAX_SUBTITLE, errors);
			if (v != null) {
				out.put("subtitle", v);
			}
		}
		enumField(body, out, errors, "placement", Banner.Placement.class, creating);
		enumField(body, out, errors, "category", Banner.Category.class, creating);
		enumField(body, out, errors, "actionType", Banner.ActionType.class, creating);
		if (body.containsKey("appId")) {
			Object v = body.get("appId");
			if (v == null) {
				out.put("appId", null);
			}
			else {
				try {
					out.put("appId", UUID.fromString(String.valueOf(v).trim()));
				}
				catch (IllegalArgumentException e) {
					errors.put("appId", "must be a mini app id (UUID)");
				}
			}
		}
		if (body.containsKey("target")) {
			Object v = body.get("target");
			if (v == null) {
				out.put("target", null);
			}
			else if (!(v instanceof String s)) {
				errors.put("target", "must be a string");
			}
			else {
				String t = s.strip();
				if (t.length() > MAX_TARGET) {
					errors.put("target", "max " + MAX_TARGET + " characters");
				}
				else {
					out.put("target", t.isEmpty() ? null : t);
				}
			}
		}
		time(body, out, errors, "startsAt", creating, false);
		time(body, out, errors, "endsAt", creating, true);
		if (body.containsKey("priority")) {
			Object v = body.get("priority");
			if (v instanceof Integer i && i >= 0 && i <= MAX_PRIORITY) {
				out.put("priority", i);
			}
			else {
				errors.put("priority", "must be a whole number from 0 to " + MAX_PRIORITY + " (higher is shown first)");
			}
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		return new BannerPatch(out);
	}

	/** "HOME_HERO, FEATURED, PROMOTIONS or CATALOG_TOP" */
	static <E extends Enum<E>> String choices(Class<E> type) {
		List<String> names = Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
		return names.subList(0, names.size() - 1).stream().collect(Collectors.joining(", ")) + " or " + names.getLast();
	}

	/** Case-insensitive enum value, or null when it is not one (blank counts as not one). */
	public static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static <E extends Enum<E>> void enumField(Map<String, Object> body, Map<String, Object> out, Map<String, String> errors,
			String key, Class<E> type, boolean creating) {
		if (!body.containsKey(key) || (creating && body.get(key) == null)) {
			return;
		}
		Object v = body.get(key);
		E parsed = v instanceof String s ? parseEnum(type, s) : null;
		if (parsed == null) {
			errors.put(key, "must be " + choices(type));
		}
		else {
			out.put(key, parsed);
		}
	}

	private static void time(Map<String, Object> body, Map<String, Object> out, Map<String, String> errors, String key, boolean creating,
			boolean nullable) {
		if (!body.containsKey(key) || (creating && !nullable && body.get(key) == null)) {
			return;
		}
		Object v = body.get(key);
		if (v == null) {
			if (nullable) {
				out.put(key, null);
			}
			else {
				errors.put(key, "required");
			}
			return;
		}
		try {
			out.put(key, OffsetDateTime.parse(String.valueOf(v).trim()).toInstant());
		}
		catch (DateTimeParseException e) {
			errors.put(key, "must be an ISO-8601 time with an offset, like 2026-12-01T00:00:00Z or 2026-12-01T09:00:00+03:00");
		}
	}

	/** The values a banner would have after this patch, for {@link BannerRules}. */
	public BannerRules.Candidate merge(Banner current) {
		return new BannerRules.Candidate(
				has("category") ? as("category") : current == null ? null : current.getCategory(),
				has("appId") ? as("appId") : current == null ? null : current.getAppId(),
				has("actionType") ? as("actionType") : current == null ? null : current.getActionType(),
				has("target") ? as("target") : current == null ? null : current.getTarget(),
				has("startsAt") ? (Instant) as("startsAt") : current == null ? null : current.getStartsAt(),
				has("endsAt") ? (Instant) as("endsAt") : current == null ? null : current.getEndsAt());
	}

}
