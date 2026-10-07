package com.example.processengine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container healthcheck: exits 0 when the local REST API answers /healthz. */
public final class HealthCheck {

    private HealthCheck() {}

    public static void main(String[] args) throws Exception {
        String port = System.getenv().getOrDefault("HTTP_PORT", "8080");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/healthz"))
                .timeout(Duration.ofSeconds(2)).build();
        HttpResponse<Void> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        System.exit(response.statusCode() == 200 ? 0 : 1);
    }
}
