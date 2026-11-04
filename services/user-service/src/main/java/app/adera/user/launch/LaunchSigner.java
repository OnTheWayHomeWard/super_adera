package app.adera.user.launch;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.user.admin.ApiException;
import app.adera.user.config.UserProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks notification-service (which holds the Adera signing key, the one behind /.well-known/jwks.json) to sign a launch token.
 * Why not sign here: one private key in one place. user-service decides WHO may get a token and with which scopes; the signer
 * only signs what it is told, validates the shape, and is reachable only with the internal secret.
 */
@Component
public class LaunchSigner {

	public record Signed(String token, int expiresInSeconds) {
	}

	private final RestClient client;
	private final String internalSecret;

	public LaunchSigner(UserProperties props, RestClient.Builder builder) {
		java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(4));
		this.client = builder.clone().baseUrl(props.notification().url()).requestFactory(factory).build();
		this.internalSecret = props.internalSecret();
	}

	/** @throws ApiException 503 service_unavailable when the signer cannot answer */
	@SuppressWarnings("unchecked")
	public Signed sign(UUID appId, String alias, List<String> scopes) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("appId", appId);
		body.put("alias", alias);
		body.put("scopes", scopes);
		try {
			Map<String, Object> res = client.post().uri("/internal/launch-tokens").contentType(MediaType.APPLICATION_JSON)
				.headers(h -> {
					if (!internalSecret.isEmpty()) {
						h.set("X-Internal-Secret", internalSecret);
					}
				}).body(body).retrieve().body(Map.class);
			if (res == null || !(res.get("token") instanceof String token)) {
				throw new ApiException(503, "service_unavailable");
			}
			return new Signed(token, ((Number) res.getOrDefault("expiresInSeconds", 300)).intValue());
		}
		catch (RestClientException e) {
			throw new ApiException(503, "service_unavailable");
		}
	}

}
