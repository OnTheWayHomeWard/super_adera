package app.adera.notification.partner.delivery;

import java.util.Map;

/** Sends one push notification to one device token (Firebase Cloud Messaging in production). */
public interface FcmProvider {

	enum PushResult {
		OK,
		/** Firebase says this token is dead (app uninstalled, token rotated): stop using it. */
		UNREGISTERED,
		/** Firebase is having a bad moment: try later. */
		TRANSIENT_FAILURE
	}

	String name();

	/** True if this reaches real phones. */
	boolean isReal();

	PushResult send(String token, String title, String body, Map<String, String> data);

}
