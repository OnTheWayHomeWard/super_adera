package app.adera.registry.staff;

import java.time.Instant;
import java.util.UUID;

import app.adera.registry.auth.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "staff")
public class Staff {

	public enum Status { ACTIVE, DISABLED }

	@Id
	@Column(name = "identity_id")
	private UUID identityId;

	@Column(nullable = false)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.ACTIVE;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Staff() {
	}

	public Staff(UUID identityId, String email, Role role, UUID createdBy, Instant now) {
		this.identityId = identityId;
		this.email = email;
		this.role = role;
		this.createdBy = createdBy;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public UUID getIdentityId() { return identityId; }

	public String getEmail() { return email; }

	public Role getRole() { return role; }

	public void setRole(Role role, Instant now) { this.role = role; this.updatedAt = now; }

	public Status getStatus() { return status; }

	public void setStatus(Status status, Instant now) { this.status = status; this.updatedAt = now; }

	public UUID getCreatedBy() { return createdBy; }

	public Instant getCreatedAt() { return createdAt; }

	public Instant getUpdatedAt() { return updatedAt; }

}
