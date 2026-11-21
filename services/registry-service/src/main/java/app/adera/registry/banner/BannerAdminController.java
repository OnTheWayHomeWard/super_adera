package app.adera.registry.banner;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.audit.Actor;
import app.adera.registry.auth.Role;
import app.adera.registry.common.ClientIp;
import app.adera.registry.common.Errors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Staff management of banners. VIEWER reads; ADMIN and SUPER_ADMIN change. Every change is written to the audit log. */
@RestController
@RequestMapping("/admin/api/v1/banners")
public class BannerAdminController {

	private final BannerService service;

	public BannerAdminController(BannerService service) {
		this.service = service;
	}

	private static Actor actor(UUID id, Role role, HttpServletRequest request) {
		return new Actor(id, role, ClientIp.of(request));
	}

	/** Highest priority first, then newest. {@code state} is the computed one. Total in X-Total-Count. */
	@GetMapping
	public ResponseEntity<List<BannerViews.AdminBanner>> list(@RequestParam(required = false) String placement,
			@RequestParam(required = false) String category, @RequestParam(required = false) String state,
			@RequestParam(required = false) UUID appId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		BannerService.Filter filter = new BannerService.Filter(filterValue(Banner.Placement.class, "placement", placement),
				filterValue(Banner.Category.class, "category", category), filterValue(BannerState.class, "state", state), appId);
		Page<Banner> result = service.list(filter, page, size);
		return ResponseEntity.ok().header("X-Total-Count", String.valueOf(result.getTotalElements())).body(service.views(result.getContent()));
	}

	private static <E extends Enum<E>> E filterValue(Class<E> type, String name, String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		E value = BannerPatch.parseEnum(type, raw);
		if (value == null) {
			throw new Errors.ValidationFailed(name, "must be " + BannerPatch.choices(type));
		}
		return value;
	}

	/** Allowed external domains, enum values, image sizes and the upload limit, for the backoffice form. */
	@GetMapping("/settings")
	public Map<String, Object> settings() {
		return service.settings();
	}

	@GetMapping("/{bannerId}")
	public BannerViews.AdminBanner get(@PathVariable UUID bannerId) {
		return service.view(service.get(bannerId));
	}

	@PostMapping
	public BannerViews.AdminBanner create(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return service.view(service.create(body, actor(id, role, request)));
	}

	@PatchMapping("/{bannerId}")
	public BannerViews.AdminBanner update(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID bannerId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return service.view(service.update(bannerId, body, actor(id, role, request)));
	}

	/** {"status": "PUBLISHED" | "PAUSED" | "DRAFT"} */
	@PostMapping("/{bannerId}/status")
	public BannerViews.AdminBanner setStatus(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID bannerId, @RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return service.view(service.setStatus(bannerId, body.get("status"), actor(id, role, request)));
	}

	/** Multipart field "file": PNG, JPEG, GIF or BMP, stored as JPEG in three sizes. Replaces any previous image. */
	@PostMapping("/{bannerId}/image")
	public BannerViews.AdminBanner uploadImage(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID bannerId, @RequestPart(name = "file", required = false) MultipartFile file, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return service.view(service.uploadImage(bannerId, file, actor(id, role, request)));
	}

	/** {"ids": [...]}: these banners show in this order (first = highest priority). */
	@PostMapping("/reorder")
	public List<BannerViews.AdminBanner> reorder(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return service.views(service.reorder(body.get("ids"), actor(id, role, request)));
	}

	@DeleteMapping("/{bannerId}")
	public Map<String, String> delete(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role,
			@PathVariable UUID bannerId, HttpServletRequest request) {
		role.require(Role.ADMIN);
		service.delete(bannerId, actor(id, role, request));
		return Map.of("status", "deleted");
	}

}
