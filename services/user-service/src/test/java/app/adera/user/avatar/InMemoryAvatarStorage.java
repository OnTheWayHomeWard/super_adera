package app.adera.user.avatar;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Test double for RustFS: lets integration tests check what was stored and deleted, without a storage server. */
@Component
@ConditionalOnProperty(prefix = "adera.user.storage", name = "type", havingValue = "memory")
public class InMemoryAvatarStorage implements AvatarStorage {

	public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

	@Override
	public void put(String key, byte[] bytes, String contentType) {
		objects.put(key, bytes);
	}

	@Override
	public void delete(String key) {
		objects.remove(key);
	}

	@Override
	public String presignedGetUrl(String key, Duration validFor) {
		return "http://storage.test/" + key + "?expires=" + validFor.toMinutes();
	}

}
