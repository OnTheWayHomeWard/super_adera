package app.adera.registry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "adera.registry")
public record RegistryProperties(
		@DefaultValue("") String gatewaySecret,
		@DefaultValue("") String internalSecret,
		@DefaultValue("true") boolean requireAal2,
		@DefaultValue("") String bootstrapSuperAdminEmail,
		@DefaultValue("dev") String environment,
		@DefaultValue Kratos kratos,
		@DefaultValue Storage storage,
		@DefaultValue Logo logo,
		@DefaultValue Banner banner) {

	public record Kratos(
			@DefaultValue("http://localhost:4433") String publicUrl,
			@DefaultValue("http://localhost:4434") String adminUrl) {
	}

	public record Storage(
			@DefaultValue("http://localhost:9000") String endpoint,
			@DefaultValue("http://localhost:9000") String publicEndpoint,
			@DefaultValue("adera-media") String bucket,
			@DefaultValue("") String accessKey,
			@DefaultValue("") String secretKey,
			@DefaultValue("us-east-1") String region,
			@DefaultValue("60") int presignMinutes) {
	}

	public record Logo(
			@DefaultValue("2097152") int maxBytes,
			@DefaultValue("512") int maxPx) {
	}

	/**
	 * @param externalDomains comma-separated domains an EXTERNAL banner may link to (each with its subdomains); empty = none
	 * @param maxBytes the largest image upload accepted
	 */
	public record Banner(
			@DefaultValue("") String externalDomains,
			@DefaultValue("4194304") int maxBytes) {
	}

}
