package app.adera.registry.app;

import java.util.Arrays;
import java.util.Optional;

/**
 * Everything we can allow a mini app to ask for. Granting is the admin gate; the per-user consent gate comes with the
 * frontend bridge. {@code reasonRequired} capabilities need a human-readable reason (shown to the user verbatim).
 */
public enum Capability {

	USER_BASIC("user.basic", "Pseudonymous id and display name", false, null),
	USER_FULL_NAME("user.full_name", "The user's first and last name", true, null),
	USER_CONTACT("user.contact", "The user's phone number and email", true, null),
	USER_ADDRESS("user.address", "One saved address the user picks", true, null),
	LOCATION_COARSE("location.coarse", "City-level location", true, null),
	LOCATION_FINE("location.fine", "Precise location, foreground only", true, null),
	PAY_REQUEST("pay.request", "Ask the user to pay (currencies and limits in payment settings)", true, null),
	NOTIFY_INAPP("notify.inapp", "Send in-app notifications (inbox + push) to users", false, "IN_APP"),
	NOTIFY_SMS("notify.sms", "Send SMS to users (costs money; capped)", false, "SMS"),
	NOTIFY_EMAIL("notify.email", "Send email to users", false, "EMAIL");

	private final String key;
	private final String description;
	private final boolean reasonRequired;
	private final String limitChannel;

	Capability(String key, String description, boolean reasonRequired, String limitChannel) {
		this.key = key;
		this.description = description;
		this.reasonRequired = reasonRequired;
		this.limitChannel = limitChannel;
	}

	public String key() { return key; }

	public String description() { return description; }

	public boolean reasonRequired() { return reasonRequired; }

	/** The notification channel this capability unlocks (and therefore needs a limit row), or null. */
	public String limitChannel() { return limitChannel; }

	public static Optional<Capability> byKey(String key) {
		return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
	}

}
