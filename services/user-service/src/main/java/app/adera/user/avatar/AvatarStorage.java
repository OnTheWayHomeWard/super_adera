package app.adera.user.avatar;

import java.time.Duration;
import java.util.UUID;

/** Where avatar image files live. Implemented by RustFS (S3 API) in the platform, and in memory for tests. */
public interface AvatarStorage {

	/** The object key for a user's avatar image: avatars/{user id}/{image id}.jpg */
	static String keyFor(UUID userId, UUID imageId) {
		return "avatars/" + userId + "/" + imageId + ".jpg";
	}

	void put(String key, byte[] bytes, String contentType);

	/** Best effort: a missing object is not an error. */
	void delete(String key);

	/** A time-limited link the client can fetch directly, so image bytes never pass through our API. */
	String presignedGetUrl(String key, Duration validFor);

}
