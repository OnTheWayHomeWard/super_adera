package app.adera.user.auth;

import java.util.UUID;

import app.adera.user.device.DeviceDtos;
import app.adera.user.device.DeviceService;
import app.adera.user.profile.ValidationFailedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One call that fully signs a device out: removes its push token (so the next person on the phone does not receive
 * this user's notifications) and revokes the Kratos session behind the caller's bearer token.
 * Kratos has no logout webhook for native apps, which is why this lives here instead of being automatic.
 */
@RestController
@RequestMapping("/api/v1/me")
public class LogoutController {

	private final DeviceService devices;
	private final KratosClient kratos;

	public LogoutController(DeviceService devices, KratosClient kratos) {
		this.devices = devices;
		this.kratos = kratos;
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@RequestHeader("X-User-Id") UUID userId,
			@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestBody(required = false) DeviceDtos.RemoveRequest device) {
		String token = bearer(authorization);
		if (token == null) {
			throw new ValidationFailedException("Authorization", "logout needs the session token as 'Authorization: Bearer <token>'");
		}
		if (device != null && !device.isEmpty()) {
			devices.remove(userId, device);
		}
		kratos.logout(token);
		return ResponseEntity.noContent().build();
	}

	static String bearer(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}
		String token = authorization.substring(7).trim();
		return token.isEmpty() ? null : token;
	}

}
