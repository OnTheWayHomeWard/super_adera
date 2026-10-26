package app.adera.notification.sms;

/** Delivers one SMS. Implementations are selected by {@code adera.notification.sms-provider}. */
public interface SmsProvider {

	/** Short name stored in the send log: console, twilio. */
	String name();

	/** True if this sends to real phones (and therefore costs money / needs the live safety checks). */
	boolean isReal();

	/** @throws app.adera.notification.dispatch.DeliveryException if delivery failed */
	void send(String to, String body);

}
