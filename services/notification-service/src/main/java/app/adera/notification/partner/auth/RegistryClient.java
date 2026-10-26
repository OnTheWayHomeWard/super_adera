package app.adera.notification.partner.auth;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.adera.notification.partner.PartnerProperties;
import app.adera.notification.partner.TtlCache;
import app.adera.notification.partner.UpstreamUnavailableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks registry-service who a key belongs to and what that app may do. Answers are cached briefly:
 * a revoked key or a killed app stops working within the cache time (about 15 seconds).
 */
@Component
public class RegistryClient {

	public record KeyInfo(String keyId, UUID appId, String environment, String algorithm, String publicKeyPem, boolean usable,
			Boolean activeHere, String deploymentEnvironment) {
	}

	public record Limit(String channel, Integer maxPerUserPerDay, Integer maxPerDay, Integer maxDistinctUsersPerDay, Long maxTotal) {
	}

	public record AppContext(UUID appId, String slug, String status, String environment, boolean usable, String denyReason,
			List<String> grantedCapabilities, List<Limit> notificationLimits, List<String> allowedCidrs) {

		public Optional<Limit> limit(String channel) {
			return notificationLimits.stream().filter(l -> l.channel().equals(channel)).findFirst();
		}

	}

	/** A key with its public half already parsed. */
	public record ParsedKey(KeyInfo info, PublicKey publicKey) {
	}

	private final RestClient client;
	private final String deployment;
	private final TtlCache<String, ParsedKey> keys = new TtlCache<>(Duration.ofSeconds(15));
	private final TtlCache<String, AppContext> contexts = new TtlCache<>(Duration.ofSeconds(15));

	public RegistryClient(PartnerProperties props, RestClient.Builder builder) {
		this.deployment = props.environment();
		this.client = builder.baseUrl(props.registryUrl()).defaultHeader("X-Internal-Secret", props.internalSecret()).build();
	}

	public Optional<ParsedKey> key(String keyId) {
		return keys.get(keyId, id -> {
			try {
				KeyInfo info = client.get().uri("/internal/partner-keys/{id}", id).exchange((req, res) -> {
					if (res.getStatusCode().value() == 404) {
						return null;
					}
					if (!res.getStatusCode().is2xxSuccessful()) {
						throw new UpstreamUnavailableException("registry answered " + res.getStatusCode().value(), null);
					}
					return res.bodyTo(KeyInfo.class);
				});
				return info == null ? Optional.<ParsedKey>empty() : Optional.of(new ParsedKey(info, parse(info.publicKeyPem())));
			}
			catch (RestClientException e) {
				throw new UpstreamUnavailableException("cannot reach registry-service", e);
			}
		});
	}

	/** The environment this deployment is (DEV or PROD): the only one the registry is ever asked about. */
	public String deployment() {
		return deployment;
	}

	/** What the app may do in THIS deployment's environment. */
	public Optional<AppContext> context(UUID appId) {
		String environment = deployment;
		return contexts.get(appId + "/" + environment, k -> {
			try {
				AppContext ctx = client.get().uri(u -> u.path("/internal/apps/{id}/context").queryParam("environment", environment).build(appId))
					.exchange((req, res) -> {
						if (res.getStatusCode().value() == 404) {
							return null;
						}
						if (!res.getStatusCode().is2xxSuccessful()) {
							throw new UpstreamUnavailableException("registry answered " + res.getStatusCode().value(), null);
						}
						return res.bodyTo(AppContext.class);
					});
				return Optional.ofNullable(ctx);
			}
			catch (RestClientException e) {
				throw new UpstreamUnavailableException("cannot reach registry-service", e);
			}
		});
	}

	public void clearCaches() {
		keys.clear();
		contexts.clear();
	}

	static PublicKey parse(String pem) {
		try {
			String body = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
			return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
		}
		catch (Exception e) {
			throw new IllegalStateException("registry returned an unreadable public key", e);
		}
	}

}
