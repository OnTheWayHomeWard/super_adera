package app.adera.notification.partner.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.PartnerProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs the launch token of a mini app (docs section 14, section 4) with the SAME Adera key that signs responses and callbacks, so a
 * partner verifies it with the public key at /.well-known/jwks.json. The key lives only here; user-service decides who may get a
 * token and what the scopes are, then asks this endpoint to sign. Internal only (X-Internal-Secret), never routed by the gateway.
 *
 * <p>Token: RS256 JWT, header {@code {"alg":"RS256","typ":"JWT","kid":<key id>}}, claims {@code iss} "adera", {@code aud} the app id,
 * {@code sub} the user's pseudonymous id for that app (never the real user id), {@code env} "dev" or "prod", {@code scopes} the
 * capabilities the user allowed, {@code iat}, {@code exp} five minutes ahead, {@code jti}.
 */
@RestController
public class LaunchTokenController {

	static final int LIFETIME_SECONDS = 300;
	private static final Pattern ALIAS = Pattern.compile("^u_p_[A-Za-z0-9_]{1,60}$");
	private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9_.]{1,38}$");

	private final SigningKeyHolder keys;
	private final PartnerProperties props;
	private final JsonMapper mapper;

	public LaunchTokenController(SigningKeyHolder keys, PartnerProperties props, JsonMapper mapper) {
		this.keys = keys;
		this.props = props;
		this.mapper = mapper;
	}

	@PostMapping("/internal/launch-tokens")
	public Map<String, Object> sign(@RequestBody Map<String, Object> body) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (String k : body.keySet()) {
			if (!Set.of("appId", "alias", "scopes").contains(k)) {
				errors.put(k, "unknown field");
			}
		}
		UUID appId = null;
		try {
			appId = UUID.fromString(String.valueOf(body.get("appId")));
		}
		catch (IllegalArgumentException e) {
			errors.put("appId", "required: the app's id (uuid)");
		}
		String alias = body.get("alias") instanceof String s && ALIAS.matcher(s).matches() ? s : null;
		if (alias == null) {
			errors.put("alias", "required: the user's id for the app (starts with u_p_)");
		}
		List<String> scopes = new ArrayList<>();
		if (body.get("scopes") instanceof List<?> list && list.size() <= 20) {
			for (Object o : list) {
				if (o instanceof String s && CAPABILITY.matcher(s).matches()) {
					scopes.add(s);
				}
				else {
					errors.put("scopes", "must be capability keys such as location.fine");
					break;
				}
			}
		}
		else {
			errors.put("scopes", "required: a list of at most 20 capability keys");
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		Instant now = Instant.now();
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("alg", "RS256");
		header.put("typ", "JWT");
		header.put("kid", keys.keyId());
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", "adera");
		claims.put("aud", appId.toString());
		claims.put("sub", alias);
		claims.put("env", props.environment().toLowerCase(java.util.Locale.ROOT));
		claims.put("scopes", scopes);
		claims.put("iat", now.getEpochSecond());
		claims.put("exp", now.plusSeconds(LIFETIME_SECONDS).getEpochSecond());
		claims.put("jti", UUID.randomUUID().toString());
		String signingInput = b64(mapper.writeValueAsBytes(header)) + "." + b64(mapper.writeValueAsBytes(claims));
		// SigningKeyHolder.sign is RSASSA-PKCS1-v1_5 SHA-256 (RS256) in standard base64; a JWT wants URL-safe base64 without padding
		String signature = keys.sign(signingInput).replace('+', '-').replace('/', '_').replace("=", "");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("token", signingInput + "." + signature);
		out.put("expiresInSeconds", LIFETIME_SECONDS);
		out.put("keyId", keys.keyId());
		return out;
	}

	private static String b64(byte[] bytes) {
		return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
