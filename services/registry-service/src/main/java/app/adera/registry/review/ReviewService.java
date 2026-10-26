package app.adera.registry.review;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import app.adera.registry.app.MiniApp;
import app.adera.registry.app.MiniAppRepository;
import app.adera.registry.audit.Actor;
import app.adera.registry.audit.AuditService;
import app.adera.registry.common.Errors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

	static final Set<String> REASONS = Set.of("SPAM", "SCAM", "INAPPROPRIATE", "BROKEN", "OTHER");

	private final AppReviewRepository reviews;
	private final AppReportRepository reports;
	private final MiniAppRepository apps;
	private final AuditService audit;
	private final Clock clock;

	public ReviewService(AppReviewRepository reviews, AppReportRepository reports, MiniAppRepository apps,
			AuditService audit, Clock clock) {
		this.reviews = reviews;
		this.reports = reports;
		this.apps = apps;
		this.audit = audit;
		this.clock = clock;
	}

	private MiniApp liveApp(UUID appId) {
		return apps.findById(appId).filter(a -> a.getStatus() == MiniApp.Status.LIVE).orElseThrow(() -> new Errors.NotFound("mini app"));
	}

	/** One review per user per app; sending again edits it. */
	@Transactional
	public AppReview upsert(UUID appId, UUID userId, Map<String, Object> body) {
		liveApp(appId);
		Map<String, String> errors = new java.util.LinkedHashMap<>();
		for (String k : body.keySet()) {
			if (!k.equals("rating") && !k.equals("comment")) {
				errors.put(k, "unknown field");
			}
		}
		Object r = body.get("rating");
		if (!(r instanceof Integer rating) || rating < 1 || rating > 5) {
			errors.put("rating", "must be a whole number from 1 to 5");
		}
		String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment")).strip();
		if (comment != null && comment.length() > 2000) {
			errors.put("comment", "max 2000 characters");
		}
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		int stars = (Integer) r;
		Instant now = clock.instant();
		AppReview review = reviews.findByAppIdAndUserId(appId, userId).orElse(null);
		String text = comment == null || comment.isEmpty() ? null : comment;
		if (review == null) {
			review = reviews.save(new AppReview(appId, userId, stars, text, now));
		}
		else {
			review.update(stars, text, now);
		}
		reviews.flush();
		apps.refreshRating(appId);
		return review;
	}

	@Transactional
	public void delete(UUID appId, UUID userId) {
		reviews.findByAppIdAndUserId(appId, userId).ifPresent(reviews::delete);
		reviews.flush();
		apps.refreshRating(appId);
	}

	@Transactional(readOnly = true)
	public AppReview mine(UUID appId, UUID userId) {
		return reviews.findByAppIdAndUserId(appId, userId).orElseThrow(() -> new Errors.NotFound("review"));
	}

	@Transactional(readOnly = true)
	public List<AppReview> visible(UUID appId, int page, int size) {
		liveApp(appId);
		return reviews.findByAppIdAndStatusOrderByCreatedAtDesc(appId, "VISIBLE", PageRequest.of(Math.max(0, page), clamp(size)));
	}

	@Transactional(readOnly = true)
	public List<AppReview> all(UUID appId, int page, int size) {
		return reviews.findByAppIdOrderByCreatedAtDesc(appId, PageRequest.of(Math.max(0, page), clamp(size)));
	}

	@Transactional
	public AppReview moderate(UUID reviewId, String status, Actor actor) {
		AppReview review = reviews.findById(reviewId).orElseThrow(() -> new Errors.NotFound("review"));
		String s = String.valueOf(status).toUpperCase(Locale.ROOT);
		if (!s.equals("VISIBLE") && !s.equals("HIDDEN")) {
			throw new Errors.ValidationFailed("status", "must be VISIBLE or HIDDEN");
		}
		review.setStatus(s, clock.instant());
		reviews.flush();
		apps.refreshRating(review.getAppId());
		audit.record(actor, "review.moderate", "app_review", reviewId.toString(), Map.of("status", s, "appId", review.getAppId().toString()));
		return review;
	}

	/** Reporting the same app again while an earlier report is still open returns that report (no pile-ups). */
	@Transactional
	public ReportResult report(UUID appId, UUID userId, Map<String, Object> body) {
		liveApp(appId);
		String reason = String.valueOf(body.get("reason")).toUpperCase(Locale.ROOT);
		if (!REASONS.contains(reason)) {
			throw new Errors.ValidationFailed("reason", "must be one of SPAM, SCAM, INAPPROPRIATE, BROKEN, OTHER");
		}
		String details = body.get("details") == null ? null : String.valueOf(body.get("details")).strip();
		if (details != null && details.length() > 2000) {
			throw new Errors.ValidationFailed("details", "max 2000 characters");
		}
		AppReport existing = reports.findFirstByAppIdAndUserIdAndStatus(appId, userId, "OPEN").orElse(null);
		if (existing != null) {
			return new ReportResult(existing, true);
		}
		return new ReportResult(reports.save(new AppReport(appId, userId, reason, details == null || details.isEmpty() ? null : details, clock.instant())), false);
	}

	/** The report a user's request ended up with; {@code alreadyReported} when an earlier OPEN report of theirs was returned instead. */
	public record ReportResult(AppReport report, boolean alreadyReported) {
	}

	@Transactional(readOnly = true)
	public List<AppReport> reports(String status, int page, int size) {
		PageRequest p = PageRequest.of(Math.max(0, page), clamp(size));
		return status == null || status.isBlank() ? reports.findAllByOrderByCreatedAtDesc(p)
				: reports.findByStatusOrderByCreatedAtDesc(status.toUpperCase(Locale.ROOT), p);
	}

	@Transactional
	public AppReport handleReport(UUID reportId, String status, Actor actor) {
		AppReport report = reports.findById(reportId).orElseThrow(() -> new Errors.NotFound("report"));
		String s = String.valueOf(status).toUpperCase(Locale.ROOT);
		if (!Set.of("OPEN", "REVIEWED", "DISMISSED").contains(s)) {
			throw new Errors.ValidationFailed("status", "must be OPEN, REVIEWED or DISMISSED");
		}
		report.handle(s, actor == null ? null : actor.id(), clock.instant());
		audit.record(actor, "report.handle", "app_report", reportId.toString(), Map.of("status", s, "appId", report.getAppId().toString()));
		return report;
	}

	private static int clamp(int size) {
		return Math.max(1, Math.min(size, 100));
	}

}
