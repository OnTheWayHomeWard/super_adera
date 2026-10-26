package app.adera.notification.partner.auth;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Who is calling, established by {@link PartnerAuthFilter}: handed to controllers as a request attribute. */
public record PartnerContext(UUID appId, String environment, String keyId, String slug, Set<String> capabilities,
		List<RegistryClient.Limit> limits, String requestSignature) {

	public static final String ATTR = "adera.partner.context";

	public boolean isDev() {
		return "DEV".equals(environment);
	}

	public boolean can(String capability) {
		return capabilities.contains(capability);
	}

	public RegistryClient.Limit limitFor(String channel) {
		return limits.stream().filter(l -> l.channel().equals(channel)).findFirst().orElse(null);
	}

}
