package app.adera.registry.banner;

import java.time.Instant;

/**
 * What the backoffice shows and what decides whether users see a banner. Computed from the stored status and the clock on
 * every read; never stored, so nothing has to run when a banner starts or ends.
 */
public enum BannerState {

	DRAFT, PAUSED, SCHEDULED, LIVE, ENDED;

	public static BannerState of(Banner.Status status, Instant startsAt, Instant endsAt, Instant now) {
		return switch (status) {
			case DRAFT -> DRAFT;
			case PAUSED -> PAUSED;
			case PUBLISHED -> now.isBefore(startsAt) ? SCHEDULED : (endsAt != null && !now.isBefore(endsAt)) ? ENDED : LIVE;
		};
	}

	public static BannerState of(Banner b, Instant now) {
		return of(b.getStatus(), b.getStartsAt(), b.getEndsAt(), now);
	}

}
