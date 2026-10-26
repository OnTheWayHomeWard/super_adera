package app.adera.notification.partner.delivery;

import java.util.Map;

import app.adera.notification.partner.ApiException;
import app.adera.notification.partner.auth.PartnerContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The partner-facing API. Every call is signed by the partner and every answer is signed by us (see Signatures). */
@RestController
@RequestMapping("/v1/notifications")
public class PartnerNotificationController {

	private final NotificationAcceptor acceptor;
	private final NotificationRepository repository;

	public PartnerNotificationController(NotificationAcceptor acceptor, NotificationRepository repository) {
		this.acceptor = acceptor;
		this.repository = repository;
	}

	/**
	 * Ask us to reach a user. Answers 202 QUEUED (we will deliver and report back), 200 for a repeat of an earlier request or
	 * a user who muted your app, or an error: 400 invalid, 403 not allowed, 409 externalId reused for a different request,
	 * 422 template problem or user unreachable, 429 over your limits, 503 try again.
	 */
	@PostMapping
	public ResponseEntity<Map<String, Object>> send(@RequestAttribute(PartnerContext.ATTR) PartnerContext ctx, @RequestBody Map<String, Object> body) {
		NotificationAcceptor.Result result = acceptor.accept(ctx, body);
		return ResponseEntity.status(result.status()).body(result.body());
	}

	/** Where is my message? Look it up by your own externalId. */
	@GetMapping("/{externalId}")
	public Map<String, Object> status(@RequestAttribute(PartnerContext.ATTR) PartnerContext ctx, @PathVariable String externalId) {
		Notification n = repository.findByAppIdAndEnvironmentAndExternalId(ctx.appId(), ctx.environment(), externalId)
			.orElseThrow(() -> new ApiException(404, "not_found"));
		Map<String, Object> view = new java.util.LinkedHashMap<>(NotificationAcceptor.view(n, false));
		view.put("attempts", n.getAttempts());
		return view;
	}

}
