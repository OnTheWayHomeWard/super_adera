package app.adera.registry.banner;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API shapes for banners (staff and public). */
public final class BannerViews {

	private BannerViews() {
	}

	/** One stored size of the image: a signed link (valid STORAGE_PRESIGN_MINUTES) and its pixel size. */
	public record Image(String url, int width, int height) {
	}

	/** The linked app as staff see it (with its status, which decides whether the banner can show). */
	public record AdminApp(UUID id, String slug, Map<String, String> name, String logoUrl, String status) {
	}

	/** The linked app as users see it. */
	public record PublicApp(UUID id, String slug, Map<String, String> name, String logoUrl) {
	}

	/**
	 * @param state the computed label (DRAFT, PAUSED, SCHEDULED, LIVE, ENDED)
	 * @param visible whether users get it right now: LIVE, with an image, and its linked app (if any) LIVE
	 */
	public record AdminBanner(UUID id, Map<String, String> title, Map<String, String> subtitle, String placement, String category,
			UUID appId, AdminApp app, String actionType, String target, UUID imageId, Map<String, Image> images, Instant startsAt,
			Instant endsAt, int priority, String status, String state, boolean visible, UUID createdBy, UUID updatedBy,
			Instant createdAt, Instant updatedAt) {
	}

	public record PublicBanner(UUID id, Map<String, String> title, Map<String, String> subtitle, String placement, String category,
			String actionType, String target, PublicApp app, Map<String, Image> images, int priority, Instant startsAt,
			Instant endsAt) {
	}

}
