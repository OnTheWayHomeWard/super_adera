package app.adera.user.avatar;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import app.adera.user.config.UserProperties;
import app.adera.user.profile.AppUser;
import app.adera.user.profile.ProfileService;
import app.adera.user.profile.ValidationFailedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AvatarService {

	/** Thrown when the upload exceeds the size limit; mapped to HTTP 413. */
	public static class TooLargeException extends RuntimeException {

		public TooLargeException(int maxBytes) {
			super("file is larger than " + maxBytes + " bytes");
		}

	}

	private final ProfileService profiles;
	private final AvatarStorage storage;
	private final UserProperties props;
	private final Clock clock;

	public AvatarService(ProfileService profiles, AvatarStorage storage, UserProperties props, Clock clock) {
		this.profiles = profiles;
		this.storage = storage;
		this.props = props;
		this.clock = clock;
	}

	/** Replaces the user's avatar. The previous image file is removed once the new one is saved. */
	@Transactional
	public AppUser upload(UUID userId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new ValidationFailedException("file", "a file is required (multipart field 'file')");
		}
		if (file.getSize() > props.avatar().maxBytes()) {
			throw new TooLargeException(props.avatar().maxBytes());
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException e) {
			throw new ValidationFailedException("file", "could not read the upload");
		}
		AvatarImageProcessor.Processed image = AvatarImageProcessor.process(bytes, props.avatar().sizePx());

		AppUser user = profiles.getOrCreate(userId);
		UUID previous = user.getAvatarImageId();
		UUID imageId = UUID.randomUUID();
		storage.put(AvatarStorage.keyFor(userId, imageId), image.bytes(), image.contentType());
		user.setAvatar(imageId, image.contentType(), clock.instant());
		user.touch(clock.instant());
		if (previous != null) {
			storage.delete(AvatarStorage.keyFor(userId, previous));
		}
		return user;
	}

	@Transactional
	public AppUser remove(UUID userId) {
		AppUser user = profiles.getOrCreate(userId);
		UUID previous = user.getAvatarImageId();
		if (previous != null) {
			storage.delete(AvatarStorage.keyFor(userId, previous));
			user.clearAvatar();
			user.touch(clock.instant());
		}
		return user;
	}

	/** A short-lived link to the image, or null if the user has no avatar. */
	public String urlFor(AppUser user) {
		if (user.getAvatarImageId() == null) {
			return null;
		}
		return storage.presignedGetUrl(AvatarStorage.keyFor(user.getId(), user.getAvatarImageId()),
				Duration.ofMinutes(props.storage().presignMinutes()));
	}

}
