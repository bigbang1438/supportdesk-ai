package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.supportdesk.AssistantHttpTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real MySQL dialect/restart/transaction validation; only the model HTTP is scripted. */
@EnabledIfEnvironmentVariable(named="SUPPORTDESK_MYSQL_TEST_URL",matches=".+")
class AssistantMySqlHttpTest {
    @Test void mysqlPendingDraftSurvivesRestartAndConcurrentPublishIsConsumedAtomically() throws Exception {
        String url=required("SUPPORTDESK_MYSQL_TEST_URL");
        if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/(supportdesk_jobready|supportdesk_validation)(\\?.*)?"))
            throw new IllegalArgumentException("Assistant MySQL tests require isolated loopback port 3308 schemas");
        long ticket,run; int snapshotVersion;
        SCRIPT.set("draft"); ROUND.set(0); REQUESTS.clear();
        try(var application=start(url)) {
            int port=port(application); var staff=logged(port,"demo_agent"); var customer=logged(port,"demo_customer");
            var created=customer.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject","MySQL Agent "+UUID.randomUUID(),"body","请核对演示订单与售后政策。")));
            assertEquals(201,created.statusCode(),created.body()); ticket=data(created).path("id").asLong();
            var proposed=staff.send("POST",base(ticket),json(Map.of("question","核对订单并按售后政策起草回复","mode","ollama")));
            assertEquals(201,proposed.statusCode(),proposed.body()); var draft=data(proposed);
            run=draft.path("id").asLong(); snapshotVersion=draft.path("ticketVersion").asInt(); assertEquals("pending",draft.path("state").asText());
            assertEquals(0,data(staff.send("GET","/api/v1/tickets/"+ticket+"/messages",null)).size());
            assertTrue(application.getBean(JdbcTemplate.class).queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1",Integer.class)>=2);
        }
        try(var application=start(url)) {
            var staff=logged(port(application),"demo_agent"); var secondSession=logged(port(application),"demo_agent");
            assertEquals(run,data(staff.send("GET",base(ticket),null)).get(0).path("id").asLong());
            String publish=base(ticket)+"/"+run+"/publish",body=json(Map.of("version",snapshotVersion));
            assertEquals(404,logged(port(application),"demo_agent2").send("POST",publish,body).statusCode());
            var gate=new CountDownLatch(1);
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var first=pool.submit(()->{gate.await();return staff.send("POST",publish,body).statusCode();});
                var second=pool.submit(()->{gate.await();return secondSession.send("POST",publish,body).statusCode();}); gate.countDown();
                assertEquals(List.of(200,409),java.util.stream.Stream.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS)).sorted().toList());
            }
            assertEquals(1,data(staff.send("GET","/api/v1/tickets/"+ticket+"/messages",null)).size());
            assertEquals(2,data(staff.send("GET","/api/v1/tickets/"+ticket+"/events",null)).size());
            SCRIPT.set("draft"); ROUND.set(0); REQUESTS.clear();
            var proposed=staff.send("POST",base(ticket),json(Map.of("question","按售后政策起草回复","mode","ollama")));
            assertEquals(201,proposed.statusCode(),proposed.body()); var draft=data(proposed); int version=draft.path("ticketVersion").asInt();
            assertEquals(201,staff.send("POST","/api/v1/tickets/"+ticket+"/messages",json(Map.of("body","人工核对后新增说明","version",version))).statusCode());
            for(int attempt:List.of(version,version+1)) assertEquals(409,staff.send("POST",base(ticket)+"/"+draft.path("id").asLong()+"/publish",json(Map.of("version",attempt))).statusCode());
            assertEquals(2,data(staff.send("GET","/api/v1/tickets/"+ticket+"/messages",null)).size());
            assertEquals(3,data(staff.send("GET","/api/v1/tickets/"+ticket+"/events",null)).size());
            assertEquals("pending",data(staff.send("GET",base(ticket),null)).get(0).path("state").asText());
        }
        try(var application=start(url)) {
            var staff=logged(port(application),"demo_agent"); var runs=data(staff.send("GET",base(ticket),null));
            assertEquals("pending",runs.get(0).path("state").asText()); assertEquals("published",runs.get(1).path("state").asText());
            assertEquals(2,data(staff.send("GET","/api/v1/tickets/"+ticket+"/messages",null)).size());
        }
    }
    private ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(SupportDeskApplication.class).profiles("mysql").run("--server.port=0","--spring.datasource.url="+url,
            "--spring.datasource.username="+required("SUPPORTDESK_MYSQL_TEST_USERNAME"),"--spring.datasource.password="+required("SUPPORTDESK_MYSQL_TEST_PASSWORD"),
            "--supportdesk.demo-password="+required("SUPPORTDESK_MYSQL_TEST_DEMO_PASSWORD"),"--supportdesk.ollama-model=scripted-boundary-test",
            "--supportdesk.ollama-url=http://127.0.0.1:"+MODEL.getAddress().getPort(),"--supportdesk.thought-in-content=false","--spring.main.banner-mode=off");
    }
    private static int port(ConfigurableApplicationContext application) { return ((WebServerApplicationContext)application).getWebServer().getPort(); }
    private Session logged(int port,String user) throws Exception { var session=new Session(port);session.login(user,required("SUPPORTDESK_MYSQL_TEST_DEMO_PASSWORD"));return session; }
    private static String required(String name) { String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing isolated Assistant MySQL test configuration: "+name);return value; }
}
