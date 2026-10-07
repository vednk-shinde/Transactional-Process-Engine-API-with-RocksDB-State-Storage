package com.example.processengine.core;

import com.example.processengine.storage.InMemoryStateStore;
import com.example.processengine.storage.StateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessEngineTest {

    private StateStore store;
    private ProcessEngine engine;

    @BeforeEach
    void setUp() {
        store = new InMemoryStateStore();
        engine = new ProcessEngine(store);
    }

    @Test
    void createStartsRunningWithInitialVariables() {
        ProcessInstance p = engine.create("p1", "order", Map.of("sku", "A1"));
        assertEquals(ProcessState.RUNNING, p.getState());
        assertEquals("A1", p.getVariable("sku"));
    }

    @Test
    void duplicateAndBlankCreatesAreRejected() {
        engine.create("p1", "order");
        assertThrows(DuplicateProcessException.class, () -> engine.create("p1", "order"));
        assertThrows(IllegalArgumentException.class, () -> engine.create(" ", "order"));
        assertThrows(IllegalArgumentException.class, () -> engine.create("p2", null));
    }

    @Test
    void pauseThenResumeRoundTrips() {
        engine.create("p1", "order");
        assertEquals(ProcessState.PAUSED, engine.pause("p1").getState());
        assertEquals(ProcessState.RUNNING, engine.resume("p1", true).getState());
    }

    @Test
    void invalidTransitionsAreRefused() {
        engine.create("p1", "order");
        assertThrows(IllegalStateException.class, () -> engine.resume("p1", true)); // not paused
        engine.pause("p1");
        assertThrows(IllegalStateException.class, () -> engine.pause("p1"));       // already paused
        assertThrows(IllegalStateException.class, () -> engine.resume("p1", false)); // condition unmet
        assertEquals(ProcessState.PAUSED, engine.find("p1").orElseThrow().getState());
    }

    @Test
    void terminalProcessesAreFrozen() {
        engine.create("p1", "order");
        engine.complete("p1");
        assertThrows(IllegalStateException.class, () -> engine.mutate("p1", Map.of("a", "b")));
        assertThrows(IllegalStateException.class, () -> engine.complete("p1"));
        assertThrows(IllegalStateException.class, () -> engine.fail("p1", "x"));

        engine.create("p2", "order");
        ProcessInstance failed = engine.fail("p2", "payment declined");
        assertEquals(ProcessState.FAILED, failed.getState());
        assertEquals("payment declined", failed.getLastError());
    }

    @Test
    void unknownProcessRaisesNotFound() {
        assertThrows(ProcessNotFoundException.class, () -> engine.pause("ghost"));
        assertTrue(engine.find("ghost").isEmpty());
    }

    @Test
    void stateSurvivesRestartOverTheSameStore() {
        engine.create("p1", "order", Map.of("qty", "3"));
        engine.pause("p1");

        ProcessEngine restarted = new ProcessEngine(store);
        ProcessInstance reloaded = restarted.find("p1").orElseThrow();
        assertEquals(ProcessState.PAUSED, reloaded.getState());
        assertEquals("3", reloaded.getVariable("qty"));
        assertEquals(ProcessState.RUNNING, restarted.resume("p1", true).getState());
        assertEquals(List.of("p1"), restarted.listProcessIds());
    }

    @Test
    void exactlyOneConcurrentCreateWins() throws Exception {
        int threads = 32;
        AtomicInteger wins = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    try {
                        engine.create("race", "order");
                        wins.incrementAndGet();
                    } catch (DuplicateProcessException ignored) {
                        // expected for every loser
                    }
                    return null;
                });
            }
            start.countDown();
        }
        assertEquals(1, wins.get());
    }

    @Test
    void concurrentMutationsAreAllApplied() throws Exception {
        engine.create("p1", "order");
        int writers = 50;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < writers; i++) {
                int n = i;
                pool.submit(() -> engine.mutate("p1", Map.of("k" + n, "v" + n)));
            }
        }
        assertEquals(writers, engine.find("p1").orElseThrow().variablesSnapshot().size());
        assertEquals(writers, new ProcessEngine(store).find("p1").orElseThrow().variablesSnapshot().size());
    }
}
