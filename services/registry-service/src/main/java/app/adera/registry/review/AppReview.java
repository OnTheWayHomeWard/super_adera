package app.adera.registry.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_review")
public class AppReview {

	@Id
	private UUID id;

	@Column(name = "app_id", nullable = false)
	private UUID appId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private short rating;

	private String comment;

	@Column(nullable = false)
	private String status = "VISIBLE";

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AppReview() {
	}

	public AppReview(UUID appId, UUID userId, int rating, String comment, Instant now) {
		this.id = UUID.randomUUID();
		this.appId = appId;
		this.userId = userId;
		this.rating = (short) rating;
		this.comment = comment;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public void update(int rating, String comment, Instant now) {
		this.rating = (short) rating;
		this.comment = comment;
		this.updatedAt = now;
	}

	public void setStatus(String status, Instant now) {
		this.status = status;
		this.updatedAt = now;
	}

	public UUID getId() { return id; }

	public UUID getAppId() { return appId; }

	public UUID getUserId() { return userId; }

	public int getRating() { return rating; }

	public String getComment() { return comment; }

	public String getStatus() { return status; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }

}
