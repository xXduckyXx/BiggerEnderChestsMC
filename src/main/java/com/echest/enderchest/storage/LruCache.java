package com.echest.enderchest.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small least recently used cache.
 *
 * <p>Entries are ordered by access, not by insertion, so a chest that is used often stays in the
 * cache while the chests of players that never logged in again are dropped first.
 *
 * <p>The cache is not thread safe on its own, callers have to synchronise the access themselves.
 *
 * @param <K> key type
 * @param <V> value type
 */
public class LruCache<K, V> extends LinkedHashMap<K, V> {

    private final int maxSize;

    /**
     * Creates a cache.
     *
     * @param maxSize maximum number of entries
     */
    public LruCache(int maxSize) {
        super(16, 0.75f, true);
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
    }

    /**
     * The maximum number of entries.
     *
     * @return the maximum size
     */
    public int maxSize() {
        return maxSize;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > maxSize;
    }
}
