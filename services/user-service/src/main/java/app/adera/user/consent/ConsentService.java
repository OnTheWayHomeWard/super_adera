package app.adera.user.consent;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import app.adera.user.admin.ApiException;
import app.adera.user.profile.AppUser;
import app.adera.user.profile.NotFoundException;
import app.adera.user.profile.ProfileService;
import app.adera.user.profile.ValidationFailedException;
import app.adera.user.registry.RegistryAppClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gate 2 of the bridge design: did THIS user allow THIS capability for THIS app. The state machine:
 * NOT_ASKED -> (dialog) GRANTED | DENIED; DENIED -> (dialog) GRANTED | BLOCKED; BLOCKED never shows a dialog and only the
 * menu switch changes it. A dialog may only be answered with the one-time prompt id that {@link #request} issued.
 * What staff granted the app (gate 1) comes from registry-service on every call, so pausing an app or removing a capability bites at once.
 */
@Service
public class ConsentService {

	public static final String USER_BASIC = "user.basic";
	public static final int PROMPT_SECONDS = 300;
	/** The second refusal in a row blocks. */
	public static final int BLOCK_AFTER = 2;

	private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9_.]{1,38}$");
	private static final SecureRandom RANDOM = new SecureRandom();

	public record Prompt(String promptId, int expiresInSeconds, String label, String purpose) {
	}

	/** {@code state} is NOT_ASKED, GRANTED, DENIED, BLOCKED or NOT_AVAILABLE (staff did not grant the capability to the app). */
	public record RequestResult(String capability, String state, Prompt prompt) {
	}

	public record Decision(String capability, String state, int denialCount, boolean canAskAgain) {
	}

	public record MenuResult(String capability, String state, int denialCount, Instant decidedAt) {
	}

	public record PermissionView(String capability, String label, String purpose, String state, int denialCount, boolean canAskAgain,
			Instant decidedAt) {
	}

	public record AppPermissions(UUID appId, String slug, Map<String, String> name, String status, List<PermissionView> permissions) {
	}

	/** One stored row. */
	record Row(String state, int denialCount, Instant decidedAt, String promptId, Instant promptExpiresAt) {

		static final Row NONE = new Row("NOT_ASKED", 0, null, null, null);

	}

	private final JdbcClient jdbc;
	private final RegistryAppClient registry;
	private final ProfileService profiles;
	private final app.adera.user.profile.UserRepository users;
	private final Clock clock;

	public ConsentService(JdbcClient jdbc, RegistryAppClient registry, ProfileService profiles, app.adera.user.profile.UserRepository users, Clock clock) {
		this.jdbc = jdbc;
		this.registry = registry;
		this.profiles = profiles;
		this.users = users;
		this.clock = clock;
	}

	// ---------------------------------------------------------------- reading

	/** The menu sheet: every capability staff granted the app with this user's state. Only a LIVE app. */
	@Transactional
	public List<PermissionView> list(UUID userId, String app) {
		AppUser user = activeUser(userId);
		RegistryAppClient.AppInfo info = liveApp(app);
		return views(userId, info, user.getLanguage());
	}

	/** Settings screen: apps where the user currently allows something, and what. */
	@Transactional
	public List<AppPermissions> overview(UUID userId) {
		AppUser user = activeUser(userId);
		List<UUID> appIds = jdbc.sql("SELECT DISTINCT app_id FROM app_consent WHERE user_id = :u AND state = 'GRANTED'").param("u", userId).query(UUID.class).list();
		List<AppPermissions> out = new ArrayList<>();
		for (RegistryAppClient.AppInfo info : registry.apps(appIds)) {
			if ("DRAFT".equals(info.status())) {
				continue;
			}
			List<PermissionView> allowed = views(userId, info, user.getLanguage()).stream().filter(p -> p.state().equals("GRANTED")).toList();
			if (!allowed.isEmpty()) {
				out.add(new AppPermissions(info.appId(), info.slug(), info.name(), info.status(), allowed));
			}
		}
		return out;
	}

	private List<PermissionView> views(UUID userId, RegistryAppClient.AppInfo info, String language) {
		Map<String, Row> rows = rows(userId, info.appId());
		List<PermissionView> out = new ArrayList<>();
		for (RegistryAppClient.GrantedPermission g : info.permissions()) {
			Row row = effective(g.capability(), rows.get(g.capability()));
			out.add(new PermissionView(g.capability(), g.label(), purposeText(g, language), row.state(), row.denialCount(), canAskAgain(g.capability(), row), row.decidedAt()));
		}
		return out;
	}

	// ---------------------------------------------------------------- the dialog

	/** The mini app needs a permission now. Returns the state and, only when a dialog is allowed, a one-time prompt. */
	@Transactional
	public RequestResult request(UUID userId, String app, String capability) {
		validCapability(capability);
		AppUser user = activeUser(userId);
		RegistryAppClient.AppInfo info = liveApp(app);
		RegistryAppClient.GrantedPermission granted = grant(info, capability);
		if (granted == null) {
			return new RequestResult(capability, "NOT_AVAILABLE", null);
		}
		if (USER_BASIC.equals(capability)) {
			// the pseudonymous id needs no dialog: GRANTED unless the user disconnected from this app
			Row row = effective(capability, find(userId, info.appId(), capability, false));
			return new RequestResult(capability, row.state(), null);
		}
		ensureRow(userId, info.appId(), capability);
		Row row = find(userId, info.appId(), capability, true);
		if (row.state().equals("GRANTED") || row.state().equals("BLOCKED")) {
			return new RequestResult(capability, row.state(), null);
		}
		// NOT_ASKED or DENIED: a dialog is allowed. A new ticket replaces any older one.
		Instant now = clock.instant();
		String promptId = "pr_" + HexFormat.of().formatHex(randomBytes(12));
		jdbc.sql("UPDATE app_consent SET prompt_id = :p, prompt_expires_at = :e WHERE user_id = :u AND app_id = :a AND capability = :c")
			.param("p", promptId).param("e", ts(now.plus(Duration.ofSeconds(PROMPT_SECONDS)))).param("u", userId).param("a", info.appId()).param("c", capability).update();
		return new RequestResult(capability, row.state(), new Prompt(promptId, PROMPT_SECONDS, granted.label(), purposeText(granted, user.getLanguage())));
	}

	/** The dialog's answer. Needs the prompt id from {@link #request}: without it (or expired, used, or for another app) nothing changes. */
	@Transactional
	public Decision answer(UUID userId, String app, String capability, Map<String, Object> body) {
		validCapability(capability);
		Map<String, String> errors = new LinkedHashMap<>();
		for (String k : body.keySet()) {
			if (!k.equals("promptId") && !k.equals("answer")) {
				errors.put(k, "unknown field");
			}
		}
		if (!(body.get("promptId") instanceof String promptId) || promptId.isBlank()) {
			errors.put("promptId", "is required (the one returned by the request call)");
		}
		if (!"GRANTED".equals(body.get("answer")) && !"DENIED".equals(body.get("answer"))) {
			errors.put("answer", "must be GRANTED or DENIED");
		}
		if (!errors.isEmpty()) {
			throw new ValidationFailedException(errors);
		}
		activeUser(userId);
		RegistryAppClient.AppInfo info = liveApp(app);
		if (grant(info, capability) == null) {
			throw new ApiException(403, "capability_not_granted");
		}
		if (USER_BASIC.equals(capability)) {
			throw new ApiException(409, "prompt_invalid");
		}
		Row row = find(userId, info.appId(), capability, true);
		Instant now = clock.instant();
		if (row == null || row.promptId() == null || !row.promptId().equals(body.get("promptId"))) {
			throw new ApiException(409, "prompt_invalid");
		}
		if (!row.promptExpiresAt().isAfter(now)) {
			clearPrompt(userId, info.appId(), capability);
			throw new ApiException(409, "prompt_expired");
		}
		if (row.state().equals("BLOCKED")) {
			throw new ApiException(409, "prompt_invalid");
		}
		String to;
		int count;
		if ("GRANTED".equals(body.get("answer"))) {
			to = "GRANTED";
			count = 0;
		}
		else {
			count = row.denialCount() + 1;
			to = count >= BLOCK_AFTER ? "BLOCKED" : "DENIED";
		}
		store(userId, info.appId(), capability, row.state(), to, count, "DIALOG", now);
		return new Decision(capability, to, count, to.equals("DENIED"));
	}

	// ---------------------------------------------------------------- the menu

	/** The menu switch: always works, resets the count, and is the only way out of BLOCKED. */
	@Transactional
	public MenuResult menu(UUID userId, String app, String capability, Map<String, Object> body) {
		validCapability(capability);
		Map<String, String> errors = new LinkedHashMap<>();
		for (String k : body.keySet()) {
			if (!k.equals("state")) {
				errors.put(k, "unknown field");
			}
		}
		Object state = body.get("state");
		if (!"GRANTED".equals(state) && !"DENIED".equals(state)) {
			errors.put("state", "must be GRANTED or DENIED");
		}
		if (!errors.isEmpty()) {
			throw new ValidationFailedException(errors);
		}
		activeUser(userId);
		RegistryAppClient.AppInfo info = liveApp(app);
		if (grant(info, capability) == null) {
			throw new ApiException(403, "capability_not_granted");
		}
		if (USER_BASIC.equals(capability) && "DENIED".equals(state)) {
			// the pseudonymous id is what makes the app work at all; leaving the app is the Disconnect action
			throw new ApiException(409, "use_disconnect");
		}
		ensureRow(userId, info.appId(), capability);
		Row row = find(userId, info.appId(), capability, true);
		Instant now = clock.instant();
		store(userId, info.appId(), capability, row.state(), (String) state, 0, "MENU", now);
		return new MenuResult(capability, (String) state, 0, now);
	}

	/** Everything this user allowed the app becomes DENIED (count 0). Works for any app that exists and is not a draft, also when paused. */
	@Transactional
	public List<Map<String, String>> disconnect(UUID userId, String app) {
		activeUser(userId);
		RegistryAppClient.AppInfo info = registry.app(app).filter(i -> !"DRAFT".equals(i.status())).orElseThrow(() -> new NotFoundException("mini app"));
		Instant now = clock.instant();
		List<Map<String, String>> changed = new ArrayList<>();
		// the pseudonymous id: reachable only while connected
		if (info.permissions().stream().anyMatch(p -> p.capability().equals(USER_BASIC))) {
			ensureRow(userId, info.appId(), USER_BASIC);
		}
		List<Map.Entry<String, Row>> rows = new ArrayList<>(rows(userId, info.appId()).entrySet());
		for (Map.Entry<String, Row> e : rows) {
			Row row = find(userId, info.appId(), e.getKey(), true);
			boolean basicConnected = e.getKey().equals(USER_BASIC) && row.state().equals("NOT_ASKED");
			if (row.state().equals("GRANTED") || row.state().equals("BLOCKED") || basicConnected || (row.state().equals("DENIED") && row.denialCount() != 0)) {
				String from = basicConnected ? "GRANTED" : row.state();
				store(userId, info.appId(), e.getKey(), from, "DENIED", 0, "DISCONNECT", now);
				changed.add(Map.of("capability", e.getKey(), "from", from, "to", "DENIED"));
			}
			else if (row.promptId() != null) {
				clearPrompt(userId, info.appId(), e.getKey());
			}
		}
		return changed;
	}

	// ---------------------------------------------------------------- launch and the partner profile

	/**
	 * Opening the app: the pseudonymous id is connected again if the user had disconnected (user.basic needs no dialog), then the
	 * states of everything the app holds are returned. No dialog, no other state changes.
	 */
	@Transactional
	public List<PermissionView> openApp(UUID userId, RegistryAppClient.AppInfo info) {
		AppUser user = activeUser(userId);
		if (grant(info, USER_BASIC) != null) {
			Row basic = find(userId, info.appId(), USER_BASIC, false);
			if (basic.state().equals("DENIED")) {
				setSwitch(userId, info.appId(), USER_BASIC, true, "LAUNCH");
			}
		}
		return views(userId, info, user.getLanguage());
	}

	/** The user's state per requested capability, and values ONLY for the GRANTED ones: what a partner's profile read may release. */
	@Transactional(readOnly = true)
	public ProfileRelease profileRelease(UUID userId, UUID appId, List<String> capabilities) {
		AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("user"));
		boolean reachable = user.getStatus() == AppUser.Status.ACTIVE && user.getDeletedAt() == null;
		Map<String, String> states = new LinkedHashMap<>();
		Map<String, Object> fields = new LinkedHashMap<>();
		if (!reachable) {
			return new ProfileRelease(false, states, fields);
		}
		Map<String, Row> rows = rows(userId, appId);
		for (String capability : capabilities) {
			states.put(capability, effective(capability, rows.get(capability)).state());
		}
		if ("GRANTED".equals(states.get("user.full_name"))) {
			String name = ((user.getFirstName() == null ? "" : user.getFirstName().strip()) + " " + (user.getLastName() == null ? "" : user.getLastName().strip())).strip();
			if (!name.isEmpty()) {
				fields.put("fullName", name);
			}
		}
		if ("GRANTED".equals(states.get("user.contact"))) {
			if (user.getPhoneE164() != null && !user.getPhoneE164().isBlank()) {
				fields.put("phone", user.getPhoneE164());
			}
			if (user.getEmail() != null && !user.getEmail().isBlank()) {
				fields.put("email", user.getEmail());
			}
		}
		if ("GRANTED".equals(states.get("user.address"))) {
			// no street address exists in the profile: the saved place is country, region and city (never the coordinates)
			Map<String, Object> address = new LinkedHashMap<>();
			if (user.getCountryCode() != null && !user.getCountryCode().isBlank()) {
				address.put("countryCode", user.getCountryCode());
			}
			if (user.getRegion() != null && !user.getRegion().isBlank()) {
				address.put("region", user.getRegion());
			}
			if (user.getCity() != null && !user.getCity().isBlank()) {
				address.put("city", user.getCity());
			}
			if (!address.isEmpty()) {
				fields.put("address", address);
			}
		}
		return new ProfileRelease(true, states, fields);
	}

	public record ProfileRelease(boolean reachable, Map<String, String> states, Map<String, Object> fields) {
	}

	// ---------------------------------------------------------------- notification consent (what the old mute switches were)

	public static final List<String> NOTIFY = List.of("notify.inapp", "notify.sms", "notify.email");

	/** The user's state per notify.* capability for one app (NOT_ASKED when never answered). What the sender must enforce. */
	@Transactional(readOnly = true)
	public Map<String, String> notifyStates(UUID userId, UUID appId) {
		Map<String, Row> rows = rows(userId, appId);
		Map<String, String> out = new LinkedHashMap<>();
		for (String capability : NOTIFY) {
			out.put(capability, rows.getOrDefault(capability, Row.NONE).state());
		}
		return out;
	}

	/**
	 * The old mute switches: switching a channel on or off is the menu switch of its notify capability (on = GRANTED, off = DENIED,
	 * count 0). It works for any LIVE app, also for a channel staff did not grant it (the answer stays inert until they do).
	 */
	@Transactional
	public void setSwitch(UUID userId, UUID appId, String capability, boolean on) {
		setSwitch(userId, appId, capability, on, "MENU");
	}

	@Transactional
	public void setSwitch(UUID userId, UUID appId, String capability, boolean on, String source) {
		profiles.getOrCreate(userId);
		ensureRow(userId, appId, capability);
		Row row = find(userId, appId, capability, true);
		String to = on ? "GRANTED" : "DENIED";
		if (row.state().equals(to) && row.denialCount() == 0 && row.promptId() == null) {
			return;
		}
		store(userId, appId, capability, row.state(), to, 0, source, clock.instant());
	}

	// ---------------------------------------------------------------- helpers

	private RegistryAppClient.AppInfo liveApp(String app) {
		RegistryAppClient.AppInfo info = registry.app(app).orElseThrow(() -> new NotFoundException("mini app"));
		if ("DRAFT".equals(info.status())) {
			throw new NotFoundException("mini app");
		}
		if (!"LIVE".equals(info.status())) {
			throw new ApiException(403, "app_not_live");
		}
		return info;
	}

	private AppUser activeUser(UUID userId) {
		AppUser user = profiles.getOrCreate(userId);
		if (user.getStatus() == AppUser.Status.SUSPENDED) {
			throw new ApiException(403, "account_suspended");
		}
		if (user.getStatus() != AppUser.Status.ACTIVE) {
			throw new ApiException(403, "account_deleted");
		}
		return user;
	}

	private static void validCapability(String capability) {
		if (!CAPABILITY.matcher(capability).matches()) {
			throw new ValidationFailedException("capability", "is not a capability key like location.fine");
		}
	}

	private static RegistryAppClient.GrantedPermission grant(RegistryAppClient.AppInfo info, String capability) {
		return info.permissions().stream().filter(p -> p.capability().equals(capability)).findFirst().orElse(null);
	}

	/** The staff-approved sentence in the user's language, English, any, or the catalog default. */
	private static String purposeText(RegistryAppClient.GrantedPermission g, String language) {
		Map<String, String> purpose = g.purpose() == null ? Map.of() : g.purpose();
		for (String lang : new String[] { language, "en" }) {
			String text = purpose.get(lang);
			if (text != null && !text.isBlank()) {
				return text;
			}
		}
		return g.defaultPurpose();
	}

	/** user.basic is GRANTED without a row; a DENIED row means the user disconnected. */
	private static Row effective(String capability, Row row) {
		if (row == null) {
			row = Row.NONE;
		}
		if (USER_BASIC.equals(capability) && row.state().equals("NOT_ASKED")) {
			return new Row("GRANTED", 0, null, null, null);
		}
		return row;
	}

	private static boolean canAskAgain(String capability, Row row) {
		return !USER_BASIC.equals(capability) && (row.state().equals("NOT_ASKED") || row.state().equals("DENIED"));
	}

	private Map<String, Row> rows(UUID userId, UUID appId) {
		Map<String, Row> out = new LinkedHashMap<>();
		jdbc.sql("SELECT capability, state, denial_count, decided_at, prompt_id, prompt_expires_at FROM app_consent WHERE user_id = :u AND app_id = :a")
			.param("u", userId).param("a", appId)
			.query((rs, n) -> {
				out.put(rs.getString("capability"), new Row(rs.getString("state"), rs.getInt("denial_count"), instant(rs.getTimestamp("decided_at")),
						rs.getString("prompt_id"), instant(rs.getTimestamp("prompt_expires_at"))));
				return null;
			}).list();
		return out;
	}

	private Row find(UUID userId, UUID appId, String capability, boolean forUpdate) {
		return jdbc.sql("SELECT state, denial_count, decided_at, prompt_id, prompt_expires_at FROM app_consent WHERE user_id = :u AND app_id = :a AND capability = :c"
				+ (forUpdate ? " FOR UPDATE" : ""))
			.param("u", userId).param("a", appId).param("c", capability)
			.query((rs, n) -> new Row(rs.getString("state"), rs.getInt("denial_count"), instant(rs.getTimestamp("decided_at")), rs.getString("prompt_id"),
					instant(rs.getTimestamp("prompt_expires_at"))))
			.optional().orElse(forUpdate ? null : Row.NONE);
	}

	private void ensureRow(UUID userId, UUID appId, String capability) {
		jdbc.sql("INSERT INTO app_consent (user_id, app_id, capability, state, denial_count) VALUES (:u, :a, :c, 'NOT_ASKED', 0) ON CONFLICT DO NOTHING")
			.param("u", userId).param("a", appId).param("c", capability).update();
	}

	private void clearPrompt(UUID userId, UUID appId, String capability) {
		jdbc.sql("UPDATE app_consent SET prompt_id = NULL, prompt_expires_at = NULL WHERE user_id = :u AND app_id = :a AND capability = :c")
			.param("u", userId).param("a", appId).param("c", capability).update();
	}

	/** Writes the new state, closes any open prompt, and appends the proof row. */
	private void store(UUID userId, UUID appId, String capability, String from, String to, int count, String source, Instant now) {
		jdbc.sql("""
				INSERT INTO app_consent (user_id, app_id, capability, state, denial_count, decided_at, source, prompt_id, prompt_expires_at)
				VALUES (:u, :a, :c, :s, :n, :now, :src, NULL, NULL)
				ON CONFLICT (user_id, app_id, capability) DO UPDATE
				SET state = :s, denial_count = :n, decided_at = :now, source = :src, prompt_id = NULL, prompt_expires_at = NULL""")
			.param("u", userId).param("a", appId).param("c", capability).param("s", to).param("n", count).param("now", ts(now)).param("src", source).update();
		jdbc.sql("INSERT INTO consent_event (user_id, app_id, capability, from_state, to_state, at, source) VALUES (:u, :a, :c, :f, :t, :now, :src)")
			.param("u", userId).param("a", appId).param("c", capability).param("f", from).param("t", to).param("now", ts(now)).param("src", source).update();
	}

	private static java.sql.Timestamp ts(Instant i) {
		return java.sql.Timestamp.from(i);
	}

	private static Instant instant(java.sql.Timestamp t) {
		return t == null ? null : t.toInstant();
	}

	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		RANDOM.nextBytes(b);
		return b;
	}

}
