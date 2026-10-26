package app.adera.registry.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.adera.registry.audit.Actor;
import app.adera.registry.audit.AuditService;
import app.adera.registry.common.Cidrs;
import app.adera.registry.common.Environment;
import app.adera.registry.common.Errors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppConfigService {

	static final Set<String> CHANNELS = Set.of("SMS", "EMAIL", "IN_APP");
	static final Set<String> CURRENCIES = Set.of("ETB", "USD");
	static final Set<String> STATES = Set.of("GRANTED", "DENIED", "SUSPENDED");

	private final AppConfigRepository config;
	private final MiniAppRepository apps;
	private final AuditService audit;
	private final Clock clock;

	public AppConfigService(AppConfigRepository config, MiniAppRepository apps, AuditService audit, Clock clock) {
		this.config = config;
		this.apps = apps;
		this.audit = audit;
		this.clock = clock;
	}

	/** New apps start with both environments (empty allowed-address lists: nothing can call until an admin adds some). */
	@Transactional
	public void initialiseEnvironments(UUID appId) {
		Instant now = clock.instant();
		for (Environment env : Environment.values()) {
			config.upsertEnvironment(appId, env.name(), List.of(), now);
		}
	}

	@Transactional
	public void setCapability(UUID appId, String key, Map<String, Object> body, Actor actor) {
		requireApp(appId);
		Capability capability = Capability.byKey(key).orElseThrow(() -> new Errors.ValidationFailed("capability", "unknown capability '" + key + "'"));
		Map<String, String> errors = new LinkedHashMap<>();
		for (String k : body.keySet()) {
			if (!k.equals("state") && !k.equals("reason")) {
				errors.put(k, "unknown field");
			}
		}
		String state = String.valueOf(body.get("state")).toUpperCase(Locale.ROOT);
		if (!STATES.contains(state)) {
			errors.put("state", "must be GRANTED, DENIED or SUSPENDED");
		}
		Map<String, String> reason = new LinkedHashMap<>();
		if (body.get("reason") instanceof Map<?, ?> r) {
			r.forEach((lang, text) -> {
				if (!AppPatch.LANGS.contains(String.valueOf(lang))) {
					errors.put("reason", "unsupported language '" + lang + "' (use en, am, om)");
				}
				else if (text != null && !String.valueOf(text).isBlank()) {
					reason.put(String.valueOf(lang), String.valueOf(text).strip());
				}
			});
		}
		else if (body.get("reason") != null) {
			errors.put("reason", "must be an object like {\"en\": \"...\"}");
		}
		if (state.equals("GRANTED") && capability.reasonRequired()) {
			String en = reason.getOrDefault("en", "");
			if (en.length() < 10) {
				errors.put("reason", "required for " + key + ": a plain-language English reason (min 10 chars) shown to users, e.g. 'To show technicians near you'");
			}
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		Instant now = clock.instant();
		config.upsertCapability(appId, key, state, reason, actor == null ? null : actor.id(), now);
		if (state.equals("GRANTED") && capability.limitChannel() != null) {
			config.insertLimitIfAbsent(appId, defaultLimit(capability.limitChannel()), now);
		}
		audit.record(actor, "capability.set", "mini_app", appId.toString(), Map.of("capability", key, "state", state));
	}

	@Transactional
	public AppConfigRepository.LimitRow setLimit(UUID appId, String channelRaw, Map<String, Object> body, Actor actor) {
		requireApp(appId);
		String channel = channelRaw.toUpperCase(Locale.ROOT);
		if (!CHANNELS.contains(channel)) {
			throw new Errors.ValidationFailed("channel", "must be SMS, EMAIL or IN_APP");
		}
		Map<String, String> errors = new LinkedHashMap<>();
		for (String k : body.keySet()) {
			if (!Set.of("maxPerUserPerDay", "maxPerDay", "maxDistinctUsersPerDay", "maxTotal").contains(k)) {
				errors.put(k, "unknown field");
			}
		}
		Integer perUser = intField(body, "maxPerUserPerDay", errors);
		Integer perDay = intField(body, "maxPerDay", errors);
		Integer distinct = intField(body, "maxDistinctUsersPerDay", errors);
		Long total = longField(body, "maxTotal", errors);
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		AppConfigRepository.LimitRow row = new AppConfigRepository.LimitRow(channel, perUser, perDay, distinct, total);
		config.upsertLimit(appId, row, clock.instant());
		audit.record(actor, "limit.set", "mini_app", appId.toString(), Map.of("channel", channel, "limits", String.valueOf(row)));
		return row;
	}

	@Transactional
	public AppConfigRepository.EnvironmentRow setEnvironment(UUID appId, String envRaw, Map<String, Object> body, Actor actor) {
		requireApp(appId);
		Environment env = Environment.parse(envRaw);
		if (!(body.get("allowedCidrs") instanceof List<?> list) || list.size() > 50) {
			throw new Errors.ValidationFailed("allowedCidrs", "must be a list (max 50) of IPs or ranges, e.g. [\"203.0.113.10\", \"198.51.100.0/24\"]");
		}
		List<String> normalised = new ArrayList<>();
		for (Object item : list) {
			try {
				String cidr = Cidrs.normalise(String.valueOf(item));
				if (Cidrs.matchesEverything(cidr) && env == Environment.PROD) {
					throw new Errors.ValidationFailed("allowedCidrs", "a range that matches every address (/0) is not allowed for PROD");
				}
				if (!normalised.contains(cidr)) {
					normalised.add(cidr);
				}
			}
			catch (IllegalArgumentException e) {
				throw new Errors.ValidationFailed("allowedCidrs", "'" + item + "' is not a valid IP address or range");
			}
		}
		config.upsertEnvironment(appId, env.name(), normalised, clock.instant());
		audit.record(actor, "environment.set", "mini_app", appId.toString(), Map.of("environment", env.name(), "allowedCidrs", normalised));
		return new AppConfigRepository.EnvironmentRow(env.name(), normalised);
	}

	@Transactional
	public AppConfigRepository.PaymentRow setPayment(UUID appId, Map<String, Object> body, Actor actor) {
		requireApp(appId);
		Map<String, String> errors = new LinkedHashMap<>();
		for (String k : body.keySet()) {
			if (!Set.of("allowedCurrencies", "allowHold", "minAmountMinor", "maxAmountMinor").contains(k)) {
				errors.put(k, "unknown field");
			}
		}
		List<String> currencies = new ArrayList<>();
		if (!(body.get("allowedCurrencies") instanceof List<?> list)) {
			errors.put("allowedCurrencies", "must be a list like [\"ETB\", \"USD\"]");
		}
		else {
			for (Object c : list) {
				String code = String.valueOf(c).toUpperCase(Locale.ROOT);
				if (!CURRENCIES.contains(code)) {
					errors.put("allowedCurrencies", "supported currencies: ETB, USD");
				}
				else if (!currencies.contains(code)) {
					currencies.add(code);
				}
			}
		}
		boolean allowHold = Boolean.TRUE.equals(body.get("allowHold"));
		Long min = longField(body, "minAmountMinor", errors);
		Long max = longField(body, "maxAmountMinor", errors);
		if (min != null && max != null && min > max) {
			errors.put("minAmountMinor", "must not be greater than maxAmountMinor");
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		AppConfigRepository.PaymentRow row = new AppConfigRepository.PaymentRow(currencies, allowHold, min, max);
		config.upsertPayment(appId, row, clock.instant());
		audit.record(actor, "payment.set", "mini_app", appId.toString(), Map.of("currencies", currencies, "allowHold", allowHold));
		return row;
	}

	private void requireApp(UUID appId) {
		if (!apps.existsById(appId)) {
			throw new Errors.NotFound("mini app");
		}
	}

	/** Conservative starting points; an admin raises them per app. */
	static AppConfigRepository.LimitRow defaultLimit(String channel) {
		return switch (channel) {
			case "SMS" -> new AppConfigRepository.LimitRow("SMS", 3, 100, 100, 1000L);
			case "EMAIL" -> new AppConfigRepository.LimitRow("EMAIL", 5, 500, 500, 10_000L);
			default -> new AppConfigRepository.LimitRow("IN_APP", 20, 10_000, 10_000, null);
		};
	}

	private static Integer intField(Map<String, Object> body, String key, Map<String, String> errors) {
		Object v = body.get(key);
		if (v == null) {
			return null;
		}
		if (v instanceof Integer i && i >= 0) {
			return i;
		}
		errors.put(key, "must be a whole number >= 0, or null for unlimited");
		return null;
	}

	private static Long longField(Map<String, Object> body, String key, Map<String, String> errors) {
		Object v = body.get(key);
		if (v == null) {
			return null;
		}
		if ((v instanceof Integer || v instanceof Long) && ((Number) v).longValue() >= 0) {
			return ((Number) v).longValue();
		}
		errors.put(key, "must be a whole number >= 0, or null for none");
		return null;
	}

}
