package app.adera.registry.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.auth.Role;
import app.adera.registry.common.Errors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api/v1/audit")
public class AuditController {

	private final AuditQuery query;

	public AuditController(AuditQuery query) {
		this.query = query;
	}

	/**
	 * Newest first. Filters: actor (staff id), action (starts-with, e.g. "app." or "app.status"), target (an app id,
	 * staff id, key id... or an app id: then every entry ABOUT that app is returned, including its keys, reviews, reports and
	 * templates), appId (strictly the entries about one app), targetType, from (inclusive) and to (exclusive) as ISO instants. Admin and above.
	 */
	@GetMapping
	public org.springframework.http.ResponseEntity<List<Map<String, Object>>> latest(@RequestHeader("X-Staff-Role") Role role, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) String actor,
			@RequestParam(required = false) String action, @RequestParam(required = false) String target,
			@RequestParam(required = false) String targetType, @RequestParam(required = false) String appId, @RequestParam(required = false) String from,
			@RequestParam(required = false) String to) {
		role.require(Role.ADMIN);
		Map<String, String> errors = new java.util.LinkedHashMap<>();
		UUID actorId = null;
		if (blank(actor) == null) {
			actor = null;
		}
		else {
			try {
				actorId = UUID.fromString(actor.trim());
			}
			catch (IllegalArgumentException e) {
				errors.put("actor", "must be a staff id (uuid)");
			}
		}
		UUID app = null;
		if (blank(appId) != null) {
			try {
				app = UUID.fromString(appId.trim());
			}
			catch (IllegalArgumentException e) {
				errors.put("appId", "must be an app id (uuid)");
			}
		}
		Instant fromAt = instant(from, "from", errors);
		Instant toAt = instant(to, "to", errors);
		if (!errors.isEmpty()) {
			throw new Errors.ValidationFailed(errors);
		}
		AuditQuery.Filter filter = new AuditQuery.Filter(actorId, blank(action), blank(target), blank(targetType), app, fromAt, toAt);
		return org.springframework.http.ResponseEntity.ok().header("X-Total-Count", String.valueOf(query.count(filter)))
			.body(query.search(filter, limit, offset));
	}

	private static String blank(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static Instant instant(String raw, String field, Map<String, String> errors) {
		if (blank(raw) == null) {
			return null;
		}
		try {
			return Instant.parse(raw.trim());
		}
		catch (DateTimeParseException e) {
			errors.put(field, "must be an ISO-8601 instant like 2026-10-01T00:00:00Z");
			return null;
		}
	}

}
