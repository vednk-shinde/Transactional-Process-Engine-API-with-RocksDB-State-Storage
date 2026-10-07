package com.example.processengine.api;

import com.example.processengine.agent.AgentOrchestrator;
import com.example.processengine.core.ProcessEngine;
import com.example.processengine.core.ProcessInstance;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * REST surface over the process engine, deliberately built on the JDK's
 * built-in {@code com.sun.net.httpserver.HttpServer} rather than a
 * framework — this keeps the API layer runnable with zero external
 * dependencies, so it can be started and hit with real HTTP requests in
 * any environment with a JDK, no Maven Central access required. A real
 * deployment would likely use Spring/Micronaut for this layer; the
 * versioning and backward-compatibility POLICY below is what matters and
 * transfers directly regardless of framework.
 *
 * API VERSIONING / BACKWARD-COMPATIBILITY CONTRACT (the actual resume claim):
 *   - Every route is prefixed /v1/ — a breaking change ships as /v2/,
 *     never by mutating /v1/'s behavior in place.
 *   - Response JSON only ever GAINS optional fields between releases;
 *     an existing field is never removed or repurposed without a major
 *     version bump. Consumers written against /v1/ today are guaranteed
 *     to keep parsing successfully against /v1/ tomorrow.
 *   - A field slated for removal is marked deprecated (documented, still
 *     populated) for at least one full version before it disappears in
 *     the next major version — never removed silently in a patch.
 */
public final class RestApiServer {
    private final ProcessEngine engine;
    private final AgentOrchestrator orchestrator;
    private final HttpServer server;

    private static final Pattern PROCESS_ID_PATH =
            Pattern.compile("^/v1/processes/([^/]+)(?:/(pause|resume|mutate|complete))?/?$");

    public RestApiServer(int port, ProcessEngine engine, AgentOrchestrator orchestrator) throws IOException {
        this.engine = engine;
        this.orchestrator = orchestrator;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/v1/processes", new ProcessesHandler());
        server.createContext("/v1/agent/instruction", new AgentHandler());
        server.createContext("/healthz", exchange -> respondJson(exchange, 200, "{\"status\":\"ok\"}"));
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    /** The port actually bound (useful when constructed with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private final class ProcessesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                URI uri = exchange.getRequestURI();
                String method = exchange.getRequestMethod();
                Matcher m = PROCESS_ID_PATH.matcher(uri.getPath());

                if (method.equals("POST") && uri.getPath().equals("/v1/processes")) {
                    handleCreate(exchange);
                    return;
                }
                if (m.matches()) {
                    String processId = m.group(1);
                    String action = m.group(2);
                    if (method.equals("GET") && action == null) {
                        handleGet(exchange, processId);
                        return;
                    }
                    if (method.equals("POST") && "pause".equals(action)) {
                        respondJson(exchange, 200, toJson(engine.pause(processId)));
                        return;
                    }
                    if (method.equals("POST") && "resume".equals(action)) {
                        respondJson(exchange, 200, toJson(engine.resume(processId, true)));
                        return;
                    }
                    if (method.equals("POST") && "complete".equals(action)) {
                        respondJson(exchange, 200, toJson(engine.complete(processId)));
                        return;
                    }
                    if (method.equals("POST") && "mutate".equals(action)) {
                        Map<String, String> updates = parseSimpleJsonBody(readBody(exchange));
                        respondJson(exchange, 200, toJson(engine.mutate(processId, updates)));
                        return;
                    }
                }
                respondJson(exchange, 404, "{\"error\":\"not_found\"}");
            } catch (IllegalArgumentException | IllegalStateException e) {
                respondJson(exchange, statusFor(e), "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            } catch (Exception e) {
                respondJson(exchange, 500, "{\"error\":\"internal_error\"}");
            }
        }

        private void handleCreate(HttpExchange exchange) throws IOException {
            Map<String, String> body = parseSimpleJsonBody(readBody(exchange));
            String processId = body.get("processId");
            String processType = body.get("processType");
            if (processId == null || processType == null) {
                respondJson(exchange, 400, "{\"error\":\"processId and processType are required\"}");
                return;
            }
            ProcessInstance instance = engine.create(processId, processType);
            respondJson(exchange, 201, toJson(instance));
        }

        private void handleGet(HttpExchange exchange, String processId) throws IOException {
            var found = engine.find(processId);
            if (found.isEmpty()) {
                respondJson(exchange, 404, "{\"error\":\"not_found\"}");
                return;
            }
            respondJson(exchange, 200, toJson(found.get()));
        }
    }

    private final class AgentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equals("POST")) {
                respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            try {
                Map<String, String> body = parseSimpleJsonBody(readBody(exchange));
                String instruction = body.get("instruction");
                if (instruction == null) {
                    respondJson(exchange, 400, "{\"error\":\"instruction is required\"}");
                    return;
                }
                String result = orchestrator.execute(instruction);
                respondJson(exchange, 200, "{\"result\":\"" + escape(result) + "\"}");
            } catch (IllegalArgumentException | IllegalStateException e) {
                respondJson(exchange, statusFor(e), "{\"error\":\"" + escape(e.getMessage()) + "\"}");
            }
        }
    }

    /** 404 for unknown processes, 409 for duplicates, 400 for every other rejected request. */
    private static int statusFor(RuntimeException e) {
        if (e instanceof com.example.processengine.core.ProcessNotFoundException) return 404;
        if (e instanceof com.example.processengine.core.DuplicateProcessException) return 409;
        return 400;
    }

    // --- tiny hand-rolled JSON helpers, kept dependency-free on purpose ---

    private static String toJson(ProcessInstance instance) {
        StringBuilder vars = new StringBuilder();
        instance.variablesSnapshot().forEach((k, v) -> {
            if (vars.length() > 0) vars.append(",");
            vars.append("\"").append(escape(k)).append("\":\"").append(escape(v)).append("\"");
        });
        return "{"
                + "\"processId\":\"" + escape(instance.getProcessId()) + "\","
                + "\"processType\":\"" + escape(instance.getProcessType()) + "\","
                + "\"state\":\"" + instance.getState() + "\","
                + "\"variables\":{" + vars + "}"
                + "}";
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Deliberately minimal flat-JSON-object parser — good enough for {"key":"value",...} request bodies, not a general JSON parser. */
    private static Map<String, String> parseSimpleJsonBody(String body) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
