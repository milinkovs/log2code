package org.log2code.ingester.match;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A small, fixed-capacity least-recently-used cache (T20 step 3: 100k entries, keyed on
 * {@code (service, logger_raw, level, message, hasException)}). Backed by an access-ordered
 * {@link LinkedHashMap} whose {@code removeEldestEntry} evicts past {@code capacity}; every access is
 * synchronized, which is more than adequate for a cache that only exists to skip re-scoring an event
 * whose 5-field key repeats verbatim (an ingester run is not expected to score events concurrently).
 */
final class LruCache<K, V> {

    private final int capacity;
    private final Map<K, V> delegate;

    LruCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.delegate = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.capacity;
            }
        };
    }

    synchronized V computeIfAbsent(K key, Function<K, V> compute) {
        V cached = delegate.get(key);
        if (cached != null) {
            return cached;
        }
        V computed = Objects.requireNonNull(compute.apply(key), "computed value");
        delegate.put(key, computed);
        return computed;
    }

    synchronized int size() {
        return delegate.size();
    }
}
