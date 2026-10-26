package app.adera.registry.storage;

import java.time.Duration;
import java.util.UUID;

/** Where files live (RustFS through its S3 API; an in-memory fake in tests). */
public interface ObjectStorage {

	/** apps/{app id}/logo/{image id}.png */
	static String logoKey(UUID appId, UUID imageId) {
		return "apps/" + appId + "/logo/" + imageId + ".png";
	}

	void put(String key, byte[] bytes, String contentType);

	/** Best effort: a missing object is not an error. */
	void delete(String key);

	/** A time-limited link clients fetch directly, so image bytes never pass through the API. */
	String presignedGetUrl(String key, Duration validFor);

}
