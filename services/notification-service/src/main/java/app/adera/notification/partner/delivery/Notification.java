package app.adera.notification.partner.delivery;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One request from a partner to reach one user. The partner identifies it by its own externalId. */
@Entity
@Table(name = "notification")
public class Notification {

	public static final String QUEUED = "QUEUED";
	public static final String PROCESSING = "PROCESSING";
	public static final String RETRYING = "RETRYING";
	public static final String SENT = "SENT";
	public static final String FAILED = "FAILED";
	public static final String UNREACHABLE = "UNREACHABLE";
	public static final String SUPPRESSED = "SUPPRESSED";
	public static final String EXPIRED = "EXPIRED";

	@Id
	private UUID id;

	@Column(name = "app_id", nullable = false)
	private UUID appId;

	@Column(nullable = false)
	private String environment;

	@Column(name = "external_id", nullable = false)
	private String externalId;

	@Column(name = "request_hash", nullable = false)
	private String requestHash;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "user_alias", nullable = false)
	private String userAlias;

	@Column(name = "template_id", nullable = false)
	private UUID templateId;

	@Column(name = "template_version", nullable = false)
	private int templateVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, String> params = new LinkedHashMap<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> data = new LinkedHashMap<>();

	@Column(nullable = false)
	private String kind;

	@Column(nullable = false)
	private String channel;

	@Column(nullable = false)
	private String status;

	private String reason;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "callback_url")
	private String callbackUrl;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	protected Notification() {
	}

	public UUID getId() { return id; }

	public UUID getAppId() { return appId; }

	public String getEnvironment() { return environment; }

	public String getExternalId() { return externalId; }

	public String getRequestHash() { return requestHash; }

	public UUID getUserId() { return userId; }

	public String getUserAlias() { return userAlias; }

	public UUID getTemplateId() { return templateId; }

	public int getTemplateVersion() { return templateVersion; }

	public Map<String, String> getParams() { return params; }

	public Map<String, Object> getData() { return data; }

	public String getKind() { return kind; }

	public String getChannel() { return channel; }

	public String getStatus() { return status; }

	public String getReason() { return reason; }

	public int getAttempts() { return attempts; }

	public Instant getNextAttemptAt() { return nextAttemptAt; }

	public String getCallbackUrl() { return callbackUrl; }

	public Instant getExpiresAt() { return expiresAt; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }

	public Instant getCompletedAt() { return completedAt; }

	public boolean isTerminal() {
		return switch (status) {
			case SENT, FAILED, UNREACHABLE, SUPPRESSED, EXPIRED -> true;
			default -> false;
		};
	}

}
