package app.adera.user.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One app installation that can receive push notifications (an FCM token belonging to a user). */
@Entity
@Table(name = "device")
public class Device {

	public enum Platform { ANDROID, IOS, WEB }

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "installation_id")
	private String installationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Platform platform;

	@Column(name = "fcm_token", nullable = false)
	private String fcmToken;

	@Column(name = "app_version")
	private String appVersion;

	@Column(name = "os_version")
	private String osVersion;

	@Column(name = "device_name")
	private String deviceName;

	private String locale;

	@Column(name = "is_active", nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	@Column(name = "token_updated_at", nullable = false)
	private Instant tokenUpdatedAt;

	protected Device() {
	}

	public Device(UUID userId, Instant now) {
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.createdAt = now;
		this.lastSeenAt = now;
		this.tokenUpdatedAt = now;
	}

	/** Applies the app's current description of itself and marks the device usable. */
	public void update(UUID userId, String installationId, Platform platform, String fcmToken, String appVersion,
			String osVersion, String deviceName, String locale, Instant now) {
		if (!fcmToken.equals(this.fcmToken)) {
			this.tokenUpdatedAt = now;
		}
		this.userId = userId;
		this.installationId = installationId;
		this.platform = platform;
		this.fcmToken = fcmToken;
		this.appVersion = appVersion;
		this.osVersion = osVersion;
		this.deviceName = deviceName;
		this.locale = locale;
		this.active = true;
		this.lastSeenAt = now;
	}

	/** Firebase told us this token is dead: keep the row (history) but stop sending to it. */
	public void deactivate() { this.active = false; }

	public UUID getId() { return id; }

	public UUID getUserId() { return userId; }

	public String getInstallationId() { return installationId; }

	public Platform getPlatform() { return platform; }

	public String getFcmToken() { return fcmToken; }

	public String getAppVersion() { return appVersion; }

	public String getOsVersion() { return osVersion; }

	public String getDeviceName() { return deviceName; }

	public String getLocale() { return locale; }

	public boolean isActive() { return active; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getLastSeenAt() { return lastSeenAt; }

	public Instant getTokenUpdatedAt() { return tokenUpdatedAt; }

}
