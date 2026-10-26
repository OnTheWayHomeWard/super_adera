package app.adera.user.device;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/devices")
public class DeviceController {

	private final DeviceService service;

	public DeviceController(DeviceService service) {
		this.service = service;
	}

	/** Call right after login and whenever the FCM SDK rotates the token. Idempotent. */
	@PutMapping
	public DeviceDtos.Response register(@RequestHeader("X-User-Id") UUID userId,
			@Valid @RequestBody DeviceDtos.RegisterRequest request) {
		return DeviceDtos.Response.of(service.register(userId, request));
	}

	@GetMapping
	public List<DeviceDtos.Response> list(@RequestHeader("X-User-Id") UUID userId) {
		return service.list(userId).stream().map(DeviceDtos.Response::of).toList();
	}

	/**
	 * Removing a device that is not there is not an error ({@code removed: 0}). Name the device by {@code installationId}
	 * and/or {@code fcmToken}, in the query string (<code>?installationId=...</code>, no body needed) or in a JSON body;
	 * a value in the body wins over the same one in the query. Prefer installationId in the query: a token in a URL ends up
	 * in access logs.
	 */
	@DeleteMapping
	public Map<String, Long> remove(@RequestHeader("X-User-Id") UUID userId,
			@RequestParam(required = false) String installationId, @RequestParam(required = false) String fcmToken,
			@Valid @RequestBody(required = false) DeviceDtos.RemoveRequest body) {
		DeviceDtos.RemoveRequest request = DeviceDtos.RemoveRequest.merge(body, fcmToken, installationId);
		return Map.of("removed", service.remove(userId, request));
	}

}
