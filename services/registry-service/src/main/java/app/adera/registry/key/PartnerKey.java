package app.adera.registry.key;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A partner's public key. {@code keyId} is an identifier sent in request headers, not a secret. */
@Entity
@Table(name = "partner_key")
public class PartnerKey {

	@Id
	@Column(name = "key_id")
	private String keyId;

	@Column(name = "app_id", nullable = false)
	private UUID appId;

	@Column(nullable = false)
	private String environment;

	@Column(nullable = false)
	private String algorithm = "RS256";

	@Column(name = "public_key_pem", nullable = false)
	private String publicKeyPem;

	@Column(nullable = false)
	private String fingerprint;

	private String label;

	@Column(nullable = false)
	private String status = "ACTIVE";

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected PartnerKey() {
	}

	public PartnerKey(String keyId, UUID appId, String environment, String publicKeyPem, String fingerprint, String label,
			Instant expiresAt, UUID createdBy, Instant now) {
		this.keyId = keyId;
		this.appId = appId;
		this.environment = environment;
		this.publicKeyPem = publicKeyPem;
		this.fingerprint = fingerprint;
		this.label = label;
		this.expiresAt = expiresAt;
		this.createdBy = createdBy;
		this.createdAt = now;
	}

	public String getKeyId() { return keyId; }

	public UUID getAppId() { return appId; }

	public String getEnvironment() { return environment; }

	public String getAlgorithm() { return algorithm; }

	public String getPublicKeyPem() { return publicKeyPem; }

	public String getFingerprint() { return fingerprint; }

	public String getLabel() { return label; }

	public String getStatus() { return status; }

	public Instant getExpiresAt() { return expiresAt; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getRevokedAt() { return revokedAt; }

	public boolean isActive(Instant now) {
		return "ACTIVE".equals(status) && (expiresAt == null || expiresAt.isAfter(now));
	}

	public void revoke(Instant now) {
		this.status = "REVOKED";
		this.revokedAt = now;
	}

}
