package app.adera.registry.staff;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.audit.Actor;
import app.adera.registry.auth.Role;
import app.adera.registry.common.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api/v1")
public class StaffController {

	public record StaffView(UUID id, String email, String role, String status, Instant createdAt, Instant updatedAt,
			boolean bootstrap, UUID createdBy) {

		static StaffView of(Staff s, String bootstrapEmail) {
			boolean bootstrap = bootstrapEmail != null && !bootstrapEmail.isBlank() && bootstrapEmail.trim().equalsIgnoreCase(s.getEmail());
			return new StaffView(s.getIdentityId(), s.getEmail(), s.getRole().name(), s.getStatus().name(), s.getCreatedAt(), s.getUpdatedAt(),
					bootstrap, s.getCreatedBy());
		}

	}

	private final StaffService service;
	private final String bootstrapEmail;

	public StaffController(StaffService service, app.adera.registry.config.RegistryProperties props) {
		this.service = service;
		this.bootstrapEmail = props.bootstrapSuperAdminEmail();
	}

	/** Who am I, as the dashboard sees me. Any role. */
	@GetMapping("/me")
	public Map<String, Object> me(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role) {
		return Map.of("id", id, "role", role.name());
	}

	@GetMapping("/staff")
	public List<StaffView> list(@RequestHeader("X-Staff-Role") Role role) {
		role.require(Role.SUPER_ADMIN);
		return service.list().stream().map(s -> StaffView.of(s, bootstrapEmail)).toList();
	}

	/** Make an email a staff member (creates the identity if needed). Super admin only. */
	@PostMapping("/staff")
	public StaffView create(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.SUPER_ADMIN);
		Role newRole = Role.parseOrNull(String.valueOf(body.get("role")));
		if (newRole == null) {
			throw new app.adera.registry.common.Errors.ValidationFailed("role", "must be one of VIEWER, ADMIN, SUPER_ADMIN");
		}
		return StaffView.of(service.ensure(String.valueOf(body.get("email")), newRole, new Actor(id, role, ClientIp.of(request))), bootstrapEmail);
	}

	@PatchMapping("/staff/{identityId}")
	public StaffView update(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID identityId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.SUPER_ADMIN);
		return StaffView.of(service.update(identityId, body, new Actor(id, role, ClientIp.of(request))), bootstrapEmail);
	}

}
