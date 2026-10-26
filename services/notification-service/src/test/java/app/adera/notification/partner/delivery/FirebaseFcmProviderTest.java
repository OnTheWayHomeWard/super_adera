package app.adera.notification.partner.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;

/** How a Firebase error decides the fate of a device token. The live path uses the fake provider, so this is the proof. */
class FirebaseFcmProviderTest {

	@Test
	void onlyUnregisteredRetiresTheToken() {
		assertThat(FirebaseFcmProvider.classify(MessagingErrorCode.UNREGISTERED)).isEqualTo(FcmProvider.PushResult.UNREGISTERED);
		for (MessagingErrorCode other : MessagingErrorCode.values()) {
			if (other != MessagingErrorCode.UNREGISTERED) {
				assertThat(FirebaseFcmProvider.classify(other)).as(other.name()).isNotEqualTo(FcmProvider.PushResult.UNREGISTERED);
			}
		}
	}

	@Test
	void configurationAndPayloadErrorsAreRejectionsNotDeadTokens() {
		for (MessagingErrorCode code : new MessagingErrorCode[] { MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH,
				MessagingErrorCode.THIRD_PARTY_AUTH_ERROR }) {
			assertThat(FirebaseFcmProvider.classify(code)).as(code.name()).isEqualTo(FcmProvider.PushResult.REJECTED);
		}
	}

	@Test
	void temporaryTroubleIsRetriedLater() {
		for (MessagingErrorCode code : new MessagingErrorCode[] { MessagingErrorCode.INTERNAL, MessagingErrorCode.QUOTA_EXCEEDED, MessagingErrorCode.UNAVAILABLE }) {
			assertThat(FirebaseFcmProvider.classify(code)).as(code.name()).isEqualTo(FcmProvider.PushResult.TRANSIENT_FAILURE);
		}
		assertThat(FirebaseFcmProvider.classify(null)).as("no code at all (network error)").isEqualTo(FcmProvider.PushResult.TRANSIENT_FAILURE);
	}

}
