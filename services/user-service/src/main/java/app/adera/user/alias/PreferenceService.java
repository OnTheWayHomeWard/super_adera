package app.adera.user.alias;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import app.adera.user.consent.ConsentService;
import app.adera.user.profile.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three per-app mute switches of the "3-dot menu", kept for existing clients. They are NOT separate data any more: a mute
 * is the user's notify.* consent ({@code muteSms} is {@code notify.sms}, {@code muteInApp} is {@code notify.inapp},
 * {@code muteEmail} is {@code notify.email}). A channel counts as muted when the user's answer is DENIED or BLOCKED; switching
 * mute off is switching the permission on (GRANTED). NOT_ASKED is neither: the switch shows "not muted", but the sender still
 * refuses until the user allows it, which {@code states} tells the client.
 */
@Service
public class PreferenceService {

	public record Preference(boolean muteInApp, boolean muteSms, boolean muteEmail, Map<String, String> states) {
	}

	private static final Map<String, String> CAPABILITY = Map.of("muteInApp", "notify.inapp", "muteSms", "notify.sms", "muteEmail", "notify.email");

	private final ConsentService consents;

	public PreferenceService(ConsentService consents) {
		this.consents = consents;
	}

	@Transactional(readOnly = true)
	public Preference get(UUID userId, UUID appId) {
		Map<String, String> states = consents.notifyStates(userId, appId);
		return new Preference(muted(states.get("notify.inapp")), muted(states.get("notify.sms")), muted(states.get("notify.email")), states);
	}

	/** Partial update: send only the switches you want to change. */
	@Transactional
	public Preference update(UUID userId, UUID appId, Map<String, Object> body) {
		Map<String, Boolean> wanted = new LinkedHashMap<>();
		for (Map.Entry<String, Object> e : body.entrySet()) {
			if (!CAPABILITY.containsKey(e.getKey())) {
				throw new ValidationFailedException(e.getKey(), "unknown field");
			}
			if (!(e.getValue() instanceof Boolean mute)) {
				throw new ValidationFailedException(e.getKey(), "must be true or false");
			}
			wanted.put(e.getKey(), mute);
		}
		wanted.forEach((field, mute) -> consents.setSwitch(userId, appId, CAPABILITY.get(field), !mute));
		return get(userId, appId);
	}

	private static boolean muted(String state) {
		return "DENIED".equals(state) || "BLOCKED".equals(state);
	}

}
