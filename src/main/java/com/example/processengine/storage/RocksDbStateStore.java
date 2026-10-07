package com.example.processengine.storage;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Embedded RocksDB key-value store: no network hop, sub-millisecond local reads and writes, and a
 * sorted keyspace so prefix scans are cheap.
 *
 * <p>Tuning: a 64MB write buffer (fewer, larger flushes under write pressure) and WAL-durable writes
 * that are not fsync'd individually. A crash of the process loses nothing; a power loss can lose the
 * last few milliseconds of writes, which is the deliberate durability/throughput trade-off.
 */
public final class RocksDbStateStore implements StateStore {

    static {
        RocksDB.loadLibrary();
    }

    private final RocksDB db;
    private final Options options;
    private final WriteOptions writeOptions = new WriteOptions().setSync(false);

    public RocksDbStateStore(String dbPath) {
        this.options = new Options()
                .setCreateIfMissing(true)
                .setWriteBufferSize(64L * 1024 * 1024)
                .setMaxWriteBufferNumber(3)
                .setMinWriteBufferNumberToMerge(1);
        try {
            Files.createDirectories(Path.of(dbPath));
            this.db = RocksDB.open(options, dbPath);
        } catch (Exception e) {
            options.close();
            throw new IllegalStateException("Failed to open RocksDB at " + dbPath, e);
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            db.put(writeOptions, bytes(key), bytes(value));
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to put key " + key, e);
        }
    }

    @Override
    public Optional<String> get(String key) {
        try {
            byte[] value = db.get(bytes(key));
            return value == null ? Optional.empty() : Optional.of(new String(value, StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to get key " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            db.delete(writeOptions, bytes(key));
        } catch (RocksDBException e) {
            throw new IllegalStateException("Failed to delete key " + key, e);
        }
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator()) {
            for (iterator.seek(bytes(prefix)); iterator.isValid(); iterator.next()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) break; // sorted iteration: first non-match ends the range
                keys.add(key);
            }
        }
        return keys;
    }

    @Override
    public void close() {
        writeOptions.close();
        db.close();
        options.close();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
