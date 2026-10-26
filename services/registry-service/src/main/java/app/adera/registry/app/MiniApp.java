package app.adera.registry.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

@Entity
@Table(name = "mini_app")
public class MiniApp {

	public enum Status { DRAFT, LIVE, PAUSED, KILLED }

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String slug;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, String> name = new LinkedHashMap<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, String> description = new LinkedHashMap<>();

	@Column(name = "logo_image_id")
	private UUID logoImageId;

	@Column(name = "logo_content_type")
	private String logoContentType;

	@Column(name = "brand_color")
	private String brandColor;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> categories = new ArrayList<>();

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> markets = new ArrayList<>(List.of("ET"));

	@Column(name = "publisher_name")
	private String publisherName;

	@Column(name = "publisher_legal_name")
	private String publisherLegalName;

	@Column(name = "support_email")
	private String supportEmail;

	@Column(name = "support_phone")
	private String supportPhone;

	private String website;

	@Column(name = "entry_url")
	private String entryUrl;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "allowed_origins", nullable = false, columnDefinition = "jsonb")
	private List<String> allowedOrigins = new ArrayList<>();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.DRAFT;

	@Column(nullable = false)
	private int priority = 100;

	@Column(name = "rating_avg", nullable = false)
	private BigDecimal ratingAvg = BigDecimal.ZERO;

	@Column(name = "rating_count", nullable = false)
	private int ratingCount;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> extra = new LinkedHashMap<>();

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected MiniApp() {
	}

	public MiniApp(UUID id, String slug, UUID createdBy, Instant now) {
		this.id = id;
		this.slug = slug;
		this.createdBy = createdBy;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() { return id; }

	public String getSlug() { return slug; }

	public Map<String, String> getName() { return name; }

	public void setName(Map<String, String> name) { this.name = name; }

	public Map<String, String> getDescription() { return description; }

	public void setDescription(Map<String, String> description) { this.description = description; }

	public UUID getLogoImageId() { return logoImageId; }

	public String getLogoContentType() { return logoContentType; }

	public void setLogo(UUID imageId, String contentType) {
		this.logoImageId = imageId;
		this.logoContentType = contentType;
	}

	public String getBrandColor() { return brandColor; }

	public void setBrandColor(String brandColor) { this.brandColor = brandColor; }

	public List<String> getCategories() { return categories; }

	public void setCategories(List<String> categories) { this.categories = categories; }

	public List<String> getMarkets() { return markets; }

	public void setMarkets(List<String> markets) { this.markets = markets; }

	public String getPublisherName() { return publisherName; }

	public void setPublisherName(String v) { this.publisherName = v; }

	public String getPublisherLegalName() { return publisherLegalName; }

	public void setPublisherLegalName(String v) { this.publisherLegalName = v; }

	public String getSupportEmail() { return supportEmail; }

	public void setSupportEmail(String v) { this.supportEmail = v; }

	public String getSupportPhone() { return supportPhone; }

	public void setSupportPhone(String v) { this.supportPhone = v; }

	public String getWebsite() { return website; }

	public void setWebsite(String v) { this.website = v; }

	public String getEntryUrl() { return entryUrl; }

	public void setEntryUrl(String v) { this.entryUrl = v; }

	public List<String> getAllowedOrigins() { return allowedOrigins; }

	public void setAllowedOrigins(List<String> v) { this.allowedOrigins = v; }

	public Status getStatus() { return status; }

	public void setStatus(Status status) { this.status = status; }

	public int getPriority() { return priority; }

	public void setPriority(int priority) { this.priority = priority; }

	public BigDecimal getRatingAvg() { return ratingAvg; }

	public int getRatingCount() { return ratingCount; }

	public Map<String, Object> getExtra() { return extra; }

	public void setExtra(Map<String, Object> extra) { this.extra = extra; }

	public UUID getCreatedBy() { return createdBy; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }

	public void touch(Instant now) { this.updatedAt = now; }

}
