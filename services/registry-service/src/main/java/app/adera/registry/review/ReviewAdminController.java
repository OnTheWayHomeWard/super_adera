package app.adera.registry.review;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.audit.Actor;
import app.adera.registry.auth.Role;
import app.adera.registry.common.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Moderation: see every review (including hidden ones), hide abusive ones, and work through user reports. */
@RestController
@RequestMapping("/admin/api/v1")
public class ReviewAdminController {

	private final ReviewService service;
	private final ModerationQuery query;

	public ReviewAdminController(ReviewService service, ModerationQuery query) {
		this.service = service;
		this.query = query;
	}

	/** Every review of every app, newest first (hidden ones included unless filtered). */
	@GetMapping("/reviews")
	public org.springframework.http.ResponseEntity<List<Map<String, Object>>> allReviews(@RequestParam(required = false) String status, @RequestParam(required = false) UUID appId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		String s = status == null || status.isBlank() ? null : status.toUpperCase(java.util.Locale.ROOT);
		if (s != null && !s.equals("VISIBLE") && !s.equals("HIDDEN")) {
			throw new app.adera.registry.common.Errors.ValidationFailed("status", "must be VISIBLE or HIDDEN");
		}
		return org.springframework.http.ResponseEntity.ok().header("X-Total-Count", String.valueOf(query.countReviews(appId, s)))
			.body(query.reviews(appId, s, Math.max(0, page), Math.max(1, Math.min(size, 100))));
	}

	@GetMapping("/apps/{appId}/reviews")
	public List<AppReview> reviews(@PathVariable UUID appId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return service.all(appId, page, size);
	}

	@PatchMapping("/reviews/{reviewId}")
	public AppReview moderate(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role, @PathVariable UUID reviewId,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return service.moderate(reviewId, String.valueOf(body.get("status")), body.get("reason"), new Actor(id, role, ClientIp.of(request)));
	}

	@GetMapping("/reports")
	public org.springframework.http.ResponseEntity<List<Map<String, Object>>> reports(@RequestParam(required = false) String status, @RequestParam(required = false) UUID appId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		String s = status == null || status.isBlank() ? null : status.toUpperCase(java.util.Locale.ROOT);
		return org.springframework.http.ResponseEntity.ok().header("X-Total-Count", String.valueOf(query.countReports(appId, s)))
			.body(query.reports(appId, s, Math.max(0, page), Math.max(1, Math.min(size, 100))));
	}

	@PatchMapping("/reports/{reportId}")
	public AppReport handle(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role, @PathVariable UUID reportId,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return service.handleReport(reportId, String.valueOf(body.get("status")), body.get("reason"), new Actor(id, role, ClientIp.of(request)));
	}

}
