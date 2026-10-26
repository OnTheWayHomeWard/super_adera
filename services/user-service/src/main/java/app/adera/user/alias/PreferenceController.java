package app.adera.user.alias;

import java.util.Map;
import java.util.UUID;

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

	public PreferenceController(PreferenceService service) {
		this.service = service;
	}

	@GetMapping
	public PreferenceService.Preference get(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID appId) {
		return service.get(userId, appId);
	}

	/** e.g. {"muteSms": true}. Only the switches you send change. */
	@PutMapping
	public PreferenceService.Preference update(@RequestHeader("X-User-Id") UUID userId, @PathVariable UUID appId,
			@RequestBody Map<String, Object> body) {
		return service.update(userId, appId, body);
	}

}
