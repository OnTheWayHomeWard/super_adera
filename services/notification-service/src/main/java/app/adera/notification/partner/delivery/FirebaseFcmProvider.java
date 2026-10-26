package app.adera.notification.partner.delivery;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import app.adera.notification.partner.PartnerProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Real push through Firebase Cloud Messaging. Credentials (the service-account JSON) come from Infisical
 * ({@code FIREBASE_SERVICE_ACCOUNT_B64}, base64 of the file) or a file ({@code FIREBASE_CREDENTIALS_PATH}).
 * Selected with {@code FCM_PROVIDER=firebase}; the service refuses to start without credentials.
 */
@Component
@ConditionalOnProperty(prefix = "adera.partner", name = "fcm-provider", havingValue = "firebase")
public class FirebaseFcmProvider implements FcmProvider {

	private static final Logger log = LoggerFactory.getLogger(FirebaseFcmProvider.class);

	private final FirebaseMessaging messaging;

	public FirebaseFcmProvider(PartnerProperties props) {
		try (InputStream in = credentials(props)) {
			FirebaseOptions options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(in)).build();
			FirebaseApp app = FirebaseApp.getApps().stream().filter(a -> a.getName().equals("adera")).findFirst()
				.orElseGet(() -> FirebaseApp.initializeApp(options, "adera"));
			this.messaging = FirebaseMessaging.getInstance(app);
		}
		catch (IllegalStateException e) {
			throw e;
		}
		catch (Exception e) {
			throw new IllegalStateException("fcm-provider=firebase but the Firebase service account could not be loaded: " + e.getMessage(), e);
		}
		log.warn("PUSH: Firebase is configured, real push notifications are enabled");
	}

	private static InputStream credentials(PartnerProperties props) throws Exception {
		if (!props.firebaseServiceAccountB64().isBlank()) {
			return new ByteArrayInputStream(Base64.getDecoder().decode(props.firebaseServiceAccountB64().trim()));
		}
		if (!props.firebaseCredentialsPath().isBlank()) {
			return new FileInputStream(props.firebaseCredentialsPath());
		}
		throw new IllegalStateException("fcm-provider=firebase needs FIREBASE_SERVICE_ACCOUNT_B64 (Infisical /notification) or FIREBASE_CREDENTIALS_PATH");
	}

	/**
	 * What a Firebase error means for the token. Only UNREGISTERED (the HTTP 404 "NOT_FOUND" of an uninstalled or rotated
	 * app instance) retires it. INVALID_ARGUMENT, SENDER_ID_MISMATCH and THIRD_PARTY_AUTH_ERROR describe our payload,
	 * project or credentials: the token is fine, so it is kept and the attempt is reported as rejected. INTERNAL,
	 * QUOTA_EXCEEDED, UNAVAILABLE and an unknown code are retried later.
	 */
	static PushResult classify(MessagingErrorCode code) {
		if (code == null) {
			return PushResult.TRANSIENT_FAILURE;
		}
		return switch (code) {
			case UNREGISTERED -> PushResult.UNREGISTERED;
			case INVALID_ARGUMENT, SENDER_ID_MISMATCH, THIRD_PARTY_AUTH_ERROR -> PushResult.REJECTED;
			case INTERNAL, QUOTA_EXCEEDED, UNAVAILABLE -> PushResult.TRANSIENT_FAILURE;
		};
	}

	@Override
	public String name() {
		return "fcm";
	}

	@Override
	public boolean isReal() {
		return true;
	}

	@Override
	public PushResult send(String token, String title, String body, Map<String, String> data) {
		try {
			messaging.send(Message.builder()
				.setToken(token)
				.setNotification(Notification.builder().setTitle(title).setBody(body).build())
				.putAllData(data)
				.setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
				.build());
			return PushResult.OK;
		}
		catch (FirebaseMessagingException e) {
			PushResult result = classify(e.getMessagingErrorCode());
			if (result == PushResult.REJECTED) {
				// configuration or payload problem: loud in the log, and the device keeps its token
				log.error("push rejected by Firebase (check the service account, sender id and payload): {} {}", e.getMessagingErrorCode(), e.getMessage());
			}
			else if (result == PushResult.TRANSIENT_FAILURE) {
				log.warn("push failed transiently: {}", e.getMessagingErrorCode());
			}
			return result;
		}
	}

}
