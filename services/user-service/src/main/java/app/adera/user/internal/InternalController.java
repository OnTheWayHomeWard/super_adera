package app.adera.user.internal;

import java.util.List;
import java.util.UUID;

import app.adera.user.alias.AliasService;
import app.adera.user.consent.ConsentService;
import app.adera.user.device.Device;
import app.adera.user.device.DeviceRepository;
import app.adera.user.profile.AppUser;
import app.adera.user.profile.NotFoundException;
import app.adera.user.profile.ProfileService;
import app.adera.user.profile.UserRepository;
import app.adera.user.registry.RegistryAppClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** For other services only (never routed by the gateway; needs the internal secret). */
@RestController
@RequestMapping("/internal")
public class InternalController {

	public record MintRequest(@NotNull UUID appId, @NotNull UUID userId) {
	}

	public record DeactivateRequest(@NotBlank String fcmToken) {
	}

	/**
	 * How to reach one user on behalf of one app. {@code reachable=false} means deleted or suspended. {@code consents} is the user's
	 * answer per notify.* capability for this app (NOT_ASKED, GRANTED, DENIED, BLOCKED): only GRANTED lets a message through.
	 */
	public record DeliveryContext(UUID userId, boolean reachable, String language, String phone, String email,
			List<String> pushTokens, java.util.Map<String, String> consents) {
	}

	private final AliasService aliases;
	private final ConsentService consents;
	private final ProfileService profiles;
	private final UserRepository users;
	private final DeviceRepository devices;
	private final RegistryAppClient registry;

	public InternalController(AliasService aliases, ConsentService consents, ProfileService profiles, UserRepository users,
			DeviceRepository devices, RegistryAppClient registry) {
		this.registry = registry;
		this.aliases = aliases;
		this.consents = consents;
		this.profiles = profiles;
		this.users = users;
		this.devices = devices;
	}

	/** Gives a user their pseudonymous id for an app (creating nothing new if it exists). Used when a user opens an app. */
	@PostMapping("/aliases")
	public java.util.Map<String, String> mint(@Valid @RequestBody MintRequest request) {
		// any status counts (partners integrate against DEV before an app is LIVE), but the app must exist
		registry.status(request.appId()).orElseThrow(() -> new NotFoundException("mini app"));
		profiles.getOrCreate(request.userId());
		return java.util.Map.of("alias", aliases.mint(request.appId(), request.userId()));
	}

	@GetMapping("/aliases/resolve")
	public java.util.Map<String, UUID> resolve(@RequestParam UUID appId, @RequestParam String alias) {
		return java.util.Map.of("userId", aliases.resolve(appId, alias).orElseThrow(() -> new NotFoundException("alias")));
	}

	@GetMapping("/users/{userId}/delivery-context")
	@Transactional(readOnly = true)
	public DeliveryContext deliveryContext(@PathVariable UUID userId, @RequestParam UUID appId) {
		AppUser user = users.findById(userId).orElseThrow(() -> new NotFoundException("user"));
		boolean reachable = user.getDeletedAt() == null && user.getStatus() == AppUser.Status.ACTIVE;
		List<String> tokens = reachable ? devices.findByUserIdAndActiveTrueOrderByLastSeenAtDesc(userId).stream().map(Device::getFcmToken).toList() : List.of();
		return new DeliveryContext(userId, reachable, user.getLanguage(), reachable ? user.getPhoneE164() : null,
				reachable ? user.getEmail() : null, tokens, consents.notifyStates(userId, appId));
	}

	/**
	 * What a partner profile read may release (notification-service serves {@code GET /v1/users/{alias}/profile}, user data stays here):
	 * the user's state for each requested capability, and values only for the GRANTED ones. {@code reachable=false} for suspended or deleted users.
	 */
	@GetMapping("/users/{userId}/consented-profile")
	public ConsentService.ProfileRelease consentedProfile(@PathVariable UUID userId, @RequestParam UUID appId, @RequestParam String capabilities) {
		List<String> list = new java.util.ArrayList<>();
		for (String c : capabilities.split(",")) {
			if (!c.matches("^[a-z][a-z0-9_.]{1,38}$")) {
				throw new app.adera.user.profile.ValidationFailedException("capabilities", "must be capability keys such as user.full_name");
			}
			list.add(c);
		}
		return consents.profileRelease(userId, appId, list);
	}

	/** Firebase said this token no longer works. */
	@PostMapping("/devices/deactivate")
	@Transactional
	public java.util.Map<String, Boolean> deactivate(@Valid @RequestBody DeactivateRequest request) {
		boolean found = devices.findByFcmToken(request.fcmToken()).map(d -> {
			d.deactivate();
			return true;
		}).orElse(false);
		return java.util.Map.of("deactivated", found);
	}

}
