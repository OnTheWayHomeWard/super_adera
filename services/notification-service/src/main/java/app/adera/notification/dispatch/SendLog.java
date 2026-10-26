package app.adera.notification.dispatch;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One delivery attempt. Holds no message body and no full recipient (see V1__send_log.sql). */
@Entity
@Table(name = "send_log")
public class SendLog {

	public enum Channel { SMS, EMAIL }

	public enum Status { SENT, FAILED, SUPPRESSED, SKIPPED }

	@Id
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private Channel channel;

	@Column(name = "recipient_hash", nullable = false, length = 64)
	private String recipientHash;

	@Column(name = "recipient_masked", nullable = false, length = 64)
	private String recipientMasked;

	@Column(name = "template_type", length = 64)
	private String templateType;

	@Column(nullable = false, length = 32)
	private String provider;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private Status status;

	@Column(name = "error_code", length = 64)
	private String errorCode;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected SendLog() {
	}

	public SendLog(Channel channel, String recipientHash, String recipientMasked, String templateType,
			String provider, Status status, String errorCode, Instant createdAt) {
		this.id = UUID.randomUUID();
		this.channel = channel;
		this.recipientHash = recipientHash;
		this.recipientMasked = recipientMasked;
		this.templateType = templateType;
		this.provider = provider;
		this.status = status;
		this.errorCode = errorCode;
		this.createdAt = createdAt;
	}

	public UUID getId() { return id; }

	public Channel getChannel() { return channel; }

	public String getRecipientHash() { return recipientHash; }

	public String getRecipientMasked() { return recipientMasked; }

	public String getTemplateType() { return templateType; }

	public String getProvider() { return provider; }

	public Status getStatus() { return status; }

	public String getErrorCode() { return errorCode; }

	public Instant getCreatedAt() { return createdAt; }

}
