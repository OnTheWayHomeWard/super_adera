package app.adera.registry.app;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.common.Errors;
import app.adera.registry.review.AppReview;
import app.adera.registry.review.ReviewService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** What a signed-in user sees: the LIVE apps (ordered by priority), their details, ratings, and the report button. */
@RestController
@RequestMapping("/api/v1/apps")
public class AppPublicController {

	/** No user id and no name: reviews are shown anonymously. */
	public record ReviewView(UUID id, int rating, String comment, Instant createdAt, Instant updatedAt) {

		static ReviewView of(AppReview r) {
			return new ReviewView(r.getId(), r.getRating(), r.getComment(), r.getCreatedAt(), r.getUpdatedAt());
		}

	}

	/**
	 * The author's own view of their review: the same fields plus {@code status} (VISIBLE, or HIDDEN when staff removed it
	 * from the public list). {@code message} is only present while hidden and says that editing does not bring it back.
	 */
	@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
	public record MyReviewView(UUID id, int rating, String comment, String status, String message, Instant createdAt, Instant updatedAt) {

		static final String HIDDEN_MESSAGE = "Hidden by moderators: it is not shown to other users and does not count in the rating. Editing it does not restore it.";

		static MyReviewView of(AppReview r) {
			boolean hidden = "HIDDEN".equals(r.getStatus());
			return new MyReviewView(r.getId(), r.getRating(), r.getComment(), r.getStatus(), hidden ? HIDDEN_MESSAGE : null, r.getCreatedAt(), r.getUpdatedAt());
		}

	}

	private final AppService apps;
	private final ReviewService reviews;

	public AppPublicController(AppService apps, ReviewService reviews) {
		this.apps = apps;
		this.reviews = reviews;
	}

	@GetMapping
	public List<AppViews.PublicApp> list(@RequestParam(required = false) String market) {
		return apps.list(MiniApp.Status.LIVE).stream()
			.filter(a -> market == null || market.isBlank() || a.getMarkets().contains(market.toUpperCase()))
			.map(apps::publicView)
			.toList();
	}

	@GetMapping("/{idOrSlug}")
	public AppViews.PublicApp detail(@PathVariable String idOrSlug) {
		return apps.publicView(live(idOrSlug));
	}

	@GetMapping("/{idOrSlug}/reviews")
	public List<ReviewView> reviews(@PathVariable String idOrSlug, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return reviews.visible(live(idOrSlug).getId(), page, size).stream().map(ReviewView::of).toList();
	}

	@GetMapping("/{idOrSlug}/my-review")
	public MyReviewView myReview(@RequestHeader("X-User-Id") UUID userId, @PathVariable String idOrSlug) {
		return MyReviewView.of(reviews.mine(live(idOrSlug).getId(), userId));
	}

	/** Rate (1-5) and optionally comment. Sending again edits your review. */
	@PutMapping("/{idOrSlug}/my-review")
	public MyReviewView review(@RequestHeader("X-User-Id") UUID userId, @PathVariable String idOrSlug, @RequestBody Map<String, Object> body) {
		return MyReviewView.of(reviews.upsert(live(idOrSlug).getId(), userId, body));
	}

	@DeleteMapping("/{idOrSlug}/my-review")
	public Map<String, String> deleteReview(@RequestHeader("X-User-Id") UUID userId, @PathVariable String idOrSlug) {
		reviews.delete(live(idOrSlug).getId(), userId);
		return Map.of("status", "deleted");
	}

	/** The "report this app" option from the 3-dot menu. */
	@PostMapping("/{idOrSlug}/report")
	public Map<String, Object> report(@RequestHeader("X-User-Id") UUID userId, @PathVariable String idOrSlug, @RequestBody Map<String, Object> body) {
		ReviewService.ReportResult result = reviews.report(live(idOrSlug).getId(), userId, body);
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("status", "received");
		out.put("received", true);
		out.put("alreadyReported", result.alreadyReported());
		out.put("reportId", result.report().getId().toString());
		out.put("reason", result.report().getReason());
		out.put("createdAt", result.report().getCreatedAt());
		return out;
	}

	private MiniApp live(String idOrSlug) {
		MiniApp app = apps.getByIdOrSlug(idOrSlug);
		if (app.getStatus() != MiniApp.Status.LIVE) {
			throw new Errors.NotFound("mini app");
		}
		return app;
	}

}
