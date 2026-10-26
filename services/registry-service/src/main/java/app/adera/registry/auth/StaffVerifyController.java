package app.adera.registry.auth;

import java.util.Map;
import java.util.Optional;

import app.adera.registry.config.RegistryProperties;
import app.adera.registry.staff.Staff;
import app.adera.registry.staff.StaffRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the gateway before it lets a request reach the admin API. Answers 200 with the staff member's id and role,
 * or: 401 (no/invalid session), 403 {@code not_staff}, 403 {@code mfa_required} (the authenticator-app step is missing).
 */
@RestController
public class StaffVerifyController {

	private final KratosClient kratos;
	private final StaffRepository staff;
	private final RegistryProperties props;

	public StaffVerifyController(KratosClient kratos, StaffRepository staff, RegistryProperties props) {
		this.kratos = kratos;
		this.staff = staff;
		this.props = props;
	}

	@GetMapping("/internal/staff/verify")
	public ResponseEntity<Map<String, String>> verify(@RequestHeader(value = "Authorization", required = false) String authorization) {
		if (authorization == null || authorization.isBlank()) {
			return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
		}
		KratosClient.WhoamiResult result = kratos.whoami(authorization);
		if (result.outcome() == KratosClient.Outcome.INVALID) {
			return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
		}
		if (result.outcome() == KratosClient.Outcome.MFA_REQUIRED) {
			return ResponseEntity.status(403).body(Map.of("error", "mfa_required"));
		}
		KratosClient.Session session = result.session();
		Role role = Role.parseOrNull(session.role());
		Optional<Staff> member = staff.findById(session.identityId());
		if (role == null || member.isEmpty() || member.get().getStatus() != Staff.Status.ACTIVE) {
			return ResponseEntity.status(403).body(Map.of("error", "not_staff"));
		}
		if (props.requireAal2() && !session.isAal2()) {
			return ResponseEntity.status(403).body(Map.of("error", "mfa_required"));
		}
		return ResponseEntity.ok()
			.header("X-Staff-Id", session.identityId().toString())
			.header("X-Staff-Role", role.name())
			.body(Map.of("status", "ok"));
	}

}
