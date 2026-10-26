package app.adera.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "adera.user")
public record UserProperties(
		@DefaultValue("") String gatewaySecret,
		@DefaultValue("") String internalSecret,
		@DefaultValue("") String aliasHmacSecret,
		@DefaultValue Kratos kratos,
		@DefaultValue Registry registry,
		@DefaultValue Storage storage,
		@DefaultValue Avatar avatar,
		@DefaultValue("16384") int extraMaxBytes) {

	public record Kratos(
			@DefaultValue("http://localhost:4433") String publicUrl,
			@DefaultValue("http://localhost:4434") String adminUrl) {
	}

	/** Where the central audit log lives (registry-service, internal network). */
	public record Registry(
			@DefaultValue("http://localhost:8087") String url) {
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

	public record Avatar(
			@DefaultValue("5242880") int maxBytes,
			@DefaultValue("512") int sizePx) {
	}

}
