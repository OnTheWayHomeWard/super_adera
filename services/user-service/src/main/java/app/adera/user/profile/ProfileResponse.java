package app.adera.user.profile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** The user's own profile, as returned by the API. {@code avatarUrl} is a short-lived signed link. */
public record ProfileResponse(
		UUID id,
		String email,
		String phone,
		String firstName,
		String lastName,
		String gender,
		LocalDate dateOfBirth,
		String language,
		String market,
		String timezone,
		UUID avatarImageId,
		String avatarUrl,
		Location location,
		String verificationTier,
		String status,
		String termsAcceptedVersion,
		Instant termsAcceptedAt,
		Map<String, Object> extra,
		Instant createdAt,
		Instant updatedAt) {

	public record Location(String countryCode, String region, String city, Double latitude, Double longitude,
			Instant updatedAt) {
	}

	public static ProfileResponse of(AppUser u, String avatarUrl) {
		Location location = u.getLocationUpdatedAt() == null ? null
				: new Location(u.getCountryCode(), u.getRegion(), u.getCity(), u.getLatitude(), u.getLongitude(),
						u.getLocationUpdatedAt());
		return new ProfileResponse(u.getId(), u.getEmail(), u.getPhoneE164(), u.getFirstName(), u.getLastName(),
				u.getGender() == null ? null : u.getGender().name(), u.getDateOfBirth(), u.getLanguage(), u.getMarket(),
				u.getTimezone(), u.getAvatarImageId(), avatarUrl, location, u.getVerificationTier().name(),
				u.getStatus().name(), u.getTermsAcceptedVersion(), u.getTermsAcceptedAt(), u.getExtra(),
				u.getCreatedAt(), u.getUpdatedAt());
	}

}
