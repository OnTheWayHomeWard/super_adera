package app.adera.notification.partner.delivery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import app.adera.notification.partner.PartnerProperties;
import app.adera.notification.partner.auth.Signatures;
import app.adera.notification.partner.auth.SigningKeyHolder;
import org.springframework.stereotype.Component;

/**
 * POSTs one signed status report to a partner. The signature is OUR key over the same canonical form partners use for their
 * requests, so partners verify callbacks with the key at /.well-known/jwks.json. Never follows redirects.
 */
@Component
public class CallbackSender {

	/**
	 * @param httpStatus 0 when no response was received; {@code blocked} when the address was refused before connecting
	 * @param retryAfter the partner's Retry-After header (seconds or an HTTP date) on a failed answer, else null
	 */
	public record Outcome(boolean delivered, boolean blocked, int httpStatus, String error, java.time.Duration retryAfter) {

		public Outcome(boolean delivered, boolean blocked, int httpStatus, String error) {
			this(delivered, blocked, httpStatus, error, null);
		}

	}

	/** Seconds ("120") or an HTTP date; anything else, or a date in the past, means no instruction. */
	static java.time.Duration parseRetryAfter(String header, Instant now) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String value = header.trim();
		try {
			long seconds = Long.parseLong(value);
			return seconds < 0 ? null : Duration.ofSeconds(Math.min(seconds, 7 * 86_400L));
		}
		catch (NumberFormatException notSeconds) {
			try {
				Instant at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				return at.isAfter(now) ? Duration.between(now, at) : null;
			}
			catch (java.time.format.DateTimeParseException e) {
				return null;
			}
		}
	}

	private final SigningKeyHolder keys;
	private final boolean allowPrivate;
	private final HttpClient client = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NEVER)
		.connectTimeout(Duration.ofSeconds(5))
		.build();

	public CallbackSender(SigningKeyHolder keys, PartnerProperties props) {
		this.keys = keys;
		this.allowPrivate = props.allowPrivateCallbacks();
	}

	public Outcome send(UUID callbackId, String url, String jsonBody) {
		URI uri;
		try {
			uri = UrlGuard.check(url, allowPrivate);
		}
		catch (IllegalArgumentException e) {
			return new Outcome(false, true, 0, e.getMessage());
		}
		byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
		String timestamp = String.valueOf(Instant.now().getEpochSecond());
		String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
		String pathAndQuery = path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
		String signature = keys.sign(Signatures.canonicalRequest(timestamp, "POST", pathAndQuery, body));
		try {
			HttpResponse<Void> response = client.send(HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(8))
				.header("Content-Type", "application/json")
				.header("User-Agent", "Adera-Callbacks/1")
				.header("X-Adera-Event", "notification.status")
				.header("X-Adera-Key-Id", keys.keyId())
				.header("X-Adera-Timestamp", timestamp)
				.header("X-Adera-Signature", signature)
				.header("Idempotency-Key", callbackId.toString())
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build(), HttpResponse.BodyHandlers.discarding());
			int status = response.statusCode();
			boolean ok = status >= 200 && status < 300;
			return new Outcome(ok, false, status, ok ? null : "http_" + status,
					ok ? null : parseRetryAfter(response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Outcome(false, false, 0, "interrupted");
		}
		catch (Exception e) {
			return new Outcome(false, false, 0, "unreachable");
		}
	}

}
