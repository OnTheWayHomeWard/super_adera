package app.adera.notification.partner.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.PartnerProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Writes to the central audit log, which lives in registry-service (POST /internal/audit). */
@Component
public class AuditClient {

	private final RestClient registry;
	private final String internalSecret;

	public AuditClient(PartnerProperties props, RestClient.Builder builder) {
		this.registry = builder.clone().baseUrl(props.registryUrl()).build();
		this.internalSecret = props.internalSecret();
	}

	/** @throws ApiException 503 audit_unavailable if the entry could not be written (callers must then undo their change) */
	public void record(String actorId, String actorRole, String action, String targetType, String targetId, Map<String, Object> meta, String ip) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("actorId", actorId);
		body.put("actorRole", actorRole);
		body.put("action", action);
		body.put("targetType", targetType);
		body.put("targetId", targetId);
		body.put("meta", meta);
		body.put("ip", ip);
		try {
			registry.post().uri("/internal/audit").contentType(MediaType.APPLICATION_JSON)
				.headers(h -> {
					if (!internalSecret.isEmpty()) {
						h.set("X-Internal-Secret", internalSecret);
					}
				})
				.body(body).retrieve().toBodilessEntity();
		}
		catch (RestClientException e) {
			throw new ApiException(503, "audit_unavailable");
		}
	}

}
