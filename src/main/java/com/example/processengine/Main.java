package com.example.processengine;

import com.example.processengine.agent.AgentOrchestrator;
import com.example.processengine.api.RestApiServer;
import com.example.processengine.core.ProcessEngine;
import com.example.processengine.core.ProcessInstance;
import com.example.processengine.core.ProcessState;
import com.example.processengine.grpc.ProcessEngineGrpcService;
import com.example.processengine.storage.InMemoryStateStore;
import com.example.processengine.storage.RocksDbStateStore;
import com.example.processengine.storage.StateStore;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point. Configuration comes from environment variables:
 * <ul>
 *   <li>{@code STORE}: {@code rocksdb} (default) or {@code memory}</li>
 *   <li>{@code DB_PATH}: RocksDB directory (default {@code ./data})</li>
 *   <li>{@code HTTP_PORT} (default 8080) and {@code GRPC_PORT} (default 9090)</li>
 * </ul>
 * Run with {@code --demo} to execute a scripted walk-through of the engine and agent and exit.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--demo")) {
            demo();
            return;
        }

        StateStore store = "memory".equalsIgnoreCase(env("STORE", "rocksdb"))
                ? new InMemoryStateStore()
                : new RocksDbStateStore(env("DB_PATH", "./data"));
        ProcessEngine engine = new ProcessEngine(store);
        AgentOrchestrator orchestrator = new AgentOrchestrator(engine);

        RestApiServer rest = new RestApiServer(Integer.parseInt(env("HTTP_PORT", "8080")), engine, orchestrator);
        Server grpc = ServerBuilder.forPort(Integer.parseInt(env("GRPC_PORT", "9090")))
                .addService(new ProcessEngineGrpcService(engine, orchestrator))
                .build();
        rest.start();
        grpc.start();
        System.out.printf("Process engine up: REST :%d, gRPC :%d, store=%s%n",
                rest.port(), grpc.getPort(), store.getClass().getSimpleName());

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            rest.stop();
            grpc.shutdown();
            store.close();
            stopped.countDown();
        }));
        stopped.await();
    }

    private static void demo() {
        ProcessEngine engine = new ProcessEngine(new InMemoryStateStore());
        AgentOrchestrator agent = new AgentOrchestrator(engine);

        ProcessInstance p = engine.create("ORD-1001", "order-fulfillment");
        System.out.println("Created: " + p);
        engine.mutate("ORD-1001", Map.of("inventory", "0"));
        engine.pause("ORD-1001");
        System.out.println(agent.execute("resume process ORD-1001 if inventory > 0"));
        System.out.println(agent.execute("set inventory=5 on process ORD-1001"));
        System.out.println(agent.execute("resume process ORD-1001 if inventory > 0"));
        ProcessInstance end = engine.find("ORD-1001").orElseThrow();
        System.out.println("Final: " + end);
        if (end.getState() != ProcessState.RUNNING) {
            throw new AssertionError("expected RUNNING");
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
