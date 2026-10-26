package app.adera.user.admin;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff view of registered users (dashboard). VIEWER reads masked data; ADMIN and above see full contact details and
 * may suspend, reactivate or mark a user deleted. Every change is written to the central audit log.
 */
@RestController
@RequestMapping("/admin/api/v1/users")
public class AdminUserController {

	private final AdminUserService service;

	public AdminUserController(AdminUserService service) {
		this.service = service;
	}

	@GetMapping
	public Map<String, Object> list(@RequestHeader("X-Staff-Role") StaffRole role, @RequestParam(required = false) String q,
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size) {
		return service.list(role, q, status, page, size);
	}

	@GetMapping("/stats")
	public Map<String, Object> stats() {
		return service.stats();
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@RequestHeader("X-Staff-Role") StaffRole role, @PathVariable UUID id) {
		return service.detail(role, id);
	}

	@PostMapping("/{id}/status")
	public Map<String, Object> setStatus(@RequestHeader("X-Staff-Id") UUID staffId, @RequestHeader("X-Staff-Role") StaffRole role,
			@RequestHeader(value = "X-Client-IP", required = false) String ip, @PathVariable UUID id,
			@RequestBody Map<String, Object> body) {
		role.require(StaffRole.ADMIN);
		return service.setStatus(staffId, role, ip, id, body);
	}

}
