package app.adera.notification.email;

/** Delivers one email. Implementations are selected by {@code adera.notification.email-provider}. */
public interface EmailProvider {

	/** Short name stored in the send log: mailpit, resend. */
	String name();

	/** True if this sends to real inboxes (and therefore needs the live safety checks). */
	boolean isReal();

	/** @throws app.adera.notification.dispatch.DeliveryException if delivery failed */
	void send(EmailMessage message);

}
