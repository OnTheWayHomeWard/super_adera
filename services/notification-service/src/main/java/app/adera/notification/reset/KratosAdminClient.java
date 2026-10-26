package app.adera.notification.reset;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.partner.UpstreamUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** The few Kratos admin calls the phone password reset needs. The admin API is reachable on the docker network only. */
@Component
public class KratosAdminClient {

	/** @param schemaId "user" for app users; staff are "default" and are never reset here */
	public record Identity(UUID id, String schemaId, String state) {
	}

	private final RestClient client;

	public KratosAdminClient(@Value("${adera.notification.kratos.admin-url:http://localhost:4434}") String adminUrl, RestClient.Builder builder) {
		this.client = builder.clone().baseUrl(adminUrl).build();
	}

	/** For tests: a client over a prepared RestClient builder. */
	static KratosAdminClient forBuilder(String adminUrl, RestClient.Builder builder) {
		return new KratosAdminClient(adminUrl, builder);
	}

	/** The identity that has this exact phone (or email) as its sign-in identifier. */
	public Optional<Identity> findBySignInIdentifier(String identifier) {
		try {
			JsonNode list = client.get().uri(u -> u.path("/admin/identities").queryParam("credentials_identifier", "{identifier}").build(identifier))   // "{}" so a "+" is sent as %2B, not as a space
				.retrieve().body(JsonNode.class);
			if (list == null || !list.isArray() || list.isEmpty()) {
				return Optional.empty();
			}
			JsonNode i = list.get(0);
			return Optional.of(new Identity(UUID.fromString(i.path("id").asString()), i.path("schema_id").asString(), i.path("state").asString()));
		}
		catch (RestClientException e) {
			throw new UpstreamUnavailableException("cannot reach Kratos", e);
		}
	}

	/** Sets a new password (Kratos hashes it) and signs the account out everywhere. Traits and verified flags stay as they were. */
	public void resetPassword(UUID identityId, String newPassword) {
		try {
			JsonNode current = client.get().uri("/admin/identities/{id}", identityId).retrieve().body(JsonNode.class);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("schema_id", current.path("schema_id").asString());
			body.put("state", current.path("state").asString());
			body.put("traits", current.path("traits"));
			if (!current.path("metadata_public").isMissingNode() && !current.path("metadata_public").isNull()) {
				body.put("metadata_public", current.path("metadata_public"));
			}
			if (!current.path("metadata_admin").isMissingNode() && !current.path("metadata_admin").isNull()) {
				body.put("metadata_admin", current.path("metadata_admin"));
			}
			body.put("credentials", Map.of("password", Map.of("config", Map.of("password", newPassword))));
			client.put().uri("/admin/identities/{id}", identityId).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
			client.delete().uri("/admin/identities/{id}/sessions", identityId).retrieve().toBodilessEntity();
		}
		catch (RestClientException e) {
			throw new UpstreamUnavailableException("cannot reach Kratos", e);
		}
	}

}
