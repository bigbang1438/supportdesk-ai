package com.supportdesk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.supportdesk.SupportHttpClient.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:support-policy-limit;DB_CLOSE_DELAY=-1",
    "supportdesk.demo-password=Support-Test-Only-2026!"})
class PolicyLimitHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Test void simultaneousLastSlotCreationCannotExceedTwentyTenantPolicies() throws Exception {
        for(int i=0;i<18;i++)jdbc.update("INSERT INTO policies(tenant_id,title,filename,content,version,updated_at) VALUES(1,?,?,?,0,?)","容量测试资料","fixture-"+i+".md","虚拟资料",SupportService.now());
        var a=new SupportHttpClient(port);a.login("demo_agent");var b=new SupportHttpClient(port);b.login("demo_agent2");
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {
            var first=pool.submit(()->{gate.await();return a.send("POST","/api/v1/policies",json(Map.of("title","最后一份","filename","last-a.md","content","虚拟资料"))).statusCode();});
            var second=pool.submit(()->{gate.await();return b.send("POST","/api/v1/policies",json(Map.of("title","最后一份","filename","last-b.md","content","虚拟资料"))).statusCode();});
            gate.countDown();var statuses=new ArrayList<>(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(201,409),statuses);
            assertEquals(20,jdbc.queryForObject("SELECT COUNT(*) FROM policies WHERE tenant_id=1",Integer.class));
        } finally { pool.shutdownNow(); }
    }
}
