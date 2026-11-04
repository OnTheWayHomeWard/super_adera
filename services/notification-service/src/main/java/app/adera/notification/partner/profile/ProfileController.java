package app.adera.notification.partner.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.UpstreamUnavailableException;
import app.adera.notification.partner.auth.PartnerContext;
import app.adera.notification.partner.auth.UserDirectoryClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/users/{alias}/profile}: what the user allowed this mini app to read, and nothing else (docs section 14, path B).
 * Signed like the rest of the partner API (ADERA-SIG-1, checked by PartnerAuthFilter, which also enforces the environment and the
 * allowed server addresses); the answer is signed by us. The data is owned by user-service, which releases only the fields of
 * capabilities the user GRANTED; this class never sees the rest. Fields the user did not allow are ABSENT (never null); the
 * {@code granted} list says which capabilities were allowed. {@code ?fields=user.full_name,...} lets a mini app say what it needs:
 * if one of those is not allowed the answer is {@code 403 consent_required} with the capability and the state, so the backend can
 * tell the user's frontend to ask (a backend can never open the dialog itself).
 */
@RestController
public class ProfileController {

	/** The capabilities that make up the profile, in the order {@code granted} lists them. */
	static final List<String> PROFILE_CAPS = List.of("user.basic", "user.full_name", "user.contact", "user.address");
	private static final Pattern ALIAS = Pattern.compile("^u_p_[A-Za-z0-9_]{1,60}$");
	private static final String SYNTHETIC_PREFIX = "u_p_test_";

	private final UserDirectoryClient directory;
	private final JdbcClient jdbc;

	public ProfileController(UserDirectoryClient directory, JdbcClient jdbc) {
		this.directory = directory;
		this.jdbc = jdbc;
	}

	@GetMapping("/v1/users/{alias}/profile")
	public Map<String, Object> profile(@RequestAttribute(PartnerContext.ATTR) PartnerContext ctx, @PathVariable String alias,
			@RequestParam(required = false) String fields) {
		if (!ALIAS.matcher(alias).matches()) {
			throw ApiException.validation("alias", "the user's id for your app (starts with u_p_)");
		}
		List<String> needed = new ArrayList<>();
		if (fields != null && !fields.isBlank()) {
			for (String f : fields.split(",")) {
				String key = f.trim();
				if (!PROFILE_CAPS.contains(key)) {
					throw ApiException.validation("fields", "comma-separated capability keys among " + String.join(", ", PROFILE_CAPS));
				}
				if (!needed.contains(key)) {
					needed.add(key);
				}
			}
		}
		// gate 1: what staff granted this app. user.basic is the connection itself, so without it there is no profile at all.
		if (!ctx.can("user.basic")) {
			throw new ApiException(403, "capability_not_granted", Map.of("capability", "user.basic"));
		}
		for (String f : needed) {
			if (!ctx.can(f)) {
				throw new ApiException(403, "capability_not_granted", Map.of("capability", f));
			}
		}
		List<String> held = PROFILE_CAPS.stream().filter(ctx::can).toList();

		UserDirectoryClient.ConsentedProfile p = load(ctx, alias, held);
		// gate 2: the user's own answers
		String basic = p.states().getOrDefault("user.basic", "NOT_ASKED");
		List<String> granted = held.stream().filter(c -> "GRANTED".equals(p.states().get(c))).toList();
		if (!"GRANTED".equals(basic)) {
			log(ctx, alias, "CONSENT_REQUIRED", granted);
			throw consentRequired("user.basic", basic);
		}
		for (String f : needed) {
			String state = p.states().getOrDefault(f, "NOT_ASKED");
			if (!"GRANTED".equals(state)) {
				log(ctx, alias, "CONSENT_REQUIRED", granted);
				throw consentRequired(f, state);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("alias", alias);
		out.put("granted", granted);
		// only the fields of granted capabilities, each only when the user has a value; never null
		if (granted.contains("user.full_name")) {
			put(out, "fullName", p.fields().get("fullName"));
		}
		if (granted.contains("user.contact")) {
			put(out, "phone", p.fields().get("phone"));
			put(out, "email", p.fields().get("email"));
		}
		if (granted.contains("user.address")) {
			put(out, "address", p.fields().get("address"));
		}
		log(ctx, alias, "OK", granted);
		return out;
	}

	private UserDirectoryClient.ConsentedProfile load(PartnerContext ctx, String alias, List<String> held) {
		if (alias.startsWith(SYNTHETIC_PREFIX)) {
			if (!ctx.isDev()) {
				throw new ApiException(422, "synthetic_user_not_allowed");
			}
			if (alias.equals("u_p_test_unreachable")) {
				throw new ApiException(422, "user_unreachable");
			}
			String other = switch (alias) {
				case "u_p_test_noconsent" -> "NOT_ASKED";
				case "u_p_test_denied" -> "DENIED";
				case "u_p_test_blocked" -> "BLOCKED";
				default -> "GRANTED";
			};
			Map<String, String> states = new LinkedHashMap<>();
			Map<String, Object> data = new LinkedHashMap<>();
			for (String c : held) {
				String state = c.equals("user.basic") ? "GRANTED" : other;
				states.put(c, state);
			}
			data.put("fullName", "Test User");
			data.put("phone", "+251900000000");
			data.put("email", "sandbox@example.test");
			data.put("address", Map.of("countryCode", "ET", "region", "Addis Ababa", "city", "Addis Ababa"));
			// the same rule as for real users: values only for GRANTED capabilities
			if (!"GRANTED".equals(other)) {
				data.clear();
			}
			return new UserDirectoryClient.ConsentedProfile(true, states, data);
		}
		try {
			Optional<UUID> userId = directory.resolve(ctx.appId(), alias);
			if (userId.isEmpty()) {
				throw new ApiException(422, "user_unreachable");   // unknown, deleted, suspended or of another app: all look the same
			}
			UserDirectoryClient.ConsentedProfile p = directory.profile(userId.get(), ctx.appId(), held).orElse(null);
			if (p == null || !p.reachable()) {
				throw new ApiException(422, "user_unreachable");
			}
			return p;
		}
		catch (UpstreamUnavailableException e) {
			throw new ApiException(503, "service_unavailable");
		}
	}

	private static ApiException consentRequired(String capability, String state) {
		return new ApiException(403, "consent_required", Map.of("capability", capability, "state", state));
	}

	private static void put(Map<String, Object> out, String key, Object value) {
		if (value != null && !(value instanceof String s && s.isBlank())) {
			out.put(key, value);
		}
	}

	/** One row per read, without the values: proof of what was released to whom. */
	private void log(PartnerContext ctx, String alias, String outcome, List<String> granted) {
		jdbc.sql("INSERT INTO profile_access (app_id, environment, alias, outcome, granted) VALUES (:a, :e, :al, :o, CAST(:g AS text[]))")
			.param("a", ctx.appId()).param("e", ctx.environment()).param("al", alias).param("o", outcome)
			.param("g", "{" + String.join(",", granted) + "}").update();
	}

}
