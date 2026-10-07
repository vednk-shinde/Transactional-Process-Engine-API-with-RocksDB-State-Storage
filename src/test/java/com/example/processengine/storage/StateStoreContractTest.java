package com.example.processengine.storage;

import com.example.processengine.core.ProcessEngine;
import com.example.processengine.core.ProcessState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The same behavioural contract is asserted against both StateStore implementations. */
class StateStoreContractTest {

    @TempDir
    Path tmp;

    private void contract(StateStore store) {
        assertTrue(store.get("missing").isEmpty());

        store.put("process:a", "1");
        store.put("process:b", "2");
        store.put("other:c", "3");
        store.put("process:a", "updated");

        assertEquals("updated", store.get("process:a").orElseThrow());
        assertEquals(List.of("process:a", "process:b"), store.listKeys("process:"));
        assertEquals(List.of("other:c"), store.listKeys("other:"));
        assertTrue(store.listKeys("nothing:").isEmpty());

        store.delete("process:a");
        assertTrue(store.get("process:a").isEmpty());
        store.delete("never-existed");
        assertEquals(List.of("process:b"), store.listKeys("process:"));
    }

    @Test
    void inMemoryStoreHonoursTheContract() {
        InMemoryStateStore store = new InMemoryStateStore();
        contract(store);
        store.close();
    }

    @Test
    void rocksDbStoreHonoursTheContract() {
        RocksDbStateStore store = new RocksDbStateStore(tmp.resolve("contract").toString());
        try {
            contract(store);
        } finally {
            store.close();
        }
    }

    @Test
    void rocksDbDataSurvivesCloseAndReopen() {
        String path = tmp.resolve("durable").toString();
        RocksDbStateStore first = new RocksDbStateStore(path);
        ProcessEngine engine = new ProcessEngine(first);
        engine.create("p1", "order", Map.of("qty", "7"));
        engine.pause("p1");
        first.close();

        RocksDbStateStore second = new RocksDbStateStore(path);
        try {
            var reloaded = new ProcessEngine(second).find("p1").orElseThrow();
            assertEquals(ProcessState.PAUSED, reloaded.getState());
            assertEquals("7", reloaded.getVariable("qty"));
        } finally {
            second.close();
        }
    }

    @Test
    void rocksDbHandlesConcurrentWriters() {
        RocksDbStateStore store = new RocksDbStateStore(tmp.resolve("concurrent").toString());
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 2000; i++) {
                int n = i;
                pool.submit(() -> store.put(String.format("process:%05d", n), "v" + n));
            }
        }
        try {
            assertEquals(2000, store.listKeys("process:").size());
            assertEquals("v1234", store.get("process:01234").orElseThrow());
        } finally {
            store.close();
        }
    }
}
