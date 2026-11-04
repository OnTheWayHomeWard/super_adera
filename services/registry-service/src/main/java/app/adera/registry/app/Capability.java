package app.adera.registry.app;

import java.util.Arrays;
import java.util.Optional;

/**
 * Everything we can allow a mini app to ask for. Granting is the admin gate; the per-user consent gate lives in
 * user-service. {@code purposeRequired} capabilities need a human-readable purpose sentence (shown to the user verbatim
 * in the permission dialog); the others fall back to {@code defaultPurpose}. {@code label} is the dialog's headline.
 */
public enum Capability {

	USER_BASIC("user.basic", "Pseudonymous id and display name", "Your Adera id", "Recognise you in the app without learning who you are.", false, null),
	USER_FULL_NAME("user.full_name", "The user's first and last name", "Your name", "Greet you by name.", true, null),
	USER_CONTACT("user.contact", "The user's phone number and email", "Your phone number and email", "Contact you about your use of the app.", true, null),
	USER_ADDRESS("user.address", "One saved address the user picks", "Your saved address", "Fill in your address for you.", true, null),
	LOCATION_COARSE("location.coarse", "City-level location", "Your approximate location", "Show results near you.", true, null),
	LOCATION_FINE("location.fine", "Precise location, foreground only", "Your precise location", "Use your exact position while the app is open.", true, null),
	PAY_REQUEST("pay.request", "Ask the user to pay (currencies and limits in payment settings)", "Payments", "Ask you to confirm a payment.", true, null),
	NOTIFY_INAPP("notify.inapp", "Send in-app notifications (inbox + push) to users", "Notifications in Adera", "Send you messages inside Adera.", false, "IN_APP"),
	NOTIFY_SMS("notify.sms", "Send SMS to users (costs money; capped)", "Text messages (SMS)", "Send you text messages.", false, "SMS"),
	NOTIFY_EMAIL("notify.email", "Send email to users", "Email", "Send you emails.", false, "EMAIL");

	private final String key;
	private final String description;
	private final String label;
	private final String defaultPurpose;
	private final boolean purposeRequired;
	private final String limitChannel;

	Capability(String key, String description, String label, String defaultPurpose, boolean purposeRequired, String limitChannel) {
		this.key = key;
		this.description = description;
		this.label = label;
		this.defaultPurpose = defaultPurpose;
		this.purposeRequired = purposeRequired;
		this.limitChannel = limitChannel;
	}

	public String key() { return key; }

	public String description() { return description; }

	public String label() { return label; }

	/** What the dialog says when staff approved no purpose sentence. */
	public String defaultPurpose() { return defaultPurpose; }

	public boolean purposeRequired() { return purposeRequired; }

	/** The notification channel this capability unlocks (and therefore needs a limit row), or null. */
	public String limitChannel() { return limitChannel; }

	public static Optional<Capability> byKey(String key) {
		return Arrays.stream(values()).filter(c -> c.key.equals(key)).findFirst();
	}

}
