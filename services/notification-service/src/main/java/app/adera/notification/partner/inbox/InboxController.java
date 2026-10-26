package app.adera.notification.partner.inbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.notification.partner.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's in-app notifications (the bell menu). The user id comes from the gateway-verified header. */
@RestController
@RequestMapping("/api/v1/me/notifications")
public class InboxController {

	private final JdbcClient jdbc;

	public InboxController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping
	public List<Map<String, Object>> list(@RequestHeader("X-User-Id") UUID userId, @RequestParam(defaultValue = "false") boolean unread,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		int limit = Math.max(1, Math.min(size, 100));
		return jdbc.sql("""
				SELECT id, app_id AS "appId", title, body, data::text AS data, created_at AS "createdAt", read_at AS "readAt"
				FROM inbox_item WHERE user_id = :u AND (:unread = FALSE OR read_at IS NULL)
				ORDER BY created_at DESC LIMIT :limit OFFSET :offset""")
			.param("u", userId).param("unread", unread).param("limit", limit).param("offset", Math.max(0, page) * limit)
			.query().listOfRows();
	}

	@GetMapping("/unread-count")
	public Map<String, Long> unreadCount(@RequestHeader("X-User-Id") UUID userId) {
		return Map.of("count", jdbc.sql("SELECT COUNT(*) FROM inbox_item WHERE user_id = :u AND read_at IS NULL").param("u", userId).query(Long.class).single());
	}

	/** Idempotent. You can only mark your own items. */
	@PutMapping("/{id}/read")
	public Map<String, Object> markRead(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID id) {
		int owned = jdbc.sql("SELECT COUNT(*) FROM inbox_item WHERE id = :id AND user_id = :u").param("id", id).param("u", userId).query(Integer.class).single();
		if (owned == 0) {
			throw new ApiException(404, "not_found");
		}
		jdbc.sql("UPDATE inbox_item SET read_at = COALESCE(read_at, now()) WHERE id = :id AND user_id = :u").param("id", id).param("u", userId).update();
		return Map.of("id", id, "read", true);
	}

	@PutMapping("/read-all")
	public Map<String, Integer> markAllRead(@RequestHeader("X-User-Id") UUID userId) {
		return Map.of("markedRead", jdbc.sql("UPDATE inbox_item SET read_at = now() WHERE user_id = :u AND read_at IS NULL").param("u", userId).update());
	}

}
