package app.adera.notification.partner.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.PartnerProperties;
import app.adera.notification.partner.UpstreamUnavailableException;
import app.adera.notification.partner.auth.PartnerContext;
import app.adera.notification.partner.auth.UserDirectoryClient;
import app.adera.notification.partner.template.Template;
import app.adera.notification.partner.template.TemplateRules;
import app.adera.notification.partner.template.TemplateService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes a partner's request and decides, immediately and synchronously: is it valid, is it a repeat, may this app send this,
 * can we reach this user and through which channel, is the app within its limits. If all is well it is stored as QUEUED
 * and answered 202; delivery happens in the background.
 *
 * <p>The partner never names a channel. Template kind IN_APP goes to the inbox and push. Kind DIRECT goes by SMS when we
 * have the user's phone and the app may send SMS, otherwise by email when we have an email and the app may send email.
 */
@Service
public class NotificationAcceptor {

	public record Result(int status, Map<String, Object> body) {
	}

	static final Pattern EXTERNAL_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
	static final Pattern ALIAS = Pattern.compile("^u_p_[A-Za-z0-9_]{1,60}$");
	static final Set<String> ALLOWED = Set.of("externalId", "userId", "templateId", "params", "callbackUrl", "ttlSeconds", "data");
	static final int DEFAULT_TTL = 86_400;
	static final int MAX_TTL = 604_800;

	private final TemplateService templates;
	private final UserDirectoryClient directory;
	private final NotificationStore store;
	private final NotificationRepository repository;
	private final JsonMapper mapper;
	private final Clock clock;
	private final PartnerProperties props;

	public NotificationAcceptor(TemplateService templates, UserDirectoryClient directory, NotificationStore store,
			NotificationRepository repository, JsonMapper mapper, Clock clock, PartnerProperties props) {
		this.templates = templates;
		this.directory = directory;
		this.store = store;
		this.repository = repository;
		this.mapper = mapper;
		this.clock = clock;
		this.props = props;
	}

	@SuppressWarnings("unchecked")
	public Result accept(PartnerContext ctx, Map<String, Object> body) {
		Map<String, String> errors = new LinkedHashMap<>();
		body.keySet().stream().filter(k -> !ALLOWED.contains(k)).forEach(k -> errors.put(k, "unknown field"));
		String externalId = body.get("externalId") instanceof String s && EXTERNAL_ID.matcher(s).matches() ? s : null;
		if (externalId == null) {
			errors.put("externalId", "required: your own unique id, 1-100 chars of letters, digits and . _ : -");
		}
		String alias = body.get("userId") instanceof String s && ALIAS.matcher(s).matches() ? s : null;
		if (alias == null) {
			errors.put("userId", "required: the user's id for your app (starts with u_p_)");
		}
		UUID templateId = null;
		try {
			templateId = UUID.fromString(String.valueOf(body.get("templateId")));
		}
		catch (IllegalArgumentException e) {
			errors.put("templateId", "required: the id of one of your templates (GET /v1/templates)");
		}
		Map<String, Object> params = Map.of();
		if (body.get("params") != null) {
			if (body.get("params") instanceof Map<?, ?> m) {
				params = (Map<String, Object>) m;
			}
			else {
				errors.put("params", "must be an object of the template's values");
			}
		}
		Map<String, Object> data = Map.of();
		if (body.get("data") != null) {
			if (body.get("data") instanceof Map<?, ?> m && mapper.writeValueAsBytes(m).length <= 2048) {
				data = (Map<String, Object>) m;
				for (Object key : m.keySet()) {
					String problem = PushPayload.reservedKeyProblem(String.valueOf(key));
					if (problem != null) {
						errors.put("data", problem);
						break;
					}
				}
			}
			else {
				errors.put("data", "must be a JSON object of at most 2048 bytes");
			}
		}
		String callbackUrl = null;
		if (body.get("callbackUrl") != null) {
			String u = String.valueOf(body.get("callbackUrl")).trim();
			if (u.length() > 500) {
				errors.put("callbackUrl", "must be at most 500 characters");
			}
			else {
				try {
					UrlGuard.checkSyntax(u, props.allowPrivateCallbacks());
					callbackUrl = u;
				}
				catch (IllegalArgumentException e) {
					errors.put("callbackUrl", "must be a public https URL on port 443 or 8443 without credentials (" + e.getMessage() + ")");
				}
			}
		}
		int ttl = DEFAULT_TTL;
		if (body.get("ttlSeconds") != null) {
			if (body.get("ttlSeconds") instanceof Integer t && t >= 60 && t <= MAX_TTL) {
				ttl = t;
			}
			else {
				errors.put("ttlSeconds", "must be 60 to " + MAX_TTL + " (how long we keep trying)");
			}
		}
		if (!errors.isEmpty()) {
			throw ApiException.validation(errors);
		}

		String hash = hash(templateId, alias, params, data, callbackUrl, ttl);
		Optional<Notification> existing = repository.findByAppIdAndEnvironmentAndExternalId(ctx.appId(), ctx.environment(), externalId);
		if (existing.isPresent()) {
			return replay(existing.get(), hash);
		}

		Template template = templates.getForApp(templateId, ctx.appId());
		Instant now = clock.instant();
		String unusable = template.unusableReason(now);
		if (unusable != null) {
			throw new ApiException(422, unusable);
		}
		Map<String, String> values = TemplateRules.checkValues(template.getParams(), params);

		UserDirectoryClient.DeliveryContext user = resolveUser(ctx, alias);
		if (user == null || !user.reachable()) {
			throw new ApiException(422, "user_unreachable"); // deleted, suspended or unknown look the same on purpose
		}
		String channel = chooseChannel(ctx, template, user);
		if ("IN_APP".equals(channel)) {
			templates.checkInboxFits(template, values);
		}
		boolean muted = switch (channel) {
			case "SMS" -> user.muted().muteSms();
			case "EMAIL" -> user.muted().muteEmail();
			default -> user.muted().muteInApp();
		};
		NotificationStore.NewNotification n = new NotificationStore.NewNotification(ctx.appId(), ctx.environment(), externalId, hash,
				user.userId(), alias, template.getId(), template.getVersion(), values, data, template.getKind(), channel, callbackUrl,
				now.plus(Duration.ofSeconds(ttl)));

		Optional<UUID> id = muted ? store.insertTerminal(n, Notification.SUPPRESSED, "recipient_opted_out")
				: store.insertQueued(n, ctx.limitFor(channel));
		if (id.isEmpty()) { // another identical request won the race
			return replay(repository.findByAppIdAndEnvironmentAndExternalId(ctx.appId(), ctx.environment(), externalId).orElseThrow(), hash);
		}
		Notification stored = repository.findById(id.get()).orElseThrow();
		return new Result(muted ? 200 : 202, view(stored, false));
	}

