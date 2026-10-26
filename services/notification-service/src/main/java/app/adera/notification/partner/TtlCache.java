package app.adera.notification.partner;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** A tiny time-based cache (including remembering "not found" briefly), so every request does not hit the registry. */
public final class TtlCache<K, V> {

	private record Entry<V>(Optional<V> value, long expiresAtNanos) {
	}

	private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
	private final long ttlNanos;

	public TtlCache(Duration ttl) {
		this.ttlNanos = ttl.toNanos();
	}

	public Optional<V> get(K key, Function<K, Optional<V>> loader) {
		long now = System.nanoTime();
		Entry<V> cached = entries.get(key);
		if (cached != null && cached.expiresAtNanos() > now) {
			return cached.value();
		}
		Optional<V> loaded = loader.apply(key);
		if (entries.size() > 5_000) {
			entries.clear(); // crude bound: this only holds keys and app contexts
		}
		entries.put(key, new Entry<>(loaded, now + ttlNanos));
		return loaded;
	}

	public void clear() {
		entries.clear();
	}

}
