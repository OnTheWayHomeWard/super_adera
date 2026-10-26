package app.adera.user.alias;

import java.util.Map;
import java.util.UUID;

import app.adera.user.profile.NotFoundException;
import app.adera.user.registry.RegistryAppClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/apps/{appId}/preferences")
public class PreferenceController {

	private final PreferenceService service;
	private final RegistryAppClient registry;

	public PreferenceController(PreferenceService service, RegistryAppClient registry) {
		this.service = service;
		this.registry = registry;
	}

	/** Like every end-user endpoint, only a LIVE app counts as existing: anything else is 404 not_found. */
	private void requireLiveApp(UUID appId) {
		if (!registry.status(appId).filter("LIVE"::equals).isPresent()) {
			throw new NotFoundException("mini app");
		}
	}

	@GetMapping
	public PreferenceService.Preference get(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID appId) {
		requireLiveApp(appId);
		return service.get(userId, appId);
	}

	/** e.g. {"muteSms": true}. Only the switches you send change. */
	@PutMapping
	public PreferenceService.Preference update(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID appId,
			@RequestBody Map<String, Object> body) {
		requireLiveApp(appId);
		return service.update(userId, appId, body);
	}

}
