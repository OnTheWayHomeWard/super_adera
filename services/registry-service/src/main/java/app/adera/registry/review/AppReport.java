package app.adera.registry.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user telling us a mini app is a problem (from the 3-dot menu in the app window). */
@Entity
@Table(name = "app_report")
public class AppReport {

	@Id
	private UUID id;

	@Column(name = "app_id", nullable = false)
	private UUID appId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private String reason;

	private String details;

	@Column(nullable = false)
	private String status = "OPEN";

	@Column(name = "handled_by")
	private UUID handledBy;

	@Column(name = "handled_at")
	private Instant handledAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected AppReport() {
	}

	public AppReport(UUID appId, UUID userId, String reason, String details, Instant now) {
		this.id = UUID.randomUUID();
		this.appId = appId;
		this.userId = userId;
		this.reason = reason;
		this.details = details;
		this.createdAt = now;
	}

	public void handle(String status, UUID staffId, Instant now) {
		this.status = status;
		this.handledBy = staffId;
		this.handledAt = now;
	}

	public UUID getId() { return id; }

	public UUID getAppId() { return appId; }

	public UUID getUserId() { return userId; }

	public String getReason() { return reason; }

	public String getDetails() { return details; }

	public String getStatus() { return status; }

	public UUID getHandledBy() { return handledBy; }

	public Instant getHandledAt() { return handledAt; }

	public Instant getCreatedAt() { return createdAt; }

}
