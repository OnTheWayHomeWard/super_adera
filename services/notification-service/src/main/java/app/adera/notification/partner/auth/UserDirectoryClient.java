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

	/**
	 * {@code consents}: the user's answer for this app per notify capability (notify.inapp, notify.sms, notify.email):
	 * NOT_ASKED, GRANTED, DENIED or BLOCKED. Only GRANTED lets a message through.
	 */
	public record DeliveryContext(UUID userId, boolean reachable, String language, String phone, String email,
			List<String> pushTokens, Map<String, String> consents) {

		/** The state for one capability; a missing entry is NOT_ASKED. */
		public String consent(String capability) {
			String state = consents == null ? null : consents.get(capability);
			return state == null ? "NOT_ASKED" : state;
		}

		/** The notify capability that covers a delivery channel (SMS, EMAIL, IN_APP). */
		public static String capabilityOf(String channel) {
			return switch (channel) {
				case "SMS" -> "notify.sms";
				case "EMAIL" -> "notify.email";
				default -> "notify.inapp";
			};
		}

	}

	/**
	 * What user-service releases for one partner profile read: {@code states} is the user's answer per requested capability,
	 * {@code fields} holds values ONLY for the capabilities that are GRANTED (fullName, phone, email, address).
	 */
	public record ConsentedProfile(boolean reachable, Map<String, String> states, Map<String, Object> fields) {
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

	/** The profile fields the user allowed this app to read. Empty when the user does not exist. */
	public Optional<ConsentedProfile> profile(UUID userId, UUID appId, List<String> capabilities) {
		try {
			ConsentedProfile p = client.get().uri(u -> u.path("/internal/users/{id}/consented-profile").queryParam("appId", appId)
					.queryParam("capabilities", String.join(",", capabilities)).build(userId))
				.exchange((req, res) -> {
					if (res.getStatusCode().value() == 404) {
						return null;
					}
					if (!res.getStatusCode().is2xxSuccessful()) {
						throw new UpstreamUnavailableException("user-service answered " + res.getStatusCode().value(), null);
					}
					return res.bodyTo(ConsentedProfile.class);
				});
			return Optional.ofNullable(p);
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
