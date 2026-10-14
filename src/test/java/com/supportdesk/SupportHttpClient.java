package com.supportdesk;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

final class SupportHttpClient {
    static final String PASSWORD = "Support-Test-Only-2026!";
    static final JsonMapper JSON = JsonMapper.builder().build();
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    final String origin;
    String csrf;
    SupportHttpClient(int port) { origin = "http://127.0.0.1:" + port; }
    HttpResponse<String> send(String method, String path, String body) throws Exception { return send(method, path, body, true, "application/json"); }
    HttpResponse<String> send(String method, String path, String body, boolean token, String type) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).header("Content-Type", type);
        if (token && csrf != null) request.header("X-CSRF-TOKEN", csrf);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode session() throws Exception {
        var response = send("GET", "/api/v1/auth/session", null);
        assertEquals(200, response.statusCode(), response.body());
        var data = JSON.readTree(response.body()); csrf = data.path("csrf").path("token").asText();
        assertFalse(csrf.isBlank()); return data.path("data");
    }
    void login(String username) throws Exception {
        session();
        var response = send("POST", "/api/v1/auth/login", "username=" + username + "&password=" + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8), true, "application/x-www-form-urlencoded");
        assertEquals(200, response.statusCode(), response.body()); session();
    }
    static JsonNode data(HttpResponse<String> response) { return JSON.readTree(response.body()).path("data"); }
    static String json(Object value) { return JSON.writeValueAsString(value); }
}
