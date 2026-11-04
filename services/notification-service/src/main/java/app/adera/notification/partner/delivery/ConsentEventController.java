package app.adera.notification.partner.delivery;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.UpstreamUnavailableException;
import app.adera.notification.partner.auth.RegistryClient;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * user-service tells us that a user changed a permission or disconnected ({@code POST /internal/consent-events}, internal secret).
 * If the app has a callback URL for this deployment's environment and may run here, the event is queued in the same signed,
 * 24-hour-retry callback queue the status reports use, and the callback worker delivers it. The payload has the pseudonymous id
 * only, never a real user id. Apps without a URL get nothing (the answer says so).
 */
@RestController
public class ConsentEventController {

	private static final Set<String> EVENTS = Set.of("user.consent_changed", "user.disconnected");
	private static final Set<String> STATES = Set.of("NOT_ASKED", "GRANTED", "DENIED", "BLOCKED");
	private static final Pattern ALIAS = Pattern.compile("^u_p_[A-Za-z0-9_]{1,60}$");
	private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9_.]{1,38}$");

	private final RegistryClient registry;
	private final JdbcClient jdbc;
	private final JsonMapper mapper;

	public ConsentEventController(RegistryClient registry, JdbcClient jdbc, JsonMapper mapper) {
		this.registry = registry;
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@PostMapping("/internal/consent-events")
	public ResponseEntity<Map<String, Object>> accept(@RequestBody Map<String, Object> body) {
		Map<String, String> errors = new LinkedHashMap<>();
		String event = body.get("event") instanceof String s && EVENTS.contains(s) ? s : null;
		if (event == null) {
			errors.put("event", "must be user.consent_changed or user.disconnected");
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
		String at = null;
		try {
			at = Instant.parse(String.valueOf(body.get("at"))).toString();
		}
		catch (DateTimeParseException e) {
			errors.put("at", "required: an ISO-8601 instant");
		}
		String capability = null;
		String state = null;
		List<String> capabilities = null;
		if ("user.consent_changed".equals(event)) {
			capability = body.get("capability") instanceof String s && CAPABILITY.matcher(s).matches() ? s : null;
			if (capability == null) {
				errors.put("capability", "required: a capability key such as location.fine");
			}
			state = body.get("state") instanceof String s && STATES.contains(s) ? s : null;
			if (state == null) {
				errors.put("state", "must be NOT_ASKED, GRANTED, DENIED or BLOCKED");
			}
		}
		else if ("user.disconnected".equals(event)) {
			if (body.get("capabilities") instanceof List<?> list && list.size() <= 20 && list.stream().allMatch(o -> o instanceof String s && CAPABILITY.matcher(s).matches())) {
				capabilities = list.stream().map(String::valueOf).toList();
			}
			else {
				errors.put("capabilities", "required: a list of at most 20 capability keys");
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		Optional<RegistryClient.AppContext> ctx;
		try {
			ctx = registry.context(appId);
		}
		catch (UpstreamUnavailableException e) {
			throw new ApiException(503, "service_unavailable");   // user-service retries: the event is not lost
		}
		if (ctx.isEmpty()) {
			return skipped("unknown_app");
		}
		if (!ctx.get().usable()) {
			return skipped("app_not_active");
		}
		String url = ctx.get().callbackUrl();
		if (url == null || url.isBlank()) {
			return skipped("no_callback_url");
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("event", event);
		payload.put("alias", alias);
		if (capability != null) {
			payload.put("capability", capability);
			payload.put("state", state);
		}
		if (capabilities != null) {
			payload.put("capabilities", capabilities);
		}
		payload.put("at", at);
		payload.put("environment", ctx.get().environment());
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.sql("""
				INSERT INTO callback (id, notification_id, url, payload, status, attempts, next_attempt_at, created_at, updated_at, event, app_id)
				VALUES (:id, NULL, :url, CAST(:payload AS jsonb), 'PENDING', 0, :now, :now, :now, :event, :app)""")
			.param("id", UUID.randomUUID()).param("url", url).param("payload", mapper.writeValueAsString(payload)).param("now", now)
			.param("event", event).param("app", appId).update();
		return ResponseEntity.accepted().body(Map.of("queued", true));
	}

	private static ResponseEntity<Map<String, Object>> skipped(String reason) {
		return ResponseEntity.ok(Map.of("queued", false, "reason", reason));
	}

}
