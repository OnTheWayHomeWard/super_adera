package app.adera.notification.config;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads this service's API keys (Twilio, Resend, ...) from Infisical at startup, so they never sit in .env,
 * the image, or compose. Runs before the application context exists.
 *
 * <p>Activated only when INFISICAL_TOKEN and INFISICAL_PROJECT_ID are set. Secrets are added as the LOWEST
 * priority property source, so a real environment variable always wins (handy for local overrides).
 * A failure here is logged but not fatal: a service that does not need the secrets (dev providers) still starts,
 * and a real provider without its secrets fails fast with a clear message.
 */
public class InfisicalEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String SOURCE_NAME = "infisical";

	/** Deferred: this runs before logging is initialised, so messages are replayed once it is. */
	private final Log log;

	public InfisicalEnvironmentPostProcessor(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(InfisicalEnvironmentPostProcessor.class);
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String token = environment.getProperty("INFISICAL_TOKEN", "");
		String projectId = environment.getProperty("INFISICAL_PROJECT_ID", "");
		String domain = environment.getProperty("INFISICAL_DOMAIN", "");
		if (token.isBlank() || projectId.isBlank() || domain.isBlank()) {
			log.info("Infisical not configured (INFISICAL_TOKEN / INFISICAL_PROJECT_ID / INFISICAL_DOMAIN): skipping secret load");
			return;
		}
		String envName = environment.getProperty("INFISICAL_ENV", "dev");
		String path = environment.getProperty("INFISICAL_PATH", "/notification");
		// Infisical often starts after us (it is slow to boot): keep trying for about a minute before giving up.
		int attempts = Integer.parseInt(environment.getProperty("INFISICAL_LOAD_ATTEMPTS", "12"));
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				Map<String, Object> secrets = fetch(domain, token, projectId, envName, path);
				environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, secrets));
				log.info("Loaded " + secrets.size() + " secret(s) from Infisical (" + envName + " " + path + ")");
				return;
			}
			catch (Exception e) {
				// Message only: never log the token or any secret value.
				boolean last = attempt == attempts;
				log.warn("Could not load secrets from Infisical (" + path + ") attempt " + attempt + "/" + attempts + ": " + e);
				if (last) {
					return;
				}
				try {
					Thread.sleep(5000);
				}
				catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	static Map<String, Object> fetch(String domain, String token, String projectId, String envName, String path)
			throws Exception {
		String base = domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
		String query = "projectId=" + enc(projectId) + "&environment=" + enc(envName) + "&secretPath=" + enc(path)
				+ "&expandSecretReferences=true";
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v4/secrets?" + query))
			.timeout(Duration.ofSeconds(5))
			.header("Authorization", "Bearer " + token)
			.GET()
			.build();
		// HTTP/1.1 on purpose: Java's default h2c upgrade attempt over plain HTTP makes Infisical answer 400.
		HttpClient client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(3))
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Infisical answered HTTP " + response.statusCode());
		}
		return parse(response.body());
	}

	static Map<String, Object> parse(String json) {
		JsonNode root = JsonMapper.builder().build().readTree(json);
		Map<String, Object> out = new LinkedHashMap<>();
		for (JsonNode secret : root.path("secrets")) {
			String key = secret.path("secretKey").asString("");
			if (!key.isEmpty()) {
				out.put(key, secret.path("secretValue").asString(""));
			}
		}
		return out;
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

}
