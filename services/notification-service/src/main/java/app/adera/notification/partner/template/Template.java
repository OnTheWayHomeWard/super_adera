package app.adera.notification.partner.template;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "template")
public class Template {

	@Id
	private UUID id;

	@Column(name = "app_id", nullable = false)
	private UUID appId;

	@Column(nullable = false)
	private String code;

	@Column(nullable = false)
	private String kind;

	@Column(nullable = false)
	private String category;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> params = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Map<String, String>> content = new LinkedHashMap<>();

	@Column(nullable = false)
	private boolean active = true;

	@Column(name = "valid_from")
	private Instant validFrom;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(nullable = false)
	private int version = 1;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "updated_by")
	private UUID updatedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Template() {
	}

	public Template(UUID appId, String code, String kind, String category, UUID createdBy, Instant now) {
		this.id = UUID.randomUUID();
		this.appId = appId;
		this.code = code;
		this.kind = kind;
		this.category = category;
		this.createdBy = createdBy;
		this.updatedBy = createdBy;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** True while the admin has it switched on and now is inside its validity window. */
	public boolean usableAt(Instant now) {
		return active && (validFrom == null || !now.isBefore(validFrom)) && (expiresAt == null || now.isBefore(expiresAt));
	}

	public String unusableReason(Instant now) {
		if (!active) {
			return "template_inactive";
		}
		if (validFrom != null && now.isBefore(validFrom)) {
			return "template_not_yet_valid";
		}
		if (expiresAt != null && !now.isBefore(expiresAt)) {
			return "template_expired";
		}
		return null;
	}

	public UUID getId() { return id; }

	public UUID getAppId() { return appId; }

	public String getCode() { return code; }

	public String getKind() { return kind; }

	public String getCategory() { return category; }

	public void setCategory(String category) { this.category = category; }

	public List<Map<String, Object>> getParams() { return params; }

	public void setParams(List<Map<String, Object>> params) { this.params = params; }

	public Map<String, Map<String, String>> getContent() { return content; }

	public void setContent(Map<String, Map<String, String>> content) { this.content = content; }

	public boolean isActive() { return active; }

	public void setActive(boolean active) { this.active = active; }

	public Instant getValidFrom() { return validFrom; }

	public void setValidFrom(Instant validFrom) { this.validFrom = validFrom; }

	public Instant getExpiresAt() { return expiresAt; }

	public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

	public int getVersion() { return version; }

	public void bumpVersion() { this.version++; }

	public UUID getCreatedBy() { return createdBy; }

	public UUID getUpdatedBy() { return updatedBy; }

	public void touch(UUID by, Instant now) {
		this.updatedBy = by;
		this.updatedAt = now;
	}

	public Instant getCreatedAt() { return createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }

}
