package app.adera.notification.partner.delivery;

import java.util.Map;

/** Sends one push notification to one device token (Firebase Cloud Messaging in production). */
public interface FcmProvider {

	enum PushResult {
		OK,
		/** Firebase says this token is dead (app uninstalled, token rotated): stop using it. */
		UNREGISTERED,
		/** Firebase is having a bad moment: try later. */
		TRANSIENT_FAILURE,
		/**
		 * Firebase refused the message itself (invalid payload, wrong sender id, bad credentials). That is a problem of our
		 * configuration or payload, not of the user's device: the token must be kept.
		 */
		REJECTED
	}

	String name();

	/** True if this reaches real phones. */
	boolean isReal();

	PushResult send(String token, String title, String body, Map<String, String> data);

}
