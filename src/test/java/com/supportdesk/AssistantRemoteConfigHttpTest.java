package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static com.supportdesk.AssistantHttpTest.data;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-remote-config;DB_CLOSE_DELAY=-1",
    "spring.sql.init.schema-locations=classpath:schema.sql,optional:classpath:assistant-schema.sql",
    "supportdesk.demo-password=Assistant-Test-Only-2026!",
    "supportdesk.ollama-model=scripted-boundary-test", "supportdesk.ollama-url=https://example.com"})
class AssistantRemoteConfigHttpTest {
    @LocalServerPort int port;

    @Test void nonLoopbackModelDestinationIsDisabledWithoutBlockingApplicationStartup() throws Exception {
        var staff = new AssistantHttpTest.Session(port); staff.login("demo_agent", "Assistant-Test-Only-2026!");
        var response = staff.send("GET", "/api/v1/assistant/capabilities", null);
        assertEquals(200, response.statusCode(), response.body()); assertFalse(data(response).path("modelConfigured").asBoolean(true));
        assertEquals(200, staff.send("GET", "/api/v1/tickets", null).statusCode());
    }
}
