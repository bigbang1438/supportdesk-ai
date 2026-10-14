package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:support-session;DB_CLOSE_DELAY=-1",
    "supportdesk.demo-password=Support-Test-Only-2026!"})
class SessionHttpTest {
    @LocalServerPort int port;
    @Test void sessionUsesDatabaseIdentityAndLogoutClearsAuthentication() throws Exception {
        var browser = new SupportHttpClient(port); assertTrue(browser.session().isNull());
        assertEquals(401, browser.send("GET", "/api/v1/orders", null).statusCode());
        browser.login("demo_agent"); var actor = browser.session();
        assertEquals("agent", actor.path("role").asText()); assertEquals(1, actor.path("tenantId").asLong());
        assertTrue(actor.path("userId").asLong() > 0); assertTrue(actor.path("workspaceName").asText().contains("虚拟"));
        assertEquals(200, browser.send("POST", "/api/v1/auth/logout", null).statusCode());
        assertTrue(browser.session().isNull());
    }
    @Test void loginRequiresCsrfAndIncorrectPasswordDoesNotAuthenticate() throws Exception {
        var browser = new SupportHttpClient(port); browser.session();
        assertEquals(403, browser.send("POST", "/api/v1/auth/login", "username=demo_agent&password=wrong", false, "application/x-www-form-urlencoded").statusCode());
        assertEquals(401, browser.send("POST", "/api/v1/auth/login", "username=demo_agent&password=wrong", true, "application/x-www-form-urlencoded").statusCode());
        assertTrue(browser.session().isNull());
    }
}
