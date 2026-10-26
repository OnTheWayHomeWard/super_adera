package app.adera.user.auth;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the gateway's forward-auth plugin before it lets a request through. It asks Kratos who the session
 * belongs to and answers 200 with {@code X-User-Id} (the Kratos identity id), or 401.
 * Kratos' own whoami returns the identity in its body, not in headers, so this small translator is what lets the
 * gateway hand the user's id to downstream services.
 */
@RestController
public class VerifyController {

	private final KratosClient kratos;
	private final app.adera.user.profile.UserRepository users;

	public VerifyController(KratosClient kratos, app.adera.user.profile.UserRepository users) {
		this.kratos = kratos;
		this.users = users;
	}

	@GetMapping("/internal/auth/verify")
	public ResponseEntity<?> verify(@RequestHeader(value = "Authorization", required = false) String authorization,
			@RequestHeader(value = "Cookie", required = false) String cookie) {
		if (authorization == null && cookie == null) {
			return ResponseEntity.status(401).build();
		}
		// Unavailable Kratos throws and is mapped to 503 by the exception handler (not a misleading 401).
		UUID id = kratos.whoami(authorization, cookie).orElse(null);
		if (id == null) {
			return ResponseEntity.status(401).build();
		}
		// A suspended or deleted account cannot use the API even with a valid Kratos session (a user with no profile row yet is fine).
		var state = users.findStatusById(id).orElse(null);
		if (state != null && (state.getStatus() == app.adera.user.profile.AppUser.Status.DELETED || state.getDeletedAt() != null)) {
			return ResponseEntity.status(403).body(java.util.Map.of("error", "account_deleted"));
		}
		if (state != null && state.getStatus() == app.adera.user.profile.AppUser.Status.SUSPENDED) {
			return ResponseEntity.status(403).body(java.util.Map.of("error", "account_suspended"));
		}
		return ResponseEntity.ok().header(GatewayAuthFilter.USER_ID_HEADER, id.toString()).build();
	}

}
