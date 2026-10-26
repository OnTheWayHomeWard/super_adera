package app.adera.registry.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API shapes for mini apps (admin and public). */
public final class AppViews {

	private AppViews() {
	}

	public record Publisher(String name, String legalName, String supportEmail, String supportPhone, String website) {
	}

	public record AppView(UUID id, String slug, Map<String, String> name, Map<String, String> description, UUID logoImageId,
			String logoUrl, String brandColor, List<String> categories, List<String> markets, Publisher publisher,
			String entryUrl, List<String> allowedOrigins, String status, int priority, BigDecimal ratingAvg, int ratingCount,
			Map<String, Object> extra, Instant createdAt, Instant updatedAt) {
	}

	public record KeyView(String keyId, String environment, String algorithm, String fingerprint, String label, String status,
			Instant expiresAt, Instant createdAt, Instant revokedAt) {
	}

	public record AppDetail(AppView app, List<AppConfigRepository.CapabilityRow> capabilities,
			AppConfigRepository.PaymentRow payment, List<AppConfigRepository.LimitRow> notificationLimits,
			List<AppConfigRepository.EnvironmentRow> environments, List<KeyView> keys) {
	}

	/** What a signed-in user may see about a LIVE app: no internals, only what it is and what it may access. */
	public record Permission(String capability, String description, Map<String, Object> reason) {
	}

	public record PublicApp(UUID id, String slug, Map<String, String> name, Map<String, String> description, String logoUrl,
			String brandColor, List<String> categories, List<String> markets, Publisher publisher, String entryUrl,
			BigDecimal ratingAvg, int ratingCount, List<Permission> permissions) {
	}

}
