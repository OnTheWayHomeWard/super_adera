package app.adera.notification.partner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "adera.partner")
public record PartnerProperties(
		@DefaultValue("dev") String environment,
		@DefaultValue("true") boolean workersEnabled,
		@DefaultValue("") String gatewaySecret,
		@DefaultValue("") String internalSecret,
		@DefaultValue("http://localhost:8087") String registryUrl,
		@DefaultValue("http://localhost:8086") String userServiceUrl,
		@DefaultValue("") String signingKeyId,
		@DefaultValue("") String signingPrivateKeyB64,
		@DefaultValue("5") int maxSkewMinutes,
		@DefaultValue("false") boolean allowPrivateCallbacks,
		@DefaultValue("fake") String fcmProvider,
		@DefaultValue("") String firebaseServiceAccountB64,
		@DefaultValue("") String firebaseCredentialsPath) {

	/** The one environment this deployment is (ADERA_ENV: dev or prod), as DEV or PROD. Anything else stops the service from starting. */
	public PartnerProperties {
		String e = environment == null ? "" : environment.trim().toUpperCase(java.util.Locale.ROOT);
		if (!e.equals("DEV") && !e.equals("PROD")) {
			throw new IllegalStateException("ADERA_ENV must be dev or prod, got '" + environment + "'");
		}
		environment = e;
	}

	public boolean isDev() {
		return "DEV".equals(environment);
	}

}
