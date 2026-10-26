package app.adera.user.alias;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import app.adera.user.profile.ProfileService;
import app.adera.user.profile.ValidationFailedException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A user's per-app choices: mute in-app messages, SMS or email from a mini app (the "3-dot menu" in the app window). */
@Service
public class PreferenceService {

	public record Preference(boolean muteInApp, boolean muteSms, boolean muteEmail) {

		static final Preference NONE = new Preference(false, false, false);

	}

	private final JdbcClient jdbc;
	private final ProfileService profiles;
	private final Clock clock;

	public PreferenceService(JdbcClient jdbc, ProfileService profiles, Clock clock) {
		this.jdbc = jdbc;
		this.profiles = profiles;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Preference get(UUID userId, UUID appId) {
		return jdbc.sql("SELECT mute_in_app, mute_sms, mute_email FROM app_notification_preference WHERE user_id = :u AND app_id = :a")
			.param("u", userId).param("a", appId)
			.query((rs, n) -> new Preference(rs.getBoolean("mute_in_app"), rs.getBoolean("mute_sms"), rs.getBoolean("mute_email")))
			.optional().orElse(Preference.NONE);
	}

	/** Partial update: send only the switches you want to change. */
	@Transactional
	public Preference update(UUID userId, UUID appId, Map<String, Object> body) {
		for (Map.Entry<String, Object> e : body.entrySet()) {
			if (!java.util.Set.of("muteInApp", "muteSms", "muteEmail").contains(e.getKey())) {
				throw new ValidationFailedException(e.getKey(), "unknown field");
			}
			if (!(e.getValue() instanceof Boolean)) {
				throw new ValidationFailedException(e.getKey(), "must be true or false");
			}
		}
		profiles.getOrCreate(userId);
		Preference current = get(userId, appId);
		Preference next = new Preference(
				body.containsKey("muteInApp") ? (Boolean) body.get("muteInApp") : current.muteInApp(),
				body.containsKey("muteSms") ? (Boolean) body.get("muteSms") : current.muteSms(),
				body.containsKey("muteEmail") ? (Boolean) body.get("muteEmail") : current.muteEmail());
		jdbc.sql("""
				INSERT INTO app_notification_preference (user_id, app_id, mute_in_app, mute_sms, mute_email, updated_at)
				VALUES (:u, :a, :i, :s, :e, :now)
				ON CONFLICT (user_id, app_id) DO UPDATE SET mute_in_app = :i, mute_sms = :s, mute_email = :e, updated_at = :now""")
			.param("u", userId).param("a", appId).param("i", next.muteInApp()).param("s", next.muteSms()).param("e", next.muteEmail())
			.param("now", java.sql.Timestamp.from(clock.instant())).update();
		return next;
	}

}
