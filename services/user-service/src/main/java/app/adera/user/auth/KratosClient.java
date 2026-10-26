package app.adera.user.auth;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.user.config.UserProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Talks to Ory Kratos from inside the Docker network: session check, identity lookup, logout. */
@Component
public class KratosClient {

	/** Who a valid session belongs to. Email/phone are only filled by {@link #identity(UUID)}. */
	public record Identity(UUID id, String email, String phone) {
	}

	/** Kratos could not be reached or answered with a server error: not the same as "invalid session". */
	public static class KratosUnavailableException extends RuntimeException {

		public KratosUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	private final RestClient publicApi;
	private final RestClient adminApi;

	public KratosClient(UserProperties props, RestClient.Builder builder) {
		// RestClient.Builder is prototype-scoped, but Spring injects one instance here: clone before customising.
		this.publicApi = builder.clone().baseUrl(props.kratos().publicUrl()).build();
		this.adminApi = builder.clone().baseUrl(props.kratos().adminUrl()).build();
	}

	/**
	 * @param authorization the caller's {@code Authorization: Bearer <session token>} header, or null
	 * @param cookie the caller's {@code Cookie} header, or null
	 * @return the identity id if the session is valid and active
	 */
	public Optional<UUID> whoami(String authorization, String cookie) {
		try {
			JsonNode session = publicApi.get()
				.uri("/sessions/whoami")
				.headers(h -> {
					if (authorization != null) {
						h.set("Authorization", authorization);
					}
					if (cookie != null) {
						h.set("Cookie", cookie);
					}
				})
				.exchange((request, response) -> {
					HttpStatusCode status = response.getStatusCode();
					if (status.value() == 401 || status.value() == 403) {
						return null;
					}
					if (!status.is2xxSuccessful()) {
						throw new KratosUnavailableException("whoami answered " + status.value(), null);
					}
					return response.bodyTo(JsonNode.class);
				});
			if (session == null || !session.path("active").asBoolean(false)) {
				return Optional.empty();
			}
			return Optional.of(UUID.fromString(session.path("identity").path("id").asString("")));
		}
		catch (RestClientException | IllegalArgumentException e) {
			throw new KratosUnavailableException("cannot reach Kratos", e);
		}
	}

	/** Reads the trusted copy of an identity's email/phone from Kratos' admin API (internal network only). */
	public Optional<Identity> identity(UUID id) {
		try {
			JsonNode identity = adminApi.get().uri("/admin/identities/{id}", id).exchange((request, response) -> {
				if (response.getStatusCode().value() == 404) {
					return null;
				}
				if (!response.getStatusCode().is2xxSuccessful()) {
					throw new KratosUnavailableException("identity lookup answered " + response.getStatusCode().value(), null);
				}
				return response.bodyTo(JsonNode.class);
			});
			if (identity == null) {
				return Optional.empty();
			}
			JsonNode traits = identity.path("traits");
			return Optional.of(new Identity(id, blankToNull(traits.path("email").asString("")),
					blankToNull(traits.path("phone_number").asString(""))));
		}
		catch (RestClientException e) {
			throw new KratosUnavailableException("cannot reach Kratos admin", e);
		}
	}

	/** Revokes the session behind this token. A token that is already invalid counts as logged out. */
	public void logout(String sessionToken) {
		try {
			publicApi.method(HttpMethod.DELETE)
				.uri("/self-service/logout/api")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("session_token", sessionToken))
				.exchange((request, response) -> {
					if (response.getStatusCode().is5xxServerError()) {
						throw new KratosUnavailableException("logout answered " + response.getStatusCode().value(), null);
					}
					return null;
				});
		}
		catch (RestClientException e) {
			throw new KratosUnavailableException("cannot reach Kratos", e);
		}
	}

	/** Signs the person out everywhere (their sessions stop working). Best effort: a failure is logged, not thrown. */
	public void revokeSessions(UUID id) {
		try {
			adminApi.delete().uri("/admin/identities/{id}/sessions", id).retrieve().toBodilessEntity();
		}
		catch (RuntimeException e) {
			org.slf4j.LoggerFactory.getLogger(KratosClient.class).warn("could not revoke Kratos sessions of {}: {}", id, e.getMessage());
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}

}
