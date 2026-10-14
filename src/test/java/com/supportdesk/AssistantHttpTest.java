package com.supportdesk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real authenticated HTTP, SQL, retrieval and tool execution; only local model HTTP is scripted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-http;DB_CLOSE_DELAY=-1",
    "spring.sql.init.schema-locations=classpath:schema.sql,optional:classpath:assistant-schema.sql",
    "supportdesk.demo-password=Assistant-Test-Only-2026!"})
class AssistantHttpTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final AtomicReference<String> SCRIPT = new AtomicReference<>("draft");
    static final AtomicInteger ROUND = new AtomicInteger();
    static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    static final HttpServer MODEL = startModel();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;

    @DynamicPropertySource static void modelProperties(DynamicPropertyRegistry registry) {
        registry.add("supportdesk.ollama-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort());
        registry.add("supportdesk.ollama-model", () -> "scripted-boundary-test");
    }
    @BeforeEach void resetModel() { SCRIPT.set("draft"); ROUND.set(0); REQUESTS.clear(); }

    @Test void explicitDemoRetrievesChinesePolicyWithExactSourceLinesWithoutMessageOrModelCall() throws Exception {
        var customer = logged("demo_customer"); var staff = logged("demo_agent"); long ticket = ticket(customer);
        String name = "retrieval-" + UUID.randomUUID().toString().substring(0, 8) + ".md";
        var policy = staff.send("POST", "/api/v1/policies", json(Map.of("title", "演示换货流程", "filename", name,
            "content", "# 虚拟流程\r\n换货需要客服核对商品批次。\r\n内部登记后联系客户。")));
        assertEquals(201, policy.statusCode(), policy.body());
        int before = messages(staff, ticket).size();
        var response = run(staff, ticket, "换货商品批次", "demo");
        assertEquals(201, response.statusCode(), response.body());
        var data = data(response); assertEquals("none", data.path("state").asText()); assertTrue(data.path("reply").isNull());
        assertTrue(data.path("answer").asText().contains("未调用模型"));
        var citations = data.path("citations"); assertFalse(citations.isEmpty());
        JsonNode matching = null;
        for (JsonNode citation : citations) if (citation.path("filename").asText().equals(name)) matching = citation;
        assertNotNull(matching, response.body()); assertEquals(1, matching.path("lineStart").asInt());
        assertEquals(3, matching.path("lineEnd").asInt());
        assertEquals("# 虚拟流程\n换货需要客服核对商品批次。\n内部登记后联系客户。", matching.path("text").asText());
        assertEquals(data(policy).path("id").asLong() + ":0", matching.path("chunkId").asText());
        assertEquals(before, messages(staff, ticket).size()); assertTrue(REQUESTS.isEmpty());
    }

    @Test void realToolObservationsReachNextModelTurnAndDraftNeedsExplicitOriginalActorPublish() throws Exception {
        var customer = logged("demo_customer"); var staff = logged("demo_agent"); long ticket = ticket(customer);
        int before = messages(staff, ticket).size(); var response = run(staff, ticket, "核对订单并按售后政策起草回复", "ollama");
        assertEquals(201, response.statusCode(), response.body()); var draft = data(response);
        assertEquals("pending", draft.path("state").asText()); assertEquals("已核对订单，将按演示政策继续处理。", draft.path("reply").asText());
        assertEquals(before, messages(staff, ticket).size()); assertEquals(3, REQUESTS.size());
        assertFalse(REQUESTS.get(0).path("think").asBoolean(true)); assertFalse(REQUESTS.get(0).path("stream").asBoolean(true));
        assertEquals(0, REQUESTS.get(0).path("options").path("temperature").asInt(-1));
        assertEquals(512, REQUESTS.get(0).path("options").path("num_predict").asInt());
        assertEquals(8192, REQUESTS.get(0).path("options").path("num_ctx").asInt());
        assertEquals(600, Instant.parse(draft.path("expiresAt").asText()).getEpochSecond() - Instant.parse(draft.path("createdAt").asText()).getEpochSecond());
        String secondTurn = REQUESTS.get(1).toString(); assertTrue(secondTurn.contains("SD20261013"));
        assertTrue(secondTurn.contains("tool")); assertTrue(secondTurn.contains("chunkId"));
        assertFalse(response.body().contains("SECRET_THINKING"));
        assertFalse(jdbc.queryForObject("SELECT view_json FROM assistant_runs WHERE id = ?", String.class, draft.path("id").asLong()).contains("SECRET_THINKING"));
        String decision = base(ticket) + "/" + draft.path("id").asLong() + "/publish";
        assertEquals(404, logged("demo_agent2").send("POST", decision, json(Map.of("version", draft.path("ticketVersion").asInt()))).statusCode());
        assertEquals(200, staff.send("POST", decision, json(Map.of("version", draft.path("ticketVersion").asInt()))).statusCode());
        assertEquals(before + 1, messages(staff, ticket).size());
        assertEquals("已核对订单，将按演示政策继续处理。", messages(staff, ticket).get(before).path("body").asText());
        assertEquals(409, staff.send("POST", decision, json(Map.of("version", draft.path("ticketVersion").asInt()))).statusCode());
        assertEquals(before + 1, messages(staff, ticket).size());
    }

    @Test void staleOrInventedClientVersionCannotPublishAndLeavesPendingWithoutSideEffects() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer"));
        var created = run(staff, ticket, "按售后政策起草回复", "ollama"); assertEquals(201, created.statusCode(), created.body());
        var draft = data(created); String decision = base(ticket) + "/" + draft.path("id").asLong() + "/publish";
        int version = draft.path("ticketVersion").asInt();
        assertEquals(201, staff.send("POST", "/api/v1/tickets/" + ticket + "/messages", json(Map.of("body", "人工补充说明", "version", version))).statusCode());
        int messageCount = messages(staff, ticket).size(); int eventCount = events(staff, ticket).size();
        for (int attempted : List.of(version, version + 1)) assertEquals(409, staff.send("POST", decision, json(Map.of("version", attempted))).statusCode());
        assertEquals(messageCount, messages(staff, ticket).size()); assertEquals(eventCount, events(staff, ticket).size());
        assertEquals("pending", data(staff.send("GET", base(ticket), null)).get(0).path("state").asText());
    }

    @Test void expiredDiscardedOrTicketMismatchedDraftCannotPublish() throws Exception {
        var staff = logged("demo_agent"); var customer = logged("demo_customer"); long ticket = ticket(customer); long other = ticket(customer);
        var created = run(staff, ticket, "按售后政策起草回复", "ollama"); assertEquals(201, created.statusCode(), created.body());
        var draft = data(created); long id = draft.path("id").asLong(); int version = draft.path("ticketVersion").asInt();
        assertEquals(404, staff.send("POST", base(other) + "/" + id + "/publish", json(Map.of("version", version))).statusCode());
        jdbc.update("UPDATE assistant_runs SET expires_at = ? WHERE id = ?", Instant.now().minusSeconds(1).toString(), id);
        assertEquals(409, staff.send("POST", base(ticket) + "/" + id + "/publish", json(Map.of("version", version))).statusCode());
        assertEquals(200, staff.send("POST", base(ticket) + "/" + id + "/discard", "{}").statusCode());
        assertEquals(409, staff.send("POST", base(ticket) + "/" + id + "/publish", json(Map.of("version", version))).statusCode());
        assertEquals(409, staff.send("POST", base(ticket) + "/" + id + "/discard", "{}").statusCode());
    }

    @Test void concurrentPublishAppendsOneReplyAndOneEvent() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer"));
        var created = run(staff, ticket, "按售后政策起草回复", "ollama"); assertEquals(201, created.statusCode(), created.body()); var draft = data(created);
        String path = base(ticket) + "/" + draft.path("id").asLong() + "/publish"; String body = json(Map.of("version", draft.path("ticketVersion").asInt()));
        int before = messages(staff, ticket).size(); int eventBefore = events(staff, ticket).size();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> staff.send("POST", path, body).statusCode());
            var b = executor.submit(() -> staff.send("POST", path, body).statusCode());
            assertEquals(List.of(200, 409), java.util.stream.Stream.of(a.get(), b.get()).sorted().toList());
        }
        assertEquals(before + 1, messages(staff, ticket).size()); assertEquals(eventBefore + 1, events(staff, ticket).size());
    }

    @Test void policyAndOrderSnapshotsAreTenantBoundAndRunHistoryIsActorBound() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer"));
        var otherStaff = logged("demo_other_agent");
        assertEquals(201, otherStaff.send("POST", "/api/v1/policies", json(Map.of("title", "外部资料", "filename", "private-policy.md", "content", "PRIVATE_TENANT_POLICY"))).statusCode());
        assertEquals(201, run(staff, ticket, "售后政策", "ollama").statusCode());
        for (var request : REQUESTS) assertFalse(request.toString().contains("PRIVATE_TENANT_POLICY"));
        assertEquals(1, data(staff.send("GET", base(ticket), null)).size());
        assertEquals(0, data(logged("demo_agent2").send("GET", base(ticket), null)).size());
        REQUESTS.clear(); assertEquals(404, run(otherStaff, ticket, "外部访问", "ollama").statusCode()); assertTrue(REQUESTS.isEmpty());
        assertEquals(403, run(logged("demo_customer"), ticket, "客户不能运行", "demo").statusCode());
    }

    @Test void unsupportedToolsWrongArgumentsNoObservationAndUnboundCitationsFailWithoutWrites() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer")); int before = messages(staff, ticket).size();
        for (String fixture : List.of("refund", "foreign-order", "wrong-query", "premature-draft", "oversized-draft", "no-tools", "bad-citation", "duplicate-draft", "thinking-content", "invalid-final-field")) {
            SCRIPT.set(fixture); ROUND.set(0); var response = run(staff, ticket, "售后政策", "ollama");
            assertEquals(502, response.statusCode(), fixture + ": " + response.body());
            assertFalse(response.body().contains("SECRET_THINKING")); assertFalse(response.body().contains("Exception"));
        }
        assertEquals(before, messages(staff, ticket).size()); assertEquals(0, data(staff.send("GET", base(ticket), null)).size());
    }

    @Test void loopingAndOversizedModelResponsesFailWithinBoundedCalls() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer"));
        SCRIPT.set("loop"); var response = run(staff, ticket, "售后政策", "ollama"); assertEquals(502, response.statusCode(), response.body());
        assertTrue(ROUND.get() <= 4, "model and tool steps share an eight-step budget");
        SCRIPT.set("oversized"); ROUND.set(0); assertEquals(502, run(staff, ticket, "售后政策", "ollama").statusCode());
        SCRIPT.set("redirect"); ROUND.set(0); assertEquals(502, run(staff, ticket, "售后政策", "ollama").statusCode());
    }

    @Test void thoughtInContentRequiresExplicitOptInEvenWhenFinalJsonAndToolsAreValid() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer")); SCRIPT.set("qwen-native");
        var response = run(staff, ticket, "售后政策", "ollama");
        assertEquals(502, response.statusCode(), response.body()); assertFalse(response.body().contains("SECRET_THINKING"));
        assertEquals(0, data(staff.send("GET", base(ticket), null)).size());
    }

    @Test void unknownFieldsBlankQuestionsModesAndCsrfAreRejectedBeforeCallingModel() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer"));
        for (String body : List.of("{\"question\":\"x\",\"mode\":\"ollama\",\"tenantId\":2}", "{\"question\":\" \",\"mode\":\"demo\"}",
            "{\"question\":\"x\",\"mode\":\"paid\"}", json(Map.of("question", "x".repeat(1501), "mode", "demo"))))
            assertEquals(400, staff.send("POST", base(ticket), body).statusCode());
        assertEquals(403, staff.send("POST", base(ticket), "{\"question\":\"x\",\"mode\":\"demo\"}", false, "application/json").statusCode());
        assertTrue(REQUESTS.isEmpty());
    }

    @Test void longPolicyLinesAreChunkedBelowLimitWithOriginalLineNumbers() throws Exception {
        var staff = logged("demo_agent"); long ticket = ticket(logged("demo_customer"));
        String name = "long-" + UUID.randomUUID().toString().substring(0, 8) + ".md";
        assertEquals(201, staff.send("POST", "/api/v1/policies", json(Map.of("title", "长行流程", "filename", name, "content", "首行\n" + "长行换货".repeat(200)))).statusCode());
        var response = run(staff, ticket, "长行换货", "demo"); assertEquals(201, response.statusCode(), response.body());
        for (JsonNode citation : data(response).path("citations")) {
            assertTrue(citation.path("text").asText().length() <= 600);
            if (citation.path("filename").asText().equals(name)) assertEquals(2, citation.path("lineEnd").asInt());
        }
    }

    Session logged(String username) throws Exception { var s = new Session(port); s.login(username, environment.getProperty("supportdesk.demo-password")); return s; }
    long ticket(Session customer) throws Exception {
        var orders = data(customer.send("GET", "/api/v1/orders?page=0&size=20", null)); long order = 0;
        for (JsonNode item : orders) if (!item.path("status").asText().equals("cancelled")) { order = item.path("id").asLong(); break; }
        assertTrue(order > 0); var response = customer.send("POST", "/api/v1/tickets", json(Map.of("orderId", order, "subject", "商品售后咨询 " + UUID.randomUUID(), "body", "请核对订单并说明演示换货政策。")));
        assertEquals(201, response.statusCode(), response.body()); return data(response).path("id").asLong();
    }
    HttpResponse<String> run(Session s, long ticket, String question, String mode) throws Exception { return s.send("POST", base(ticket), json(Map.of("question", question, "mode", mode))); }
    JsonNode messages(Session s, long ticket) throws Exception { return data(s.send("GET", "/api/v1/tickets/" + ticket + "/messages", null)); }
    JsonNode events(Session s, long ticket) throws Exception { return data(s.send("GET", "/api/v1/tickets/" + ticket + "/events", null)); }
    static String base(long ticket) { return "/api/v1/tickets/" + ticket + "/assistant/runs"; }
    static String json(Object value) { return JSON.writeValueAsString(value); }
    static JsonNode data(HttpResponse<String> response) { return JSON.readTree(response.body()).path("data"); }

    static HttpServer startModel() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", exchange -> {
                JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes()); REQUESTS.add(request); int round = ROUND.getAndIncrement(); String script = SCRIPT.get();
                if (script.equals("redirect")) { exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/never"); exchange.sendResponseHeaders(302, -1); exchange.close(); return; }
                Object message;
                if (script.equals("refund")) message = calls(call("refund_order", Map.of()));
                else if (script.equals("foreign-order")) message = calls(call("lookup_order", Map.of("orderId", 99999)));
                else if (script.equals("wrong-query")) message = calls(call("search_policy", Map.of("query", "售后", "tenantId", 2)));
                else if (script.equals("premature-draft")) message = calls(call("propose_reply", Map.of("body", "未读取事实的草稿")));
                else if (script.equals("no-tools")) message = Map.of("role", "assistant", "content", "{\"answer\":\"未经核对的回答\",\"citations\":[]}");
                else if (script.equals("thinking-content")) message = Map.of("role", "assistant", "content", "<think>SECRET_THINKING</think>回答");
                else if (script.equals("loop")) message = calls(call("lookup_order", Map.of()));
                else if (round == 0) message = calls(call("search_policy", Map.of("query", "售后 换货")), call("lookup_order", Map.of()));
                else if (script.equals("bad-citation")) message = Map.of("role", "assistant", "content", "{\"answer\":\"演示规则 [99]\",\"citations\":[99]}");
                else if (script.equals("invalid-final-field")) message = Map.of("role", "assistant", "content", "{\"answer\":\"演示规则 [1]\",\"citations\":[1],\"reasoning\":\"SECRET_THINKING\"}");
                else if (round == 1) {
                    boolean observed = request.path("messages").toString().contains("chunkId") && request.path("messages").toString().contains("SD20261013");
                    if (!observed) message = Map.of("role", "assistant", "content", "{\"answer\":\"missing real observation\",\"citations\":[]}");
                    else if (script.equals("duplicate-draft")) message = calls(call("propose_reply", Map.of("body", "第一份草稿")), call("propose_reply", Map.of("body", "第二份草稿")));
                    else if (script.equals("oversized-draft")) message = calls(call("propose_reply", Map.of("body", "x".repeat(1501))));
                    else message = calls(call("propose_reply", Map.of("body", "已核对订单，将按演示政策继续处理。")));
                } else message = Map.of("role", "assistant", "thinking", "SECRET_THINKING", "content", "{\"answer\":\"已核对订单并查到演示售后政策 [1]。草稿等待客服确认。\",\"citations\":[1]}");
                if (script.startsWith("qwen-")) {
                    @SuppressWarnings("unchecked") var wire = (Map<String, Object>) message;
                    boolean xml = script.equals("qwen-xml") || script.equals("qwen-mixed") || script.equals("qwen-xml-trailing");
                    String finalContent = (String) wire.getOrDefault("content", "");
                    if (xml && wire.containsKey("tool_calls")) {
                        StringBuilder blocks = new StringBuilder();
                        for (Object rawCall : (List<?>) wire.get("tool_calls")) {
                            JsonNode function = JSON.valueToTree(rawCall).path("function");
                            blocks.append("<tool_call>").append(json(Map.of("name", function.path("name").asText(), "arguments", function.path("arguments")))).append("</tool_call>");
                        }
                        finalContent = blocks.toString();
                    }
                    String content = "SECRET_THINKING <tool_call>{\"name\":\"refund_order\",\"arguments\":{}}</tool_call>" +
                        (script.equals("qwen-unclosed") ? "" : "</think>") + finalContent;
                    if (script.equals("qwen-xml-trailing")) content += "arbitrary trailing prose";
                    if (script.equals("qwen-mixed")) message = Map.of("role", "assistant", "content", content, "tool_calls", wire.get("tool_calls"));
                    else if (xml || script.equals("qwen-unclosed")) message = Map.of("role", "assistant", "content", content);
                    else if (wire.containsKey("tool_calls")) message = Map.of("role", "assistant", "content", "", "tool_calls", wire.get("tool_calls"));
                    else message = Map.of("role", "assistant", "content", content);
                }
                String response = script.equals("oversized") ? "x".repeat(65537) : json(Map.of("message", message, "done", true));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static Map<String, Object> call(String name, Map<String, Object> args) { return Map.of("function", Map.of("name", name, "arguments", args)); }
    @SafeVarargs static Map<String, Object> calls(Map<String, Object>... calls) { return Map.of("role", "assistant", "content", "", "tool_calls", List.of(calls)); }

    static final class Session {
        final String origin; final HttpClient client; String csrf;
        Session(int port) { origin = "http://127.0.0.1:" + port; client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); }
        HttpResponse<String> send(String method, String path, String body) throws Exception { return send(method, path, body, true, "application/json"); }
        HttpResponse<String> send(String method, String path, String body, boolean token, String contentType) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).header("Content-Type", contentType).header("Origin", origin);
            if (token && csrf != null) request.header("X-CSRF-TOKEN", csrf);
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        void csrf() throws Exception { var response = send("GET", "/api/v1/auth/session", null); assertEquals(200, response.statusCode(), response.body()); csrf = JSON.readTree(response.body()).path("csrf").path("token").asText(); }
        void login(String username, String password) throws Exception {
            csrf(); var response = send("POST", "/api/v1/auth/login", "username=" + username + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), true, "application/x-www-form-urlencoded");
            assertEquals(200, response.statusCode(), response.body()); csrf();
        }
    }
}
