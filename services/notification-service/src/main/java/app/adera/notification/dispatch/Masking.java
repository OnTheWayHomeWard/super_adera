package app.adera.notification.dispatch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Helpers so full phone numbers and email addresses never reach logs or the send log. */
public final class Masking {

	private Masking() {
	}

	/** +251911234567 -> +2519******67 */
	public static String phone(String phone) {
		if (phone == null || phone.length() < 7) {
			return "***";
		}
		return phone.substring(0, 5) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 2);
	}

	/** alice@example.com -> a***@example.com */
	public static String email(String email) {
		if (email == null) {
			return "***";
		}
		int at = email.indexOf('@');
		if (at < 1) {
			return "***";
		}
		return email.charAt(0) + "***" + email.substring(at);
	}

	/** Stable lookup key for rate limiting; the original value cannot be read back from it. */
	public static String hash(String recipient) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(normalise(recipient).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String normalise(String recipient) {
		return recipient == null ? "" : recipient.trim().toLowerCase(Locale.ROOT);
	}

}
