package app.adera.notification.partner.template;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** How a template looks in the API (admin and partner). {@code usableNow} says whether a partner can send it right now. */
public record TemplateView(UUID id, UUID appId, String code, String kind, String category, List<Map<String, Object>> params,
		Map<String, Map<String, String>> content, boolean active, Instant validFrom, Instant expiresAt, int version, boolean usableNow,
		String unusableReason, Instant createdAt, Instant updatedAt) {

	public static TemplateView of(Template t, Clock clock) {
		Instant now = clock.instant();
		return new TemplateView(t.getId(), t.getAppId(), t.getCode(), t.getKind(), t.getCategory(), t.getParams(), t.getContent(),
				t.isActive(), t.getValidFrom(), t.getExpiresAt(), t.getVersion(), t.usableAt(now), t.unusableReason(now),
				t.getCreatedAt(), t.getUpdatedAt());
	}

}
