package app.adera.registry.audit;

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

/** Append-only: who changed what, when, from where. */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

	@Id
	private UUID id;

	@Column(name = "actor_id")
	private UUID actorId;

	@Column(name = "actor_role")
	private String actorRole;

	@Column(nullable = false)
	private String action;

	@Column(name = "target_type")
	private String targetType;

	@Column(name = "target_id")
	private String targetId;

	/** The mini app this entry is about (the target itself, or the app a key/review/report/template belongs to). */
	@Column(name = "app_id")
	private UUID appId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> meta = new LinkedHashMap<>();

	private String ip;

	@Column(nullable = false)
	private Instant at;

	protected AuditEvent() {
	}

	public AuditEvent(UUID actorId, String actorRole, String action, String targetType, String targetId,
			Map<String, Object> meta, String ip, Instant at) {
		this.id = UUID.randomUUID();
		this.actorId = actorId;
		this.actorRole = actorRole;
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
		this.meta = meta == null ? new LinkedHashMap<>() : meta;
		this.appId = appIdOf(targetType, targetId, this.meta);
		this.ip = ip;
		this.at = at;
	}

	/** The app target itself, else {@code meta.appId} when it is a valid uuid; otherwise null. */
	static UUID appIdOf(String targetType, String targetId, Map<String, Object> meta) {
		Object candidate = "mini_app".equals(targetType) ? targetId : meta.get("appId");
		if (candidate == null) {
			return null;
		}
		try {
			return UUID.fromString(String.valueOf(candidate));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	public UUID getId() { return id; }

	public UUID getAppId() { return appId; }

	public UUID getActorId() { return actorId; }

	public String getActorRole() { return actorRole; }

	public String getAction() { return action; }

	public String getTargetType() { return targetType; }

	public String getTargetId() { return targetId; }

	public Map<String, Object> getMeta() { return meta; }

	public String getIp() { return ip; }

	public Instant getAt() { return at; }

}
