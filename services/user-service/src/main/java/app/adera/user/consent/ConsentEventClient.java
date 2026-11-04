package app.adera.user.consent;

import java.time.Duration;
import java.util.Map;

import app.adera.user.admin.ApiException;
import app.adera.user.config.UserProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Hands a consent event to notification-service ({@code POST /internal/consent-events}), which signs it and delivers it to the
 * partner's callback URL with the retry schedule of the other callbacks (if the app has a URL). A non-2xx answer or an outage is
 * a failed attempt: the outbox keeps the event and tries again.
 */
@Component
public class ConsentEventClient {

	private final RestClient client;
	private final String internalSecret;

	public ConsentEventClient(UserProperties props, RestClient.Builder builder) {
		java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(4));
		this.client = builder.clone().baseUrl(props.notification().url()).requestFactory(factory).build();
		this.internalSecret = props.internalSecret();
	}

	/** @throws ApiException 503 service_unavailable when it could not be handed over */
	public void send(Map<String, Object> event) {
		try {
			client.post().uri("/internal/consent-events").contentType(MediaType.APPLICATION_JSON).headers(h -> {
				if (!internalSecret.isEmpty()) {
					h.set("X-Internal-Secret", internalSecret);
				}
			}).body(event).retrieve().toBodilessEntity();
		}
		catch (RestClientException e) {
			throw new ApiException(503, "service_unavailable");
		}
	}

}