	/** Same externalId and same request: answer with the current state. Same externalId, different request: 409. */
	private Result replay(Notification existing, String hash) {
		if (!existing.getRequestHash().equals(hash)) {
			throw new ApiException(409, "external_id_reused");
		}
		return new Result(200, view(existing, true));
	}

	/** Synthetic users (DEV only): u_p_test_* let partners try every path without real people. Refused in PROD. */
	public static final String SYNTHETIC_PREFIX = "u_p_test_";

	private UserDirectoryClient.DeliveryContext resolveUser(PartnerContext ctx, String alias) {
		if (alias.startsWith(SYNTHETIC_PREFIX) && !ctx.isDev()) {
			throw new ApiException(422, "synthetic_user_not_allowed");
		}
		if (alias.startsWith(SYNTHETIC_PREFIX)) {
			UUID synthetic = UUID.nameUUIDFromBytes(("sandbox:" + ctx.appId() + ":" + alias).getBytes(StandardCharsets.UTF_8));
			boolean mutedAll = alias.equals("u_p_test_muted");
			return new UserDirectoryClient.DeliveryContext(synthetic, !alias.equals("u_p_test_unreachable"), "en", "+251900000000",
					"sandbox@example.test", List.of("sandbox-token"), new UserDirectoryClient.Muted(mutedAll, mutedAll, mutedAll));
		}
		try {
			Optional<UUID> userId = directory.resolve(ctx.appId(), alias);
			if (userId.isEmpty()) {
				return null;
			}
			return directory.context(userId.get(), ctx.appId()).orElse(null);
		}
		catch (UpstreamUnavailableException e) {
			throw new ApiException(503, "service_unavailable");
		}
	}

	static String chooseChannel(PartnerContext ctx, Template template, UserDirectoryClient.DeliveryContext user) {
		if ("IN_APP".equals(template.getKind())) {
			requireCapability(ctx, "notify.inapp");
			return "IN_APP";
		}
		boolean hasPhone = user.phone() != null && !user.phone().isBlank();
		boolean hasEmail = user.email() != null && !user.email().isBlank();
		if (hasPhone && ctx.can("notify.sms")) {
			return "SMS";
		}
		if (hasEmail && ctx.can("notify.email")) {
			return "EMAIL";
		}
		if (hasPhone) {
			requireCapability(ctx, "notify.sms");
		}
		if (hasEmail) {
			requireCapability(ctx, "notify.email");
		}
		throw new ApiException(422, "user_unreachable");
	}

	private static void requireCapability(PartnerContext ctx, String capability) {
		if (!ctx.can(capability)) {
			throw new ApiException(403, "capability_not_granted", Map.of("capability", capability));
		}
	}

	public static Map<String, Object> view(Notification n, boolean replayed) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("notificationId", n.getId());
		out.put("externalId", n.getExternalId());
		out.put("status", n.getStatus());
		out.put("channel", n.getChannel());
		if (n.getReason() != null) {
			out.put("reason", n.getReason());
		}
		out.put("environment", n.getEnvironment());
		out.put("createdAt", n.getCreatedAt());
		out.put("expiresAt", n.getExpiresAt());
		if (n.getCompletedAt() != null) {
			out.put("completedAt", n.getCompletedAt());
		}
		if (replayed) {
			out.put("replayed", true);
		}
		return out;
	}

	/** Stable fingerprint of what the partner asked for, independent of JSON key order. */
	private String hash(UUID templateId, String alias, Map<String, Object> params, Map<String, Object> data, String callbackUrl, int ttl) {
		Map<String, Object> canonical = new TreeMap<>();
		canonical.put("templateId", templateId.toString());
		canonical.put("userId", alias);
		canonical.put("params", sorted(params));
		canonical.put("data", sorted(data));
		canonical.put("callbackUrl", callbackUrl);
		canonical.put("ttl", ttl);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Object sorted(Object value) {
		if (value instanceof Map<?, ?> m) {
			Map<String, Object> out = new TreeMap<>();
			((Map<String, Object>) m).forEach((k, v) -> out.put(k, sorted(v)));
			return out;
		}
		if (value instanceof List<?> l) {
			return l.stream().map(NotificationAcceptor::sorted).toList();
		}
		return value;
	}

}
