package app.adera.registry.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.app.AppConfigRepository;
import app.adera.registry.app.MiniApp;
import app.adera.registry.app.MiniAppRepository;
import app.adera.registry.common.Environment;
import app.adera.registry.common.Errors;
import app.adera.registry.key.PartnerKey;
import app.adera.registry.key.PartnerKeyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * For other services (notification-service checks every partner request against this). Protected by the internal
 * secret; never routed by the gateway.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

	/** {@code usable}: not revoked or expired. {@code activeHere}: the key's environment is the one this deployment runs. */
	public record KeyInfo(String keyId, UUID appId, String environment, String algorithm, String publicKeyPem, boolean usable,
			boolean activeHere, String deploymentEnvironment) {
	}

	/** Everything the delivery side needs to decide about one request from one app in one environment. */
	public record AppContext(UUID appId, String slug, String status, String environment, boolean usable, String denyReason,
			List<String> grantedCapabilities, List<AppConfigRepository.LimitRow> notificationLimits, List<String> allowedCidrs) {
	}

	private static final java.util.regex.Pattern ACTION = java.util.regex.Pattern.compile("^[a-z][a-z0-9_.-]{1,60}$");

	private final app.adera.registry.audit.AuditService audit;
	private final PartnerKeyRepository keys;
	private final MiniAppRepository apps;
	private final AppConfigRepository config;
	private final Clock clock;
	private final Environment deployment;

	public InternalController(app.adera.registry.audit.AuditService audit, PartnerKeyRepository keys, MiniAppRepository apps, AppConfigRepository config, Clock clock,
			Environment deployment) {
		this.deployment = deployment;
		this.audit = audit;
		this.keys = keys;
		this.apps = apps;
		this.config = config;
		this.clock = clock;
	}

	@GetMapping("/partner-keys/{keyId}")
	@Transactional(readOnly = true)
	public KeyInfo key(@PathVariable String keyId) {
		PartnerKey key = keys.findById(keyId).orElseThrow(() -> new Errors.NotFound("key"));
		return new KeyInfo(key.getKeyId(), key.getAppId(), key.getEnvironment(), key.getAlgorithm(), key.getPublicKeyPem(), key.isActive(Instant.now(clock)),
				key.getEnvironment().equals(deployment.name()), deployment.name());
	}

	/**
	 * Always answers for THIS deployment's environment (ADERA_ENV); asking for the other one is refused with 403
	 * wrong_environment. DEV works for any app that is not killed, so partners can integrate before going live;
	 * PROD works only once the app status is LIVE.
	 */
	@GetMapping("/apps/{appId}/context")
	@Transactional(readOnly = true)
	public AppContext context(@PathVariable UUID appId, @RequestParam(required = false) String environment) {
		Environment env = deployment;
		if (environment != null && Environment.parse(environment) != env) {
			throw new Errors.Forbidden("wrong_environment");
		}
		MiniApp app = apps.findById(appId).orElseThrow(() -> new Errors.NotFound("mini app"));
		boolean usable = env == Environment.DEV ? app.getStatus() != MiniApp.Status.KILLED : app.getStatus() == MiniApp.Status.LIVE;
		String deny = usable ? null : "app_" + app.getStatus().name().toLowerCase();
		List<String> granted = config.capabilities(appId).stream().filter(c -> c.state().equals("GRANTED")).map(AppConfigRepository.CapabilityRow::capability).toList();
		List<String> cidrs = config.environments(appId).stream().filter(e -> e.environment().equals(env.name())).findFirst()
			.map(AppConfigRepository.EnvironmentRow::allowedCidrs).orElse(List.of());
		return new AppContext(appId, app.getSlug(), app.getStatus().name(), env.name(), usable, deny, granted, config.limits(appId), cidrs);
	}

	/** Does this app exist, and in which status? (user-service checks ids it is given: 404 for an unknown app.) */
	@GetMapping("/apps/{appId}")
	@Transactional(readOnly = true)
	public Map<String, Object> app(@PathVariable UUID appId) {
		MiniApp app = apps.findById(appId).orElseThrow(() -> new Errors.NotFound("mini app"));
		return Map.of("appId", app.getId(), "slug", app.getSlug(), "status", app.getStatus().name());
	}

	/** How other services (user-service) write to the central audit log. Not routed by the gateway. */
	@PostMapping("/audit")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> recordAudit(@RequestBody Map<String, Object> body) {
		Map<String, String> errors = new java.util.LinkedHashMap<>();
		Object action = body.get("action");
		if (!(action instanceof String a) || !ACTION.matcher(a).matches()) {
			errors.put("action", "required: 2-61 chars, lowercase, e.g. user.status");
		}
		UUID actorId = null;
		if (body.get("actorId") != null) {
			try {
				actorId = UUID.fromString(String.valueOf(body.get("actorId")));
			}
			catch (IllegalArgumentException e) {
				errors.put("actorId", "must be a uuid");
			}
		}
		Object meta = body.get("meta");
		if (meta != null && !(meta instanceof Map<?, ?>)) {
			errors.put("meta", "must be an object");
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> metaMap = meta == null ? Map.of() : (Map<String, Object>) meta;
		UUID id = audit.recordExternal(actorId, str(body.get("actorRole"), 16), (String) action, str(body.get("targetType"), 30),
				str(body.get("targetId"), 64), metaMap, str(body.get("ip"), 64));
		return Map.of("id", id);
	}

	private static String str(Object v, int max) {
		if (v == null) {
			return null;
		}
		String s = String.valueOf(v);
		return s.length() > max ? s.substring(0, max) : s;
	}

}
