package app.adera.user.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class DeviceDtos {

	private DeviceDtos() {
	}

	/** What the app sends after login, and again whenever Firebase rotates the token. */
	public record RegisterRequest(
			@NotBlank @Size(max = 4096) String fcmToken,
			@NotNull Device.Platform platform,
			@Size(max = 64) String installationId,
			@Size(max = 32) String appVersion,
			@Size(max = 32) String osVersion,
			@Size(max = 100) String deviceName,
			@Size(max = 16) String locale) {
	}

	/** Identify the device to remove by its token or its installation id (at least one). */
	public record RemoveRequest(@Size(max = 4096) String fcmToken, @Size(max = 64) String installationId) {

		/** The body's values win; the query parameters fill in what the body did not say. Either may be null. */
		public static RemoveRequest merge(RemoveRequest body, String queryToken, String queryInstallationId) {
			String token = body != null && body.fcmToken() != null && !body.fcmToken().isBlank() ? body.fcmToken() : queryToken;
			String installation = body != null && body.installationId() != null && !body.installationId().isBlank() ? body.installationId() : queryInstallationId;
			return new RemoveRequest(token, installation);
		}

		public boolean isEmpty() {
			return (fcmToken == null || fcmToken.isBlank()) && (installationId == null || installationId.isBlank());
		}

	}

	/** The token itself is deliberately not echoed back. */
	public record Response(UUID id, String installationId, String platform, String appVersion, String osVersion,
			String deviceName, String locale, boolean active, Instant createdAt, Instant lastSeenAt) {

		static Response of(Device d) {
			return new Response(d.getId(), d.getInstallationId(), d.getPlatform().name(), d.getAppVersion(),
					d.getOsVersion(), d.getDeviceName(), d.getLocale(), d.isActive(), d.getCreatedAt(), d.getLastSeenAt());
		}

	}

}
