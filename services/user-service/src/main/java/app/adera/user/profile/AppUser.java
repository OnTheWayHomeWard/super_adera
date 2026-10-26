package app.adera.user.profile;

import java.time.Instant;
import java.time.LocalDate;
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

/** The full user record. {@code id} is the Ory Kratos identity id. */
@Entity
@Table(name = "app_user")
public class AppUser {

	public enum Gender { MALE, FEMALE, OTHER, PREFER_NOT_TO_SAY }

	/** CONTACT = verified by a code sent to their phone/email; ID = identity document checked; PROVIDER = a service provider. */
	public enum VerificationTier { CONTACT, ID, PROVIDER }

	public enum Status { ACTIVE, SUSPENDED, DELETED }

	@Id
	private UUID id;

	private String email;

	@Column(name = "phone_e164")
	private String phoneE164;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	@Enumerated(EnumType.STRING)
	private Gender gender;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	@Column(nullable = false)
	private String language = "en";

	@Column(nullable = false)
	private String market = "ET";

	@Column(nullable = false)
	private String timezone = "Africa/Addis_Ababa";

	@Column(name = "avatar_image_id")
	private UUID avatarImageId;

	@Column(name = "avatar_content_type")
	private String avatarContentType;

	@Column(name = "avatar_updated_at")
	private Instant avatarUpdatedAt;

	@Column(name = "country_code")
	private String countryCode;

	private String region;

	private String city;

	private Double latitude;

	private Double longitude;

	@Column(name = "location_updated_at")
	private Instant locationUpdatedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "verification_tier", nullable = false)
	private VerificationTier verificationTier = VerificationTier.CONTACT;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.ACTIVE;

	@Column(name = "terms_accepted_version")
	private String termsAcceptedVersion;

	@Column(name = "terms_accepted_at")
	private Instant termsAcceptedAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> extra = new LinkedHashMap<>();

	@Column(name = "last_seen_at")
	private Instant lastSeenAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected AppUser() {
	}

	public AppUser(UUID id, String email, String phoneE164, String market, Instant now) {
		this.id = id;
		this.email = email;
		this.phoneE164 = phoneE164;
		this.market = market;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getId() { return id; }

	public String getEmail() { return email; }

	public void setEmail(String email) { this.email = email; }

	public String getPhoneE164() { return phoneE164; }

	public void setPhoneE164(String phoneE164) { this.phoneE164 = phoneE164; }

	public String getFirstName() { return firstName; }

	public void setFirstName(String firstName) { this.firstName = firstName; }

	public String getLastName() { return lastName; }

	public void setLastName(String lastName) { this.lastName = lastName; }

	public Gender getGender() { return gender; }

	public void setGender(Gender gender) { this.gender = gender; }

	public LocalDate getDateOfBirth() { return dateOfBirth; }

	public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

	public String getLanguage() { return language; }

	public void setLanguage(String language) { this.language = language; }

	public String getMarket() { return market; }

	public void setMarket(String market) { this.market = market; }

	public String getTimezone() { return timezone; }

	public void setTimezone(String timezone) { this.timezone = timezone; }

	public UUID getAvatarImageId() { return avatarImageId; }

	public String getAvatarContentType() { return avatarContentType; }

	public Instant getAvatarUpdatedAt() { return avatarUpdatedAt; }

	public void setAvatar(UUID imageId, String contentType, Instant now) {
		this.avatarImageId = imageId;
		this.avatarContentType = contentType;
		this.avatarUpdatedAt = now;
	}

	public void clearAvatar() {
		this.avatarImageId = null;
		this.avatarContentType = null;
		this.avatarUpdatedAt = null;
	}

	public String getCountryCode() { return countryCode; }

	public String getRegion() { return region; }

	public String getCity() { return city; }

	public Double getLatitude() { return latitude; }

	public Double getLongitude() { return longitude; }

	public Instant getLocationUpdatedAt() { return locationUpdatedAt; }

	public void setLocation(String countryCode, String region, String city, Double latitude, Double longitude, Instant now) {
		this.countryCode = countryCode;
		this.region = region;
		this.city = city;
		this.latitude = latitude;
		this.longitude = longitude;
		this.locationUpdatedAt = now;
	}

	public void clearLocation() {
		this.countryCode = null;
		this.region = null;
		this.city = null;
		this.latitude = null;
		this.longitude = null;
		this.locationUpdatedAt = null;
	}

	public VerificationTier getVerificationTier() { return verificationTier; }

	public Status getStatus() { return status; }

	public String getTermsAcceptedVersion() { return termsAcceptedVersion; }

	public Instant getTermsAcceptedAt() { return termsAcceptedAt; }

	public void acceptTerms(String version, Instant now) {
		this.termsAcceptedVersion = version;
		this.termsAcceptedAt = version == null ? null : now;
	}

	public Map<String, Object> getExtra() { return extra; }

	public void setExtra(Map<String, Object> extra) { this.extra = extra; }

	public Instant getLastSeenAt() { return lastSeenAt; }

	public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }

	public void touch(Instant now) { this.updatedAt = now; }

	/** Staff action. DELETED also stamps deletedAt (the unique email/phone indexes ignore deleted rows). */
	public void changeStatus(Status status, Instant now) {
		this.status = status;
		if (status == Status.DELETED && this.deletedAt == null) {
			this.deletedAt = now;
		}
		this.updatedAt = now;
	}

	public Instant getDeletedAt() { return deletedAt; }

}
