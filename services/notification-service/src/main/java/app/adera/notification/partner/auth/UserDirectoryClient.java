package app.adera.notification.partner.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.partner.PartnerProperties;
import app.adera.notification.partner.UpstreamUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** user-service's internal API: turn an alias into a user, find out how to reach them, retire dead push tokens. */
@Component
public class UserDirectoryClient {

	public record Muted(boolean muteInApp, boolean muteSms, boolean muteEmail) {
	}

	public record DeliveryContext(UUID userId, boolean reachable, String language, String phone, String email,
			List<String> pushTokens, Muted muted) {
	}

	private final RestClient client;

	public UserDirectoryClient(PartnerProperties props, RestClient.Builder builder) {
		this.client = builder.baseUrl(props.userServiceUrl()).defaultHeader("X-Internal-Secret", props.internalSecret()).build();
	}

	public Optional<UUID> resolve(UUID appId, String alias) {
		try {
			Map<?, ?> body = client.get().uri(u -> u.path("/internal/aliases/resolve").queryParam("appId", appId).queryParam("alias", alias).build())
				.exchange((req, res) -> {
					if (res.getStatusCode().value() == 404) {
						return null;
					}
					if (!res.getStatusCode().is2xxSuccessful()) {
						throw new UpstreamUnavailableException("user-service answered " + res.getStatusCode().value(), null);
					}
					return res.bodyTo(Map.class);
				});
			return body == null ? Optional.empty() : Optional.of(UUID.fromString(String.valueOf(body.get("userId"))));
		}
		catch (RestClientException e) {
			throw new UpstreamUnavailableException("cannot reach user-service", e);
		}
	}

	public Optional<DeliveryContext> context(UUID userId, UUID appId) {
		try {
			DeliveryContext ctx = client.get().uri(u -> u.path("/internal/users/{id}/delivery-context").queryParam("appId", appId).build(userId))
				.exchange((req, res) -> {
					if (res.getStatusCode().value() == 404) {
						return null;
					}
					if (!res.getStatusCode().is2xxSuccessful()) {
						throw new UpstreamUnavailableException("user-service answered " + res.getStatusCode().value(), null);
					}
					return res.bodyTo(DeliveryContext.class);
				});
			return Optional.ofNullable(ctx);
		}
		catch (RestClientException e) {
			throw new UpstreamUnavailableException("cannot reach user-service", e);
		}
	}

	/** Best effort: failing to retire a token must never fail a delivery. */
	public void deactivateToken(String fcmToken) {
		try {
			client.post().uri("/internal/devices/deactivate").contentType(MediaType.APPLICATION_JSON).body(Map.of("fcmToken", fcmToken)).retrieve().toBodilessEntity();
		}
		catch (RestClientException e) {
			// logged by the caller's attempt record; the token will be retried next time it fails
		}
	}

}
