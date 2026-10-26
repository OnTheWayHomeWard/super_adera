package app.adera.notification.partner;

/** A service we depend on (registry, user-service) could not be reached: callers answer 503 and the partner retries. */
public class UpstreamUnavailableException extends RuntimeException {

	public UpstreamUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
