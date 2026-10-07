package com.example.processengine.storage;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

/**
 * Dependency-free stand-in for {@link RocksDbStateStore}, used to verify
 * {@code ProcessEngine} and the agent orchestration logic without
 * requiring the native RocksDB JNI library on the classpath. Backed by a
 * {@link ConcurrentSkipListMap} specifically because it keeps keys
 * sorted — RocksDB is also naturally key-ordered (it's an LSM-tree), so
 * {@link #listKeys} here behaves the same way a real prefix-scan would,
 * making this a faithful enough substitute for testing prefix queries.
 */
public final class InMemoryStateStore implements StateStore {
    private final ConcurrentSkipListMap<String, String> data = new ConcurrentSkipListMap<>();

    @Override
    public void put(String key, String value) {
        data.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(data.get(key));
    }

    @Override
    public void delete(String key) {
        data.remove(key);
    }

    @Override
    public List<String> listKeys(String prefix) {
        return data.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toList());
    }

    @Override
    public void close() {
        // no resources to release
    }
}
