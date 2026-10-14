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

/** Real HTTP + sessions + database. No mocks and no authentication bypass. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:support-core;DB_CLOSE_DELAY=-1",
    "supportdesk.demo-password=Support-Test-Only-2026!"})
class SupportApiHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    SupportHttpClient logged(String username) throws Exception { var client=new SupportHttpClient(port);client.login(username);return client; }
    long ticket(SupportHttpClient client, String subject) throws Exception {
        var result=client.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject",subject,"body","商品外观需要售后核对。")));
        assertEquals(201,result.statusCode(),result.body()); assertTrue(result.headers().firstValue("Location").orElse("").startsWith("/api/v1/tickets/")); return data(result).path("id").asLong();
    }
    @Test void ordersAreScopedToCustomerAndTenantWithBoundedPagination() throws Exception {
        var customer=logged("demo_customer"); var staff=logged("demo_agent");
        var response=customer.send("GET","/api/v1/orders?page=0&size=1",null); assertEquals(200,response.statusCode(),response.body());
        var body=JSON.readTree(response.body()); assertEquals(1,body.path("data").size()); assertEquals(3,body.path("meta").path("total").asInt());
        assertEquals(200,staff.send("GET","/api/v1/orders/4",null).statusCode());
        assertEquals(404,customer.send("GET","/api/v1/orders/4",null).statusCode());
        assertEquals(404,staff.send("GET","/api/v1/orders/5",null).statusCode());
        assertEquals(400,customer.send("GET","/api/v1/orders?size=101",null).statusCode());
        assertEquals(400,customer.send("GET","/api/v1/orders?page=-1",null).statusCode());
        assertEquals(400,customer.send("GET","/api/v1/orders/0",null).statusCode());
    }
    @Test void customersCreateOnlyTicketsForOwnNonCancelledOrdersAndOutsidersSeeNothing() throws Exception {
        var customer=logged("demo_customer");long id=ticket(customer,"after-sales-"+UUID.randomUUID());
        var response=customer.send("GET","/api/v1/tickets/"+id,null);assertEquals(200,response.statusCode());assertEquals("open",data(response).path("status").asText());
        assertEquals(200,logged("demo_agent").send("GET","/api/v1/tickets/"+id,null).statusCode());
        assertEquals(404,logged("demo_customer2").send("GET","/api/v1/tickets/"+id,null).statusCode());
        assertEquals(404,logged("demo_other_agent").send("GET","/api/v1/tickets/"+id,null).statusCode());
        for(int order:List.of(4,5)) assertEquals(404,customer.send("POST","/api/v1/tickets",json(Map.of("orderId",order,"subject","问题","body","详细描述"))).statusCode());
        assertEquals(409,customer.send("POST","/api/v1/tickets",json(Map.of("orderId",3,"subject","问题","body","详细描述"))).statusCode());
        assertEquals(403,logged("demo_agent").send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject","问题","body","详细描述"))).statusCode());
    }
    @Test void replyAndStatusFollowVersionedWorkflowAndAuditIsAtomic() throws Exception {
        var customer=logged("demo_customer");var staff=logged("demo_agent");long id=ticket(customer,"workflow-"+UUID.randomUUID());
        var response=staff.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","已收到，正在核对。","version",0)));assertEquals(201,response.statusCode(),response.body());
        assertEquals(1,data(response).path("version").asInt());
        assertEquals(409,staff.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","过期回复","version",0))).statusCode());
        assertEquals(409,staff.send("PATCH","/api/v1/tickets/"+id+"/status",json(Map.of("status","resolved","version",1))).statusCode());
        assertEquals(200,staff.send("PATCH","/api/v1/tickets/"+id+"/status",json(Map.of("status","processing","version",1))).statusCode());
        assertEquals(409,staff.send("PATCH","/api/v1/tickets/"+id+"/status",json(Map.of("status","processing","version",2))).statusCode());
        assertEquals(403,customer.send("PATCH","/api/v1/tickets/"+id+"/status",json(Map.of("status","resolved","version",2))).statusCode());
        assertEquals(200,staff.send("PATCH","/api/v1/tickets/"+id+"/status",json(Map.of("status","resolved","version",2))).statusCode());
        assertEquals(409,customer.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","补充信息","version",3))).statusCode());
        assertEquals(200,customer.send("PATCH","/api/v1/tickets/"+id+"/status",json(Map.of("status","open","version",3))).statusCode());
        var messages=data(staff.send("GET","/api/v1/tickets/"+id+"/messages",null));assertEquals(1,messages.size());assertEquals("agent",messages.get(0).path("role").asText());
        var events=data(staff.send("GET","/api/v1/tickets/"+id+"/events",null));assertEquals(5,events.size());
        assertEquals(4,data(staff.send("GET","/api/v1/tickets/"+id,null)).path("version").asInt());
    }
    @Test void simultaneousRepliesAtOneVersionAllowOneWinnerAndOneAuditEvent() throws Exception {
        var customer=logged("demo_customer");var a=logged("demo_agent");var b=logged("demo_agent2");long id=ticket(customer,"concurrent-"+UUID.randomUUID());
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{gate.await();return a.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","客服 A 回复","version",0))).statusCode();});
            var two=pool.submit(()->{gate.await();return b.send("POST","/api/v1/tickets/"+id+"/messages",json(Map.of("body","客服 B 回复","version",0))).statusCode();});
            gate.countDown();assertEquals(Set.of(201,409),Set.of(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS)));
            assertEquals(1,data(a.send("GET","/api/v1/tickets/"+id+"/messages",null)).size());
            assertEquals(2,data(a.send("GET","/api/v1/tickets/"+id+"/events",null)).size());
        } finally { pool.shutdownNow(); }
    }
    @Test void policiesAreTenantBoundVersionedAndPreserveExactWhitespace() throws Exception {
        var staff=logged("demo_agent");String filename="policy-"+UUID.randomUUID()+".md";String content="  首行\r\n\r\n换货流程。  \n";
        var response=staff.send("POST","/api/v1/policies",json(Map.of("title","虚拟流程","filename",filename,"content",content)));assertEquals(201,response.statusCode(),response.body());
        long id=data(response).path("id").asLong();assertEquals(content,data(response).path("content").asText());
        assertEquals(409,staff.send("POST","/api/v1/policies",json(Map.of("title","重复","filename",filename,"content","内容"))).statusCode());
        assertEquals(200,staff.send("PUT","/api/v1/policies/"+id,json(Map.of("title","更新流程","filename",filename,"content",content,"version",0))).statusCode());
        assertEquals(409,staff.send("PUT","/api/v1/policies/"+id,json(Map.of("title","过期更新","filename",filename,"content","内容","version",0))).statusCode());
        assertEquals(404,logged("demo_other_agent").send("PUT","/api/v1/policies/"+id,json(Map.of("title","跨企业","filename",filename,"content","内容","version",1))).statusCode());
        assertEquals(403,logged("demo_customer").send("POST","/api/v1/policies",json(Map.of("title","客户写入","filename","test.md","content","内容"))).statusCode());
        var policies=data(logged("demo_other_agent").send("GET","/api/v1/policies",null));assertFalse(policies.toString().contains(filename));
    }
    @Test void invalidUnknownFieldsUnsafeFilenamesAndMissingCsrfCannotMutate() throws Exception {
        var customer=logged("demo_customer");var staff=logged("demo_agent");
        assertEquals(400,customer.send("POST","/api/v1/tickets","{\"orderId\":1,\"subject\":\"x\",\"body\":\"x\",\"customerId\":2}").statusCode());
        assertEquals(400,customer.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject"," ","body","详情"))).statusCode());
        assertEquals(403,customer.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject","问题","body","详情")),false,"application/json").statusCode());
        for(String filename:List.of("../policy.md","C:/a.txt","CON.md","file.exe","a\\b.md","a..md")) assertEquals(400,staff.send("POST","/api/v1/policies",json(Map.of("title","策略","filename",filename,"content","内容"))).statusCode(),filename);
        assertEquals(400,customer.send("GET","/api/v1/tickets?status=bad",null).statusCode());
        assertEquals(400,customer.send("GET","/api/v1/tickets?q="+"x".repeat(101),null).statusCode());
    }
    @Test void searchTreatsSqlWildcardLiterallyAndSummaryUsesScope() throws Exception {
        var customer=logged("demo_customer");var staff=logged("demo_agent");String marker="literal-"+UUID.randomUUID();ticket(customer,marker+"%_");ticket(customer,marker+"ZZ");
        var response=customer.send("GET","/api/v1/tickets?q="+marker+"%25_",null);assertEquals(200,response.statusCode(),response.body());assertEquals(1,data(response).size());
        var customerTotal=data(customer.send("GET","/api/v1/summary",null)).path("total").asLong();assertTrue(customerTotal>=2);
        var other=logged("demo_other");assertEquals(0,data(other.send("GET","/api/v1/summary",null)).path("total").asLong());
        assertTrue(data(staff.send("GET","/api/v1/summary",null)).path("total").asLong()>=customerTotal);
    }
    @Test void localHostOriginAndBodyLimitRejectUnsafeRequestsBeforeMutation() throws Exception {
        var customer=logged("demo_customer");
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(customer.origin+"/api/v1/tickets")).header("Content-Type","application/json").header("X-CSRF-TOKEN",customer.csrf).header("Origin","http://evil.example").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json(Map.of("orderId",1,"subject","问题","body","详情")))).build();
        assertEquals(403,customer.client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(413,customer.send("POST","/api/v1/tickets",json(Map.of("orderId",1,"subject","问题","body","x".repeat(25000)))).statusCode());
    }
}
