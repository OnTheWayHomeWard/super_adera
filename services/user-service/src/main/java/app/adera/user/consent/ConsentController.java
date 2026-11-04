package app.adera.user.consent;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's permissions per mini app. {app} is the app's id or its slug. See docs section 14. */
@RestController
public class ConsentController {

	private final ConsentService service;

	public ConsentController(ConsentService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/me/permissions")
	public List<ConsentService.AppPermissions> overview(@RequestHeader("X-User-Id") UUID userId) {
		return service.overview(userId);
	}

	@GetMapping("/api/v1/me/apps/{app}/permissions")
	public List<ConsentService.PermissionView> list(@RequestHeader("X-User-Id") UUID userId, @PathVariable String app) {
		return service.list(userId, app);
	}

	@PostMapping("/api/v1/me/apps/{app}/permissions/{capability}/request")
	public ConsentService.RequestResult request(@RequestHeader("X-User-Id") UUID userId, @PathVariable String app, @PathVariable String capability) {
		return service.request(userId, app, capability);
	}

	@PutMapping("/api/v1/me/apps/{app}/permissions/{capability}/answer")
	public ConsentService.Decision answer(@RequestHeader("X-User-Id") UUID userId, @PathVariable String app, @PathVariable String capability,
			@RequestBody Map<String, Object> body) {
		return service.answer(userId, app, capability, body);
	}

	@PutMapping("/api/v1/me/apps/{app}/permissions/{capability}")
	public ConsentService.MenuResult menu(@RequestHeader("X-User-Id") UUID userId, @PathVariable String app, @PathVariable String capability,
			@RequestBody Map<String, Object> body) {
		return service.menu(userId, app, capability, body);
	}

	@DeleteMapping("/api/v1/me/apps/{app}/permissions")
	public Map<String, Object> disconnect(@RequestHeader("X-User-Id") UUID userId, @PathVariable String app) {
		return Map.of("disconnected", true, "changed", service.disconnect(userId, app));
	}

}
