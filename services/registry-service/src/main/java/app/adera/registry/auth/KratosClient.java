package app.adera.registry.auth;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.registry.config.RegistryProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Talks to Ory Kratos from inside the Docker network: session check and staff identity management. */
@Component
public class KratosClient {

	public record Session(UUID identityId, String email, String role, String aal) {

		public boolean isAal2() {
			return "aal2".equalsIgnoreCase(aal);
		}

	}

	public enum Outcome { VALID, INVALID, MFA_REQUIRED }

	public record WhoamiResult(Outcome outcome, Session session) {

		static WhoamiResult of(Outcome outcome) {
			return new WhoamiResult(outcome, null);
		}

	}

	public record IdentityRef(UUID id, String email, String role) {
	}

	public static class KratosUnavailableException extends RuntimeException {

		public KratosUnavailableException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	private final RestClient publicApi;
	private final RestClient adminApi;

	public KratosClient(RegistryProperties props, RestClient.Builder builder) {
		this.publicApi = builder.clone().baseUrl(props.kratos().publicUrl()).build();
		this.adminApi = builder.clone().baseUrl(props.kratos().adminUrl()).build();
	}

	/**
	 * 401 = no/invalid session. 403 from Kratos means the identity has an authenticator app enrolled but this session
	 * has not completed that step yet, which we report as {@link Outcome#MFA_REQUIRED}.
	 */
	public WhoamiResult whoami(String authorization) {
		if (authorization == null) {
			return WhoamiResult.of(Outcome.INVALID);
		}
		try {
			return publicApi.get().uri("/sessions/whoami").header("Authorization", authorization).exchange((req, res) -> {
				int status = res.getStatusCode().value();
				if (status == 401) {
					return WhoamiResult.of(Outcome.INVALID);
				}
				if (status == 403) {
					return WhoamiResult.of(Outcome.MFA_REQUIRED);
				}
				if (!res.getStatusCode().is2xxSuccessful()) {
					throw new KratosUnavailableException("whoami answered " + status, null);
				}
				JsonNode session = res.bodyTo(JsonNode.class);
				if (session == null || !session.path("active").asBoolean(false)) {
					return WhoamiResult.of(Outcome.INVALID);
				}
				JsonNode identity = session.path("identity");
				String role = identity.path("metadata_public").path("role").asString("");
				return new WhoamiResult(Outcome.VALID, new Session(UUID.fromString(identity.path("id").asString("")),
						identity.path("traits").path("email").asString(""), role.isBlank() ? null : role,
						session.path("authenticator_assurance_level").asString("aal1")));
			});
		}
		catch (RestClientException | IllegalArgumentException e) {
			throw new KratosUnavailableException("cannot reach Kratos", e);
		}
	}

	public Optional<IdentityRef> findByEmail(String email) {
		try {
			JsonNode list = adminApi.get()
				.uri(u -> u.path("/admin/identities").queryParam("credentials_identifier", email).build())
				.retrieve()
				.body(JsonNode.class);
			if (list == null || !list.isArray() || list.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(ref(list.get(0)));
		}
		catch (RestClientException e) {
			throw new KratosUnavailableException("cannot reach Kratos admin", e);
		}
	}

	public IdentityRef createStaffIdentity(String email, Role role) {
		try {
			JsonNode created = adminApi.post()
				.uri("/admin/identities")
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("schema_id", "default", "state", "active", "traits", Map.of("email", email),
						"metadata_public", Map.of("role", role.name())))
				.retrieve()
				.body(JsonNode.class);
			return ref(created);
		}
		catch (RestClientException e) {
			throw new KratosUnavailableException("cannot create identity in Kratos", e);
		}
	}

	/** Sets the role, or removes it (null) so the identity is a plain customer again. */
	public void setRole(UUID identityId, Role role) {
		try {
			Object value = role == null ? Map.of() : Map.of("role", role.name());
			adminApi.patch()
				.uri("/admin/identities/{id}", identityId)
				.contentType(MediaType.APPLICATION_JSON)
				.body(List.of(Map.of("op", "add", "path", "/metadata_public", "value", value)))
				.retrieve()
				.toBodilessEntity();
		}
		catch (RestClientException e) {
			throw new KratosUnavailableException("cannot update identity in Kratos", e);
		}
	}

	public void revokeSessions(UUID identityId) {
		try {
			adminApi.method(HttpMethod.DELETE).uri("/admin/identities/{id}/sessions", identityId).retrieve().toBodilessEntity();
		}
		catch (HttpClientErrorException.NotFound e) {
			// Kratos answers 404 when the identity has no sessions (a staff member added but never signed in): nothing to revoke
		}
		catch (RestClientException e) {
			throw new KratosUnavailableException("cannot revoke sessions in Kratos", e);
		}
	}

	private static IdentityRef ref(JsonNode identity) {
		String role = identity.path("metadata_public").path("role").asString("");
		return new IdentityRef(UUID.fromString(identity.path("id").asString("")),
				identity.path("traits").path("email").asString(""), role.isBlank() ? null : role);
	}

}
