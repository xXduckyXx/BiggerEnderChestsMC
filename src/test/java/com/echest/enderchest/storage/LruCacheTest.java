package com.echest.enderchest.storage;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LruCacheTest {

    @Test
    void evictsTheLeastRecentlyUsedEntry() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        assertEquals(2, cache.size());
        assertNull(cache.get("a"), "the oldest entry has to go");
        assertNotNull(cache.get("b"));
        assertNotNull(cache.get("c"));
    }

    @Test
    void usesTheAccessOrderNotTheInsertionOrder() {
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "1");
        cache.put("b", "2");

        // Reading "a" makes it the most recently used entry.
        cache.get("a");
        cache.put("c", "3");

        assertNotNull(cache.get("a"), "the entry that was used last must survive");
        assertNull(cache.get("b"));
    }

    @Test
    void keepsTheSizeWithinTheLimit() {
        LruCache<UUID, String> cache = new LruCache<>(100);
        for (int i = 0; i < 250; i++) {
            cache.put(UUID.randomUUID(), "value" + i);
        }

        assertEquals(100, cache.size());
        assertEquals(100, cache.maxSize());
    }

    @Test
    void rejectsAnInvalidSize() {
        try {
            new LruCache<String, String>(0);
        } catch (IllegalArgumentException e) {
            return;
        }
        throw new AssertionError("a cache without a size limit must be rejected");
    }

    @Test
    void worksAsAMap() {
        LruCache<String, Integer> cache = new LruCache<>(4);
        cache.put("a", 1);
        cache.put("b", 2);

        Map<String, Integer> asMap = cache;

        assertEquals(2, asMap.size());
        assertTrue(asMap.containsKey("a"));
        cache.remove("a");
        assertEquals(1, cache.size());
    }
}
