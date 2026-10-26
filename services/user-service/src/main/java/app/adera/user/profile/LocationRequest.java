package app.adera.user.profile;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What the user chose to share. Coarse is fine (city level); coordinates are optional and always user-initiated. */
public record LocationRequest(
		@Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a 2-letter ISO country code") String countryCode,
		@Size(max = 100) String region,
		@Size(max = 100) String city,
		@DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
		@DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {
}
