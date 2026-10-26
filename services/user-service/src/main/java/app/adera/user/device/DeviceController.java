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

	/** Removing a device that is not there is not an error ({@code removed: 0}). */
	@DeleteMapping
	public Map<String, Long> remove(@RequestHeader("X-User-Id") UUID userId,
			@Valid @RequestBody DeviceDtos.RemoveRequest request) {
		return Map.of("removed", service.remove(userId, request));
	}

}
