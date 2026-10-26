package app.adera.registry.key;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import app.adera.registry.app.AppViews;
import app.adera.registry.audit.Actor;
import app.adera.registry.auth.Role;
import app.adera.registry.common.ClientIp;
import app.adera.registry.common.Environment;
import app.adera.registry.common.Errors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A partner proves who they are by signing requests with a private key only they hold; we store the matching PUBLIC key.
 * Register the key they generated, or (convenience) have us generate a pair and hand over the private half once.
 */
@RestController
@RequestMapping("/admin/api/v1/apps/{appId}/keys")
public class KeyAdminController {

	private final PartnerKeyService service;

	public KeyAdminController(PartnerKeyService service) {
		this.service = service;
	}

	static AppViews.KeyView view(PartnerKey k) {
		return new AppViews.KeyView(k.getKeyId(), k.getEnvironment(), k.getAlgorithm(), k.getFingerprint(), k.getLabel(), k.getStatus(),
				k.getExpiresAt(), k.getCreatedAt(), k.getRevokedAt());
	}

	@GetMapping
	public List<AppViews.KeyView> list(@PathVariable UUID appId) {
		return service.list(appId).stream().map(KeyAdminController::view).toList();
	}

	@PostMapping
	public AppViews.KeyView register(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role, @PathVariable UUID appId,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		reject(body, "environment", "publicKeyPem", "label", "expiresAt");
		return view(service.register(appId, Environment.parse(String.valueOf(body.get("environment"))), (String) body.get("publicKeyPem"),
				(String) body.get("label"), expires(body), new Actor(id, role, ClientIp.of(request))));
	}

	/** The private key is in this response only. We do not keep it and cannot show it again. */
	@PostMapping("/generate")
	public Map<String, Object> generate(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role, @PathVariable UUID appId,
			@RequestBody Map<String, Object> body, HttpServletRequest request) {
		role.require(Role.ADMIN);
		reject(body, "environment", "label", "expiresAt");
		PartnerKeyService.Created created = service.generate(appId, Environment.parse(String.valueOf(body.get("environment"))),
				(String) body.get("label"), expires(body), new Actor(id, role, ClientIp.of(request)));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("key", view(created.key()));
		out.put("privateKeyPem", created.privateKeyPem());
		out.put("warning", "Shown once. We store only the public key. Give the private key to the partner over a secure channel.");
		return out;
	}

	@DeleteMapping("/{keyId}")
	public AppViews.KeyView revoke(@RequestHeader("X-Staff-Id") UUID id, @RequestHeader("X-Staff-Role") Role role, @PathVariable UUID appId,
			@PathVariable String keyId, HttpServletRequest request) {
		role.require(Role.ADMIN);
		return view(service.revoke(appId, keyId, new Actor(id, role, ClientIp.of(request))));
	}

	private static void reject(Map<String, Object> body, String... allowed) {
		java.util.Set<String> ok = java.util.Set.of(allowed);
		for (String k : body.keySet()) {
			if (!ok.contains(k)) {
				throw new Errors.ValidationFailed(k, "unknown field");
			}
		}
	}

	private static Instant expires(Map<String, Object> body) {
		Object v = body.get("expiresAt");
		if (v == null) {
			return null;
		}
		try {
			return Instant.parse(String.valueOf(v));
		}
		catch (DateTimeParseException e) {
			throw new Errors.ValidationFailed("expiresAt", "must be an ISO-8601 instant like 2027-01-01T00:00:00Z");
		}
	}

}
