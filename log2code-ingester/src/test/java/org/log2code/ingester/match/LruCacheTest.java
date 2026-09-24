package org.log2code.ingester.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LruCacheTest {

    @Test
    void computeIfAbsentComputesOnceThenReusesTheCachedValue() {
        LruCache<String, Integer> cache = new LruCache<>(10);
        AtomicInteger calls = new AtomicInteger();

        int first = cache.computeIfAbsent("a", k -> {
            calls.incrementAndGet();
            return 1;
        });
        int second = cache.computeIfAbsent("a", k -> {
            calls.incrementAndGet();
            return 99; // never called - "a" is already cached
        });

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryOnceCapacityIsExceeded() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.computeIfAbsent("a", k -> "A");
        cache.computeIfAbsent("b", k -> "B");
        cache.computeIfAbsent("a", k -> "A-recomputed"); // touches "a", "b" is now the least recently used
        cache.computeIfAbsent("c", k -> "C"); // evicts "b"

        AtomicInteger recomputed = new AtomicInteger();
        String b = cache.computeIfAbsent("b", k -> {
            recomputed.incrementAndGet();
            return "B-again";
        });

        assertThat(b).isEqualTo("B-again");
        assertThat(recomputed.get()).isEqualTo(1); // "b" had to be recomputed - it was evicted
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void neverExceedsCapacity() {
        LruCache<Integer, Integer> cache = new LruCache<>(5);
        for (int i = 0; i < 100; i++) {
            int value = i;
            cache.computeIfAbsent(i, k -> value);
        }

        assertThat(cache.size()).isEqualTo(5);
    }
}
