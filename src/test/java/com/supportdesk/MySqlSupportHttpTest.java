package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.supportdesk.SupportHttpClient.*;

/** Opt-in real MySQL test. Only explicitly named isolated loopback schemas are allowed; no drops. */
@EnabledIfEnvironmentVariable(named="SUPPORTDESK_MYSQL_TEST_URL",matches=".+")
class MySqlSupportHttpTest {
    @Test void mysqlMigrationsScopeConcurrentRepliesAndRestartPersistence() throws Exception {
        String url=required("SUPPORTDESK_MYSQL_TEST_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/(supportdesk_jobready|supportdesk_validation)(\\?.*)?"))throw new IllegalArgumentException("MySQL tests require isolated loopback port 3308 supportdesk_jobready/supportdesk_validation");
        long ticket;String hash;String subject="MySQL persistence "+UUID.randomUUID();
        try(var first=start(url)) {
            int port=((WebServerApplicationContext)first).getWebServer().getPort();var customer=client(port,"demo_customer");var a=client(port,"demo_agent");var b=client(port,"demo_agent2");
            var result=customer.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject",subject,"body","仅验证隔离演示库。")));assertEquals(201,result.statusCode(),result.body());ticket=data(result).path("id").asLong();
            assertEquals(404,client(port,"demo_other_agent").send("GET","/api/v1/tickets/"+ticket,null).statusCode());
            assertEquals(404,client(port,"demo_customer2").send("GET","/api/v1/orders/1",null).statusCode());
            assertEquals(403,a.send("POST","/api/v1/tickets/"+ticket+"/messages",json(Map.of("body","无令牌回复","version",0)),false,"application/json").statusCode());
            var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
            try {
                long id=ticket;var one=pool.submit(()->{gate.await();return a.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","MySQL 客服A","version",0))).statusCode();});
                var two=pool.submit(()->{gate.await();return b.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","MySQL 客服B","version",0))).statusCode();});gate.countDown();
                var statuses=new ArrayList<>(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(201,409),statuses);
            } finally { pool.shutdownNow(); }
            assertEquals(1,data(a.send("GET","/api/v1/tickets/"+ticket+"/messages",null)).size());assertEquals(2,data(a.send("GET","/api/v1/tickets/"+ticket+"/events",null)).size());
            var jdbc=first.getBean(JdbcTemplate.class);assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)>=1);hash=jdbc.queryForObject("SELECT password_hash FROM users WHERE username='demo_customer'",String.class);
        }
        try(var second=start(url)) {
            var client=client(((WebServerApplicationContext)second).getWebServer().getPort(),"demo_customer");var record=data(client.send("GET","/api/v1/tickets/"+ticket,null));assertEquals(subject,record.path("subject").asText());assertEquals(1,record.path("version").asInt());
            assertEquals(hash,second.getBean(JdbcTemplate.class).queryForObject("SELECT password_hash FROM users WHERE username='demo_customer'",String.class));
        }
    }
    private org.springframework.context.ConfigurableApplicationContext start(String url) { return new SpringApplicationBuilder(SupportDeskApplication.class).profiles("mysql").run("--server.port=0","--spring.datasource.url="+url,"--spring.datasource.username="+required("SUPPORTDESK_MYSQL_TEST_USERNAME"),"--spring.datasource.password="+required("SUPPORTDESK_MYSQL_TEST_PASSWORD"),"--supportdesk.demo-password="+required("SUPPORTDESK_MYSQL_TEST_DEMO_PASSWORD"),"--spring.main.banner-mode=off"); }
    private SupportHttpClient client(int port,String username) throws Exception {
        var client=new SupportHttpClient(port);client.session();var response=client.send("POST","/api/v1/auth/login","username="+username+"&password="+java.net.URLEncoder.encode(required("SUPPORTDESK_MYSQL_TEST_DEMO_PASSWORD"),java.nio.charset.StandardCharsets.UTF_8),true,"application/x-www-form-urlencoded");assertEquals(200,response.statusCode(),response.body());client.session();return client;
    }
    private static String required(String key) { String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing isolated MySQL test configuration: "+key);return value; }
}
