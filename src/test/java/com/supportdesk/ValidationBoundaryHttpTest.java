package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.supportdesk.SupportHttpClient.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:support-validation-boundary;DB_CLOSE_DELAY=-1",
    "supportdesk.demo-password=Support-Test-Only-2026!"})
class ValidationBoundaryHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Test void validEightyCharacterFilenameIsAcceptedAndEightyOneIsRejected() throws Exception {
        var staff=new SupportHttpClient(port);staff.login("demo_agent");String filename="x".repeat(77)+".md";
        var response=staff.send("POST","/api/v1/policies",json(Map.of("title","边界资料","filename",filename,"content","虚拟资料")));assertEquals(201,response.statusCode(),response.body());
        assertEquals(filename,data(response).path("filename").asText());
        assertEquals(400,staff.send("POST","/api/v1/policies",json(Map.of("title","过长资料","filename","x".repeat(78)+".md","content","虚拟资料"))).statusCode());
    }
    @Test void untrustedHostCannotReadEvenPublicSession() throws Exception {
        try(var socket=new Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000);socket.getOutputStream().write("GET /api/v1/auth/session HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertTrue(response.startsWith("HTTP/1.1 403"),response);assertTrue(response.contains("invalid_host"));
        }
    }
    @Test void failingAuditInsertRollsBackReplyAndTicketVersionWithoutExposingSql() throws Exception {
        var customer=new SupportHttpClient(port);customer.login("demo_customer");
        var created=customer.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject","事务失败验证","body","仅用于自己的测试数据库。")));assertEquals(201,created.statusCode(),created.body());long id=data(created).path("id").asLong();
        jdbc.execute("ALTER TABLE ticket_events ADD CONSTRAINT test_reject_reply CHECK(action <> 'reply')");
        try {
            var response=customer.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","不能部分提交","version",0)));assertEquals(500,response.statusCode());assertEquals("internal_error",JSON.readTree(response.body()).path("error").path("code").asText());
            assertFalse(response.body().contains("test_reject_reply"));assertFalse(response.body().contains("INSERT"));
            assertEquals(0,data(customer.send("GET","/api/v1/tickets/"+id,null)).path("version").asInt());
            assertEquals(0,data(customer.send("GET","/api/v1/tickets/"+id+"/messages",null)).size());assertEquals(1,data(customer.send("GET","/api/v1/tickets/"+id+"/events",null)).size());
        } finally { jdbc.execute("ALTER TABLE ticket_events DROP CONSTRAINT test_reject_reply"); }
    }
}
