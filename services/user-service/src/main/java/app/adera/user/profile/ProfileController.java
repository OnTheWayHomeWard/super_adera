package app.adera.user.profile;

import java.util.Map;
import java.util.UUID;

import app.adera.user.avatar.AvatarService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The signed-in user's own profile. The user id comes from the gateway-verified {@code X-User-Id} header. */
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

	private final ProfileService profiles;
	private final AvatarService avatars;

	public ProfileController(ProfileService profiles, AvatarService avatars) {
		this.profiles = profiles;
		this.avatars = avatars;
	}

	@GetMapping
	public ProfileResponse get(@RequestHeader("X-User-Id") UUID userId) {
		return respond(profiles.getOrCreate(userId));
	}

	/**
	 * Partial update. Send only what changes; null clears an optional field. {@code extra} is merged
	 * (JSON Merge Patch): add or change keys, set a key to null to remove it.
	 */
	@PatchMapping
	public ProfileResponse update(@RequestHeader("X-User-Id") UUID userId, @RequestBody Map<String, Object> body) {
		return respond(profiles.update(userId, body));
	}

	@PutMapping("/location")
	public ProfileResponse setLocation(@RequestHeader("X-User-Id") UUID userId,
			@Valid @RequestBody LocationRequest request) {
		return respond(profiles.setLocation(userId, request));
	}

	@DeleteMapping("/location")
	public ProfileResponse clearLocation(@RequestHeader("X-User-Id") UUID userId) {
		return respond(profiles.clearLocation(userId));
	}

	/** Multipart upload, field name {@code file}. Replaces any existing avatar. */
	@PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ProfileResponse uploadAvatar(@RequestHeader("X-User-Id") UUID userId,
			@RequestPart(name = "file", required = false) MultipartFile file) {
		return respond(avatars.upload(userId, file));
	}

	@DeleteMapping("/avatar")
	public ProfileResponse removeAvatar(@RequestHeader("X-User-Id") UUID userId) {
		return respond(avatars.remove(userId));
	}

	private ProfileResponse respond(AppUser user) {
		return ProfileResponse.of(user, avatars.urlFor(user));
	}

}
