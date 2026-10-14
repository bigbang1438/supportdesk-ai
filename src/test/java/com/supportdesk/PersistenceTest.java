package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.supportdesk.SupportHttpClient.*;

/** Two real application instances against one test-owned H2 file; no reset between boots. */
class PersistenceTest {
    @TempDir Path folder;
    @Test void restartPreservesTicketHistoryAndOriginalPasswordHash() throws Exception {
        String url="jdbc:h2:file:"+folder.resolve("supportdesk").toAbsolutePath().toString().replace('\\','/');long ticket;String hash;
        try(var first=start(url,PASSWORD)) {
            var client=new SupportHttpClient(port(first));client.login("demo_customer");
            var created=client.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject","重启持久化验证","body","测试自己的数据库文件。")));assertEquals(201,created.statusCode(),created.body());ticket=data(created).path("id").asLong();
            assertEquals(201,client.send("POST","/api/v1/tickets/"+ticket+"/messages",json(Map.of("body","持久化回复","version",0))).statusCode());
            hash=first.getBean(JdbcTemplate.class).queryForObject("SELECT password_hash FROM users WHERE username='demo_customer'",String.class);
        }
        try(var second=start(url,"")) {
            var client=new SupportHttpClient(port(second));client.login("demo_customer");
            var persisted=data(client.send("GET","/api/v1/tickets/"+ticket,null));assertEquals("重启持久化验证",persisted.path("subject").asText());assertEquals(1,persisted.path("version").asInt());
            assertEquals("持久化回复",data(client.send("GET","/api/v1/tickets/"+ticket+"/messages",null)).get(0).path("body").asText());
            assertEquals(2,data(client.send("GET","/api/v1/tickets/"+ticket+"/events",null)).size());
            assertEquals(hash,second.getBean(JdbcTemplate.class).queryForObject("SELECT password_hash FROM users WHERE username='demo_customer'",String.class));
        }
    }
    private org.springframework.context.ConfigurableApplicationContext start(String url,String password) {
        return new SpringApplicationBuilder(SupportDeskApplication.class).run("--server.port=0","--spring.datasource.url="+url,"--supportdesk.demo-password="+password,"--spring.main.banner-mode=off");
    }
    private int port(org.springframework.context.ConfigurableApplicationContext context) { return ((WebServerApplicationContext)context).getWebServer().getPort(); }
}
