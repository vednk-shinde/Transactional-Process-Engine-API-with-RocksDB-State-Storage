package com.example.processengine.api;

import com.example.processengine.agent.AgentOrchestrator;
import com.example.processengine.core.ProcessEngine;
import com.example.processengine.storage.InMemoryStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestApiServerTest {

    private RestApiServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private String base;

    @BeforeEach
    void start() throws Exception {
        ProcessEngine engine = new ProcessEngine(new InMemoryStateStore());
        server = new RestApiServer(0, engine, new AgentOrchestrator(engine));
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void create(String id) throws Exception {
        assertEquals(201, call("POST", "/v1/processes", "{\"processId\":\"" + id + "\",\"processType\":\"order\"}").statusCode());
    }

    @Test
    void healthCheck() throws Exception {
        HttpResponse<String> response = call("GET", "/healthz", null);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ok"));
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        create("p1");
        assertTrue(call("GET", "/v1/processes/p1", null).body().contains("\"state\":\"RUNNING\""));
        assertTrue(call("POST", "/v1/processes/p1/pause", null).body().contains("PAUSED"));
        assertTrue(call("POST", "/v1/processes/p1/mutate", "{\"inventory\":\"5\"}").body().contains("\"inventory\":\"5\""));
        assertTrue(call("POST", "/v1/processes/p1/resume", null).body().contains("RUNNING"));
        assertTrue(call("POST", "/v1/processes/p1/complete", null).body().contains("COMPLETED"));
    }

    @Test
    void errorsMapToTheRightStatusCodes() throws Exception {
        create("p1");
        assertEquals(409, call("POST", "/v1/processes", "{\"processId\":\"p1\",\"processType\":\"order\"}").statusCode());
        assertEquals(400, call("POST", "/v1/processes", "{\"processId\":\"p2\"}").statusCode());
        assertEquals(404, call("GET", "/v1/processes/ghost", null).statusCode());
        assertEquals(404, call("POST", "/v1/processes/ghost/pause", null).statusCode());
        assertEquals(400, call("POST", "/v1/processes/p1/resume", null).statusCode()); // not paused
        assertEquals(404, call("GET", "/v1/processes/p1/unknown-action", null).statusCode());
        assertEquals(404, call("DELETE", "/v1/processes/p1", null).statusCode());
    }

    @Test
    void agentInstructionEndpoint() throws Exception {
        create("p1");
        call("POST", "/v1/processes/p1/mutate", "{\"inventory\":\"0\"}");
        call("POST", "/v1/processes/p1/pause", null);

        HttpResponse<String> refused = call("POST", "/v1/agent/instruction",
                "{\"instruction\":\"resume process p1 if inventory > 0\"}");
        assertEquals(200, refused.statusCode());
        assertTrue(refused.body().contains("Resume refused"));

        assertEquals(400, call("POST", "/v1/agent/instruction", "{\"instruction\":\"do something odd\"}").statusCode());
        assertEquals(400, call("POST", "/v1/agent/instruction", "{}").statusCode());
        assertEquals(404, call("POST", "/v1/agent/instruction", "{\"instruction\":\"pause process nope\"}").statusCode());
        assertEquals(405, call("GET", "/v1/agent/instruction", null).statusCode());
    }
}
