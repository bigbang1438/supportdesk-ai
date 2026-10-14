package com.supportdesk;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;

/** Original fictional users. Existing hashes are never overwritten on restart. */
@Component
public class DemoSeed implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;
    public DemoSeed(JdbcTemplate jdbc, PasswordEncoder encoder, @Value("${supportdesk.demo-password:}") String password) { this.jdbc=jdbc; this.encoder=encoder; this.password=password; }
    @Override @Transactional public void run(String... args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) > 0) return;
        if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalStateException("Set SUPPORTDESK_DEMO_PASSWORD to at least 12 characters and at most 72 UTF-8 bytes for a new database");
        jdbc.update("INSERT INTO tenants(id,name) VALUES(1,?),(2,?)", "青禾数码（虚拟企业）", "星桥文具（虚拟企业）");
        String hash=encoder.encode(password);
        seedUser(1,1,"demo_customer","演示客户","customer",hash);
        seedUser(2,1,"demo_customer2","另一位演示客户","customer",hash);
        seedUser(3,1,"demo_agent","演示客服","agent",hash);
        seedUser(4,1,"demo_agent2","协作客服","agent",hash);
        seedUser(5,2,"demo_other","外部演示客户","customer",hash);
        seedUser(6,2,"demo_other_agent","外部演示客服","agent",hash);
        String now=SupportService.now();
        seedOrder(1,1,1,"SD20261013001","青禾便携键盘（虚拟商品）",1,12900,"delivered",now);
        seedOrder(2,1,1,"SD20261013002","青禾桌面支架（虚拟商品）",2,7800,"shipped",now);
        seedOrder(3,1,1,"SD20261013003","青禾收纳包（虚拟商品）",1,3500,"cancelled",now);
        seedOrder(4,1,2,"SD20261013004","青禾演示鼠标（虚拟商品）",1,8900,"paid",now);
        seedOrder(5,2,5,"SD20261013005","星桥演示笔记本（虚拟商品）",3,4800,"delivered",now);
        seedOrder(6,2,5,"SD20261013006","星桥演示笔袋（虚拟商品）",1,2500,"shipped",now);
        jdbc.update("INSERT INTO policies(tenant_id,title,filename,content,version,updated_at) VALUES(?,?,?,?,0,?)",1,"演示售后与换货流程","after-sales.md","# 虚拟企业内部演示流程\n售后工单先由客服核对订单状态及商品批次。\n换货申请进入人工核对流程，演示系统不会自动承诺换货或发起退款。\n客服记录核对结果后回复客户，确认处理完成再关闭工单。",now);
        jdbc.update("INSERT INTO policies(tenant_id,title,filename,content,version,updated_at) VALUES(?,?,?,?,0,?)",2,"文具演示处理规范","stationery.md","# 星桥虚拟企业演示资料\n文具售后问题先核对订单与商品描述。\n客服人工登记处理意见；本系统不执行付款、退款或寄送。",now);
    }
    private void seedUser(long id,long tenant,String username,String name,String role,String hash) { jdbc.update("INSERT INTO users(id,tenant_id,username,display_name,role,password_hash) VALUES(?,?,?,?,?,?)",id,tenant,username,name,role,hash); }
    private void seedOrder(long id,long tenant,long customer,String number,String item,int quantity,int cents,String status,String now) { jdbc.update("INSERT INTO support_orders(id,tenant_id,customer_id,order_number,item_name,quantity,total_cents,status,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",id,tenant,customer,number,item,quantity,cents,status,now); }
}
