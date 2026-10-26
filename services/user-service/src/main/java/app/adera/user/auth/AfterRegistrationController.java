package app.adera.user.auth;

import java.util.Map;
import java.util.UUID;

import app.adera.user.profile.ProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Kratos calls this right after a new identity is created (registration web_hook). It is internal only: the gateway
 * does not route it, and the Kratos hook is non-blocking, so this service being slow or down never blocks a sign-up.
 * If the call is ever lost, the profile is created on first use instead (see {@link ProfileService#getOrCreate}).
 */
@RestController
public class AfterRegistrationController {

	public record Registration(@NotNull UUID identityId, String email, String phone) {
	}

	private final ProfileService profiles;

	public AfterRegistrationController(ProfileService profiles) {
		this.profiles = profiles;
	}

	@PostMapping("/internal/kratos/after-registration")
	public Map<String, String> afterRegistration(@Valid @RequestBody Registration body) {
		boolean created = profiles.createFromRegistration(body.identityId(), body.email(), body.phone());
		return Map.of("status", created ? "created" : "exists");
	}

}
