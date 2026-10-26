package app.adera.user.profile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import app.adera.user.auth.KratosClient;
import app.adera.user.config.UserProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ProfileService {

	private static final Duration SEEN_EVERY = Duration.ofMinutes(5);

	private final UserRepository users;
	private final KratosClient kratos;
	private final UserProperties props;
	private final JsonMapper mapper;
	private final Clock clock;

	public ProfileService(UserRepository users, KratosClient kratos, UserProperties props, JsonMapper mapper, Clock clock) {
		this.users = users;
		this.kratos = kratos;
		this.props = props;
		this.mapper = mapper;
		this.clock = clock;
	}

	/**
	 * The profile for an authenticated user. Normally the Kratos webhook created it at sign-up; if that call was lost
	 * (service down, network), it is created here from Kratos' own record, so a valid session always has a profile.
	 */
	@Transactional
	public AppUser getOrCreate(UUID id) {
		Instant now = clock.instant();
		AppUser user = users.findByIdAndDeletedAtIsNull(id).orElse(null);
		if (user == null) {
			KratosClient.Identity identity = kratos.identity(id)
				.orElseThrow(() -> new NotFoundException("identity " + id + " does not exist"));
			users.insertIfAbsent(id, identity.email(), identity.phone(), marketFor(identity.phone()), now);
			user = users.findByIdAndDeletedAtIsNull(id).orElseThrow(() -> new NotFoundException("profile " + id));
		}
		if (user.getLastSeenAt() == null || Duration.between(user.getLastSeenAt(), now).compareTo(SEEN_EVERY) > 0) {
			user.setLastSeenAt(now);
		}
		return user;
	}

	/** @return true if this call created the profile */
	@Transactional
	public boolean createFromRegistration(UUID id, String email, String phone) {
		Instant now = clock.instant();
		boolean created = users.insertIfAbsent(id, blankToNull(email), blankToNull(phone), marketFor(phone), now) == 1;
		if (!created) {
			users.findByIdAndDeletedAtIsNull(id).ifPresent(u -> {
				if (u.getEmail() == null && email != null && !email.isBlank()) {
					u.setEmail(email);
				}
				if (u.getPhoneE164() == null && phone != null && !phone.isBlank()) {
					u.setPhoneE164(phone);
				}
			});
		}
		return created;
	}

	@Transactional
	public AppUser update(UUID id, Map<String, Object> body) {
		AppUser user = getOrCreate(id);
		LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
		ProfilePatch patch = ProfilePatch.parse(body, mapper, props.extraMaxBytes(), today);
		Instant now = clock.instant();

		if (patch.has("firstName")) user.setFirstName((String) patch.get("firstName"));
		if (patch.has("lastName")) user.setLastName((String) patch.get("lastName"));
		if (patch.has("gender")) user.setGender((AppUser.Gender) patch.get("gender"));
		if (patch.has("dateOfBirth")) user.setDateOfBirth((LocalDate) patch.get("dateOfBirth"));
		if (patch.has("language")) user.setLanguage((String) patch.get("language"));
		if (patch.has("market")) user.setMarket((String) patch.get("market"));
		if (patch.has("timezone")) user.setTimezone((String) patch.get("timezone"));
		if (patch.has("termsAcceptedVersion")) user.acceptTerms((String) patch.get("termsAcceptedVersion"), now);
		if (patch.has("extra")) {
			Map<String, Object> merged = JsonMergePatch.apply(user.getExtra(), patch.extra());
			if (mapper.writeValueAsBytes(merged).length > props.extraMaxBytes()) {
				throw new ValidationFailedException("extra", "too large after merge (max " + props.extraMaxBytes() + " bytes)");
			}
			user.setExtra(merged);
		}
		user.touch(now);
		return user;
	}

	@Transactional
	public AppUser setLocation(UUID id, LocationRequest request) {
		boolean hasCoordinates = request.latitude() != null || request.longitude() != null;
		if (hasCoordinates && (request.latitude() == null || request.longitude() == null)) {
			throw new ValidationFailedException("latitude", "latitude and longitude must be given together");
		}
		if (!hasCoordinates && blank(request.countryCode()) && blank(request.region()) && blank(request.city())) {
			throw new ValidationFailedException("location", "give at least a city, region, country or coordinates");
		}
		AppUser user = getOrCreate(id);
		Instant now = clock.instant();
		user.setLocation(request.countryCode() == null ? null : request.countryCode().toUpperCase(java.util.Locale.ROOT),
				blankToNull(request.region()), blankToNull(request.city()), request.latitude(), request.longitude(), now);
		user.touch(now);
		return user;
	}

	@Transactional
	public AppUser clearLocation(UUID id) {
		AppUser user = getOrCreate(id);
		user.clearLocation();
		user.touch(clock.instant());
		return user;
	}

	static String marketFor(String phone) {
		if (phone != null && phone.startsWith("+1")) {
			return "US";
		}
		return "ET";
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static String blankToNull(String s) {
		return blank(s) ? null : s.strip();
	}

}
