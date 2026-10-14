package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static com.supportdesk.AssistantHttpTest.JSON;
import static com.supportdesk.AssistantHttpTest.data;
import static com.supportdesk.AssistantHttpTest.json;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-disabled;DB_CLOSE_DELAY=-1",
    "spring.sql.init.schema-locations=classpath:schema.sql,optional:classpath:assistant-schema.sql",
    "supportdesk.demo-password=Assistant-Test-Only-2026!", "supportdesk.ollama-model=",
    "supportdesk.ollama-url=http://169.254.169.254/metadata"})
class AssistantDisabledHttpTest {
    @LocalServerPort int port;

    @Test void invalidOrMissingLocalModelConfigurationKeepsBaselineAndExplicitDemoUsable() throws Exception {
        var staff = new AssistantHttpTest.Session(port); staff.login("demo_agent", "Assistant-Test-Only-2026!");
        var capabilities = staff.send("GET", "/api/v1/assistant/capabilities", null);
        assertEquals(200, capabilities.statusCode(), capabilities.body()); assertFalse(data(capabilities).path("modelConfigured").asBoolean(true));
        var customer = new AssistantHttpTest.Session(port); customer.login("demo_customer", "Assistant-Test-Only-2026!");
        var orders = customer.send("GET", "/api/v1/orders", null); assertEquals(200, orders.statusCode());
        long order = 0;
        for (var item : data(orders)) if (!item.path("status").asText().equals("cancelled")) { order = item.path("id").asLong(); break; }
        assertTrue(order > 0, "a non-cancelled fictional order is required");
        var created = customer.send("POST", "/api/v1/tickets", json(Map.of("orderId", order, "subject", "无模型基础功能", "body", "演示售后流程")));
        assertEquals(201, created.statusCode(), created.body()); long ticket = data(created).path("id").asLong();
        String path = AssistantHttpTest.base(ticket);
        assertEquals(201, staff.send("POST", path, json(Map.of("question", "售后", "mode", "demo"))).statusCode());
        var unavailable = staff.send("POST", path, json(Map.of("question", "售后", "mode", "ollama")));
        assertEquals(503, unavailable.statusCode(), unavailable.body()); assertFalse(unavailable.body().contains("169.254"));
        assertEquals(403, customer.send("GET", "/api/v1/assistant/capabilities", null).statusCode());
    }
}
