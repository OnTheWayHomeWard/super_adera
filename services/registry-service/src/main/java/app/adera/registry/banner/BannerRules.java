package app.adera.registry.banner;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rules that combine banner fields, checked on the values a banner would have after a create or an update:
 * <ul>
 * <li>the end is after the start;</li>
 * <li>a FEATURED_APP category or a MINI_APP action needs a linked app;</li>
 * <li>the target fits the action type: MINI_APP a route starting with "/" (or none = the app's home), INTERNAL an
 * {@code adera://} link, EXTERNAL an https URL on an allowed domain, NONE nothing.</li>
 * </ul>
 */
public final class BannerRules {

	public record Candidate(Banner.Category category, UUID appId, Banner.ActionType actionType, String target, Instant startsAt,
			Instant endsAt) {
	}

	/** URL characters only (RFC 3986 unreserved, reserved and %): no spaces, quotes, angle brackets or backslashes. */
	private static final String URL_CHARS = "[A-Za-z0-9._~!$&'()*+,;=:@%/?#\\[\\]-]";
	static final Pattern ROUTE = Pattern.compile("^/(?!/)" + URL_CHARS + "*$");
	static final Pattern INTERNAL = Pattern.compile("^adera://[A-Za-z0-9]" + URL_CHARS + "*$");

	private BannerRules() {
	}

	/** Field name -> message for every rule broken (empty when all is well). */
	public static Map<String, String> check(Candidate c, ExternalDomains domains) {
		Map<String, String> errors = new LinkedHashMap<>();
		if (c.startsAt() != null && c.endsAt() != null && !c.endsAt().isAfter(c.startsAt())) {
			errors.put("endsAt", "must be later than startsAt");
		}
		if (c.appId() == null && c.category() == Banner.Category.FEATURED_APP) {
			errors.put("appId", "required when category is FEATURED_APP");
		}
		if (c.appId() == null && c.actionType() == Banner.ActionType.MINI_APP) {
			errors.put("appId", "required when actionType is MINI_APP");
		}
		String t = c.target();
		if (c.actionType() != null) {
			switch (c.actionType()) {
				case MINI_APP -> {
					if (t != null && !ROUTE.matcher(t).matches()) {
						errors.put("target", "must be a route inside the mini app starting with / (like /promo/first-ride), or empty for the app's home");
					}
				}
				case INTERNAL -> {
					if (t == null) {
						errors.put("target", "required for INTERNAL: a super-app link like adera://wallet");
					}
					else if (!INTERNAL.matcher(t).matches()) {
						errors.put("target", "must be a super-app link like adera://wallet");
					}
				}
				case EXTERNAL -> {
					String problem = externalProblem(t, domains);
					if (problem != null) {
						errors.put("target", problem);
					}
				}
				case NONE -> {
					if (t != null) {
						errors.put("target", "must be empty when actionType is NONE");
					}
				}
			}
		}
		return errors;
	}

	static String externalProblem(String t, ExternalDomains domains) {
		if (t == null) {
			return "required for EXTERNAL: an https:// URL";
		}
		URI uri;
		try {
			uri = new URI(t);
		}
		catch (URISyntaxException e) {
			return "must be an https:// URL";
		}
		if (!"https".equals(uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost() == null
				|| !t.regionMatches(true, 0, "https://", 0, 8)) {
			return "must be an https:// URL";
		}
		if (uri.getRawUserInfo() != null) {
			return "must not contain a user name or password";
		}
		if (domains.list().isEmpty()) {
			return "external links are switched off on this deployment (BANNER_EXTERNAL_DOMAINS is empty)";
		}
		if (!domains.allows(uri.getHost())) {
			return "the domain " + uri.getHost().toLowerCase(Locale.ROOT) + " is not allowed for banners (allowed: "
					+ String.join(", ", domains.list()) + ", and their subdomains)";
		}
		return null;
	}

}
