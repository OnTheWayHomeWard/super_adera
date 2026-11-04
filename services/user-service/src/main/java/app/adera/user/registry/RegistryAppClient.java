package app.adera.user.registry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import app.adera.user.admin.ApiException;
import app.adera.user.config.UserProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks registry-service whether a mini app exists (GET /internal/apps/{id}). Known apps are remembered for a minute, so
 * the check costs one extra call per app and minute at most; unknown ids are never remembered (they are not worth a
 * cache slot and must notice a new app at once).
 */
@Component
public class RegistryAppClient {

	/** A capability granted to the app, as the permission dialog needs it. {@code purpose} maps language to the approved sentence. */
	public record GrantedPermission(String capability, String label, Map<String, String> purpose, String defaultPurpose) {
	}

	/** The app as the consent endpoints see it, read fresh (no cache: a removed capability or a pause must bite at once). */
	public record AppInfo(UUID appId, String slug, Map<String, String> name, String status, String entryUrl, List<GrantedPermission> permissions) {

		public AppInfo(UUID appId, String slug, Map<String, String> name, String status, List<GrantedPermission> permissions) {
			this(appId, slug, name, status, null, permissions);
		}

	}

	private static final Duration TTL = Duration.ofSeconds(60);

	private record Cached(String status, Instant until) {
	}

	private final RestClient registry;
	private final String internalSecret;
	private final Clock clock;
	private final Map<UUID, Cached> known = new ConcurrentHashMap<>();

	public RegistryAppClient(UserProperties props, RestClient.Builder builder, Clock clock) {
		java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(Duration.ofSeconds(3));
		this.registry = builder.clone().baseUrl(props.registry().url()).requestFactory(factory).build();
		this.internalSecret = props.internalSecret();
		this.clock = clock;
	}

	/**
	 * @return the app's status (DRAFT, LIVE, PAUSED, KILLED), empty when the app does not exist
	 * @throws ApiException 503 service_unavailable when registry-service cannot answer
	 */
	@SuppressWarnings("unchecked")
	public Optional<String> status(UUID appId) {
		Cached hit = known.get(appId);
		if (hit != null && hit.until().isAfter(clock.instant())) {
			return Optional.of(hit.status());
		}
		try {
			Map<String, Object> body = registry.get().uri("/internal/apps/{id}", appId)
				.headers(h -> {
					if (!internalSecret.isEmpty()) {
						h.set("X-Internal-Secret", internalSecret);
					}
				})
				.retrieve().body(Map.class);
			String status = String.valueOf(body == null ? null : body.get("status"));
			known.put(appId, new Cached(status, clock.instant().plus(TTL)));
			return Optional.of(status);
		}
		catch (HttpClientErrorException.NotFound e) {
			known.remove(appId);
			return Optional.empty();
		}
		catch (RestClientException e) {
			throw new ApiException(503, "service_unavailable");
		}
	}

	/** The app by id or slug with its granted capabilities. Empty when it does not exist. Never cached. */
	public Optional<AppInfo> app(String idOrSlug) {
		try {
			return Optional.ofNullable(registry.get().uri("/internal/apps/{id}/permissions", idOrSlug).headers(this::secret).retrieve().body(AppInfo.class));
		}
		catch (HttpClientErrorException.NotFound e) {
			return Optional.empty();
		}
		catch (RestClientException e) {
			throw new ApiException(503, "service_unavailable");
		}
	}

	/** Several apps at once (unknown ids are simply missing from the answer). */
	public List<AppInfo> apps(java.util.Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		try {
			String joined = ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
			AppInfo[] body = registry.get().uri(u -> u.path("/internal/apps").queryParam("ids", joined).build()).headers(this::secret).retrieve().body(AppInfo[].class);
			return body == null ? List.of() : List.of(body);
		}
		catch (RestClientException e) {
			throw new ApiException(503, "service_unavailable");
		}
	}

	private void secret(org.springframework.http.HttpHeaders h) {
		if (!internalSecret.isEmpty()) {
			h.set("X-Internal-Secret", internalSecret);
		}
	}

}
