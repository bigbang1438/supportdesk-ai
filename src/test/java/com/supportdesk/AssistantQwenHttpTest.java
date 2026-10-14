package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static com.supportdesk.AssistantHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reproduces the explicitly opted-in Qwen thought-in-content transport shape without retaining thoughts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:assistant-qwen;DB_CLOSE_DELAY=-1",
    "spring.sql.init.schema-locations=classpath:schema.sql,optional:classpath:assistant-schema.sql",
    "supportdesk.demo-password=Assistant-Test-Only-2026!", "supportdesk.thought-in-content=true"})
class AssistantQwenHttpTest {
    @LocalServerPort int port;
    @Autowired Environment environment;
    @DynamicPropertySource static void qwenProperties(DynamicPropertyRegistry registry) { modelProperties(registry); }
    @BeforeEach void reset() { SCRIPT.set("draft"); ROUND.set(0); REQUESTS.clear(); }
    AssistantHttpTest scenario() { var test = new AssistantHttpTest(); test.port = port; test.environment = environment; return test; }

    @Test void completePrefilledThinkingIsDiscardedBeforeFinalJsonOrStrictXmlToolCalls() throws Exception {
        var scenario = scenario(); var staff = scenario.logged("demo_agent"); long ticket = scenario.ticket(scenario.logged("demo_customer"));
        for (String script : List.of("qwen-native", "qwen-xml")) {
            SCRIPT.set(script); ROUND.set(0); REQUESTS.clear(); var response = scenario.run(staff, ticket, "核对订单并按售后政策起草回复", "ollama");
            assertEquals(201, response.statusCode(), script + response.body());
            assertEquals("pending", data(response).path("state").asText());
            assertFalse(response.body().contains("SECRET_THINKING")); assertFalse(response.body().contains("refund_order"));
            assertEquals(3, REQUESTS.size());
            for (var request : REQUESTS) assertFalse(request.toString().contains("SECRET_THINKING"));
        }
    }

    @Test void unclosedThoughtsMixedNativeAndXmlOrTrailingXmlTextAreRejectedWithoutPersistence() throws Exception {
        var scenario = scenario(); var staff = scenario.logged("demo_agent"); long ticket = scenario.ticket(scenario.logged("demo_customer"));
        for (String script : List.of("qwen-unclosed", "qwen-mixed", "qwen-xml-trailing")) {
            SCRIPT.set(script); ROUND.set(0); var response = scenario.run(staff, ticket, "售后政策", "ollama");
            assertEquals(502, response.statusCode(), script + response.body()); assertFalse(response.body().contains("SECRET_THINKING"));
        }
        assertEquals(0, data(staff.send("GET", base(ticket), null)).size());
    }
}
