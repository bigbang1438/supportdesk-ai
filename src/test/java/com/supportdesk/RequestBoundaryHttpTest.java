package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.supportdesk.SupportHttpClient.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:support-request-boundary;DB_CLOSE_DELAY=-1",
    "supportdesk.demo-password=Support-Test-Only-2026!"})
class RequestBoundaryHttpTest {
    @LocalServerPort int port;
    @Test void chunkedMutationWithUnknownLengthCannotBypassRequestLimit() throws Exception {
        var browser=new SupportHttpClient(port);browser.login("demo_customer");
        byte[] bytes=json(Map.of("orderId",1,"subject","未知长度请求","body","x".repeat(26000))).getBytes(StandardCharsets.UTF_8);
        var request=HttpRequest.newBuilder(URI.create(browser.origin+"/api/v1/tickets")).version(HttpClient.Version.HTTP_1_1).header("Content-Type","application/json").header("X-CSRF-TOKEN",browser.csrf).POST(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(bytes))).build();
        var response=browser.client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(413,response.statusCode(),response.body());
        assertEquals(0,data(browser.send("GET","/api/v1/summary",null)).path("total").asLong());
        assertEquals(200,browser.send("POST","/api/v1/auth/logout",null).statusCode());assertTrue(browser.session().isNull());
    }
}
