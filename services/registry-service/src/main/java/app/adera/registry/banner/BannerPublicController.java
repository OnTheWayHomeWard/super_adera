package app.adera.registry.banner;

import java.time.Duration;
import java.util.List;

import app.adera.registry.common.Errors;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The banners the super app shows right now. Public (no signed-in user, so the home screen can show them before login),
 * reached only through the gateway. Answers may be cached for 60 seconds.
 */
@RestController
@RequestMapping("/api/v1/banners")
public class BannerPublicController {

	static final int DEFAULT_LIMIT = 10;
	static final int MAX_LIMIT = 50;
	static final CacheControl CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

	private final BannerService service;

	public BannerPublicController(BannerService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<List<BannerViews.PublicBanner>> list(@RequestParam(required = false) String placement,
			@RequestParam(required = false) String category, @RequestParam(required = false) String limit) {
		Banner.Placement p = filter(Banner.Placement.class, "placement", placement);
		Banner.Category c = filter(Banner.Category.class, "category", category);
		int n = DEFAULT_LIMIT;
		if (limit != null && !limit.isBlank()) {
			try {
				n = Integer.parseInt(limit.trim());
			}
			catch (NumberFormatException e) {
				n = -1;
			}
			if (n < 1 || n > MAX_LIMIT) {
				throw new Errors.ValidationFailed("limit", "must be a whole number from 1 to " + MAX_LIMIT);
			}
		}
		return ResponseEntity.ok().cacheControl(CACHE).body(service.visibleNow(p, c, n));
	}

	private static <E extends Enum<E>> E filter(Class<E> type, String name, String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		E value = BannerPatch.parseEnum(type, raw);
		if (value == null) {
			throw new Errors.ValidationFailed(name, "must be " + BannerPatch.choices(type));
		}
		return value;
	}

}
