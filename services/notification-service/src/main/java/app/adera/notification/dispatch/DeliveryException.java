package app.adera.notification.dispatch;

/** A provider could not deliver. The message is a short code safe to log and store: never a body or recipient. */
public class DeliveryException extends RuntimeException {

	private final String code;

	public DeliveryException(String code, Throwable cause) {
		super(code, cause);
		this.code = code;
	}

	public String code() {
		return code;
	}

}
