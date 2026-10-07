package com.example.processengine.storage;

import java.util.List;
import java.util.Optional;

/**
 * Local, embedded key-value metadata storage for process state. In
 * production this is backed by RocksDB (see {@link RocksDbStateStore}):
 * an embedded LSM-tree store colocated with the process engine, so a
 * state read/write never crosses a network hop — that's the actual
 * point of using RocksDB here rather than a networked database, and
 * what "fast local state validation" means concretely.
 */
public interface StateStore {
    void put(String key, String value);
    Optional<String> get(String key);
    void delete(String key);
    List<String> listKeys(String prefix);
    void close();
}
