package app.adera.registry.banner;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A promotional banner in the super app. Staff set only {@link Status}; what users see is computed from it, the clock and
 * the linked app ({@link BannerState}).
 */
@Entity
@Table(name = "banner")
public class Banner {

	public enum Placement { HOME_HERO, FEATURED, PROMOTIONS, CATALOG_TOP }

	public enum Category { PROMOTION, FEATURED_APP, ANNOUNCEMENT, EVENT }

	public enum ActionType { MINI_APP, INTERNAL, EXTERNAL, NONE }

	/** The one switch staff set. "Ended" is never stored: it is computed. */
	public enum Status { DRAFT, PUBLISHED, PAUSED }

	@Id
	private UUID id;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, String> title = new LinkedHashMap<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, String> subtitle = new LinkedHashMap<>();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Placement placement;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Category category;

	@Column(name = "app_id")
	private UUID appId;

	@Enumerated(EnumType.STRING)
	@Column(name = "action_type", nullable = false)
	private ActionType actionType;

	private String target;

	@Column(name = "image_id")
	private UUID imageId;

	/** size name -> {"width": .., "height": ..} */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "image_variants", columnDefinition = "jsonb")
	private Map<String, Map<String, Integer>> imageVariants;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at")
	private Instant endsAt;

	@Column(nullable = false)
	private int priority;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.DRAFT;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Banner() {
	}

	public Banner(UUID id, UUID createdBy, Instant now) {
		this.id = id;
		this.createdBy = createdBy;
		this.updatedBy = createdBy;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() { return id; }

	public Map<String, String> getTitle() { return title; }

	public void setTitle(Map<String, String> title) { this.title = title; }

	public Map<String, String> getSubtitle() { return subtitle; }

	public void setSubtitle(Map<String, String> subtitle) { this.subtitle = subtitle == null ? new LinkedHashMap<>() : subtitle; }

	public Placement getPlacement() { return placement; }

	public void setPlacement(Placement placement) { this.placement = placement; }

	public Category getCategory() { return category; }

	public void setCategory(Category category) { this.category = category; }

	public UUID getAppId() { return appId; }

	public void setAppId(UUID appId) { this.appId = appId; }

	public ActionType getActionType() { return actionType; }

	public void setActionType(ActionType actionType) { this.actionType = actionType; }

	public String getTarget() { return target; }

	public void setTarget(String target) { this.target = target; }

	public UUID getImageId() { return imageId; }

	public Map<String, Map<String, Integer>> getImageVariants() { return imageVariants; }

	public void setImage(UUID imageId, Map<String, Map<String, Integer>> variants) {
		this.imageId = imageId;
		this.imageVariants = variants;
	}

	public Instant getStartsAt() { return startsAt; }

	public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }

	public Instant getEndsAt() { return endsAt; }

	public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }

	public int getPriority() { return priority; }

	public void setPriority(int priority) { this.priority = priority; }

	public Status getStatus() { return status; }

	public void setStatus(Status status) { this.status = status; }

	public UUID getCreatedBy() { return createdBy; }

	public UUID getUpdatedBy() { return updatedBy; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }

	public void touch(UUID by, Instant now) {
		this.updatedBy = by;
		this.updatedAt = now;
	}

}
