package app.adera.registry.app;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.audit.Actor;
import app.adera.registry.auth.Role;
import app.adera.registry.common.ClientIp;
import app.adera.registry.common.Errors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Staff-only management of mini apps. Roles: VIEWER reads; ADMIN changes things; reviving a killed app needs SUPER_ADMIN.
 * Every change is written to the audit log.
 */
@RestController
@RequestMapping("/admin/api/v1")
public class AppAdminController {

	private final AppService apps;
	private final AppConfigService config;

	public AppAdminController(AppService apps, AppConfigService config) {
		this.apps = apps;
		this.config = config;
	}

	private static Actor actor(UUID id, Role role, HttpServletRequest request) {
		return new Actor(id, role, ClientIp.of(request));
	}

	@GetMapping("/apps")
	public List<AppViews.AppView> list(@RequestParam(required = false) String status) {
		MiniApp.Status filter = null;
		if (status != null && !status.isBlank()) {
			try {
				filter = MiniApp.Status.valueOf(status.toUpperCase());
			}
			catch (IllegalArgumentException e) {
				throw new Errors.ValidationFailed("status", "must be DRAFT, LIVE, PAUSED or KILLED");
			}
		}
		return apps.list(filter).stream().map(apps::view).toList();
	}

	@PostMapping("/apps")
	public AppViews.AppView create(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return apps.view(apps.create(body, actor(id, role, request)));
	}

	@GetMapping("/apps/{appId}")
	public AppViews.AppDetail detail(@PathVariable UUID appId) {
		return apps.detail(appId);
	}

	@PatchMapping("/apps/{appId}")
	public AppViews.AppView update(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return apps.view(apps.update(appId, body, actor(id, role, request)));
	}

	@PostMapping("/apps/{appId}/status")
	public AppViews.AppView setStatus(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return apps.view(apps.setStatus(appId, String.valueOf(body.get("status")), actor(id, role, request)));
	}

	@PostMapping("/apps/{appId}/logo")
	public AppViews.AppView uploadLogo(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, @RequestPart(name = "file", required = false) MultipartFile file, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return apps.view(apps.uploadLogo(appId, file, actor(id, role, request)));
	}

	@DeleteMapping("/apps/{appId}/logo")
	public AppViews.AppView removeLogo(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return apps.view(apps.removeLogo(appId, actor(id, role, request)));
	}

	/** The list of things we can allow an app to ask for. */
	@GetMapping("/capabilities")
	public List<Map<String, Object>> capabilities() {
		return Arrays.stream(Capability.values())
			.map(c -> Map.<String, Object>of("key", c.key(), "description", c.description(), "reasonRequired", c.reasonRequired()))
			.toList();
	}

	@PutMapping("/apps/{appId}/capabilities/{capability}")
	public AppViews.AppDetail setCapability(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, @PathVariable String capability, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		config.setCapability(appId, capability, body, actor(id, role, request));
		return apps.detail(appId);
	}

	@PutMapping("/apps/{appId}/payment-settings")
	public AppConfigRepository.PaymentRow setPayment(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return config.setPayment(appId, body, actor(id, role, request));
	}

	@PutMapping("/apps/{appId}/notification-limits/{channel}")
	public AppConfigRepository.LimitRow setLimit(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, @PathVariable String channel, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return config.setLimit(appId, channel, body, actor(id, role, request));
	}

	@PutMapping("/apps/{appId}/environments/{environment}")
	public AppConfigRepository.EnvironmentRow setEnvironment(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID appId, @PathVariable String environment, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return config.setEnvironment(appId, environment, body, actor(id, role, request));
	}

}
