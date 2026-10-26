package app.adera.notification.partner.admin;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.notification.partner.template.TemplateService;
import app.adera.notification.partner.template.TemplateView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff create and approve the message templates each mini app may use. Partners cannot create or edit them.
 * ADMIN and above change things; VIEWER reads.
 */
@RestController
@RequestMapping("/admin/api/v1/notification/templates")
public class TemplateAdminController {

	private final TemplateService templates;
	private final Clock clock;

	public TemplateAdminController(TemplateService templates, Clock clock) {
		this.templates = templates;
		this.clock = clock;
	}

	@GetMapping
	public List<TemplateView> list(@RequestParam UUID appId) {
		return templates.list(appId).stream().map(t -> TemplateView.of(t, clock)).toList();
	}

	@PostMapping
	public TemplateView create(@RequestHeader("X-Staff-Id") UUID staffId, @RequestHeader("X-Staff-Role") StaffRole role,
			@RequestHeader(value = "X-Client-IP", required = false) String ip, @RequestBody Map<String, Object> body) {
		role.require(StaffRole.ADMIN);
		return TemplateView.of(templates.create(body, new StaffActor(staffId, role, ip)), clock);
	}

	@GetMapping("/{id}")
	public TemplateView get(@PathVariable UUID id) {
		return TemplateView.of(templates.get(id), clock);
	}

	/**
	 * Change the words, declared values, category, validity window, or switch it on/off ({"active": false}). Templates are
	 * rendered when a message is DELIVERED, so an edit also changes messages that were accepted earlier and are still queued
	 * or retrying (the text of the current version is used). The backoffice says so above the edit form.
	 */
	@PatchMapping("/{id}")
	public TemplateView update(@RequestHeader("X-Staff-Id") UUID staffId, @RequestHeader("X-Staff-Role") StaffRole role,
			@RequestHeader(value = "X-Client-IP", required = false) String ip, @PathVariable UUID id, @RequestBody Map<String, Object> body) {
		role.require(StaffRole.ADMIN);
		return TemplateView.of(templates.update(id, body, new StaffActor(staffId, role, ip)), clock);
	}

}
