package com.supportdesk;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import java.sql.Statement;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.supportdesk.ApiModels.*;

@Service
public class SupportService {
    private final JdbcTemplate jdbc;
    private static final String TICKET_SQL="SELECT t.*,o.order_number,u.display_name FROM tickets t JOIN support_orders o ON o.id=t.order_id AND o.tenant_id=t.tenant_id JOIN users u ON u.id=t.customer_id WHERE t.tenant_id=?";
    private static final RowMapper<TicketView> TICKET=(rs,n)->new TicketView(rs.getLong("id"),rs.getString("ticket_number"),rs.getString("subject"),rs.getString("body"),rs.getLong("order_id"),rs.getString("order_number"),rs.getString("display_name"),rs.getString("status"),rs.getInt("version"),rs.getString("created_at"),rs.getString("updated_at"));
    private static final RowMapper<OrderView> ORDER=(rs,n)->new OrderView(rs.getLong("id"),rs.getString("order_number"),rs.getString("item_name"),rs.getInt("quantity"),rs.getInt("total_cents"),rs.getString("status"),rs.getString("updated_at"));
    private static final RowMapper<PolicyView> POLICY=(rs,n)->new PolicyView(rs.getLong("id"),rs.getString("title"),rs.getString("filename"),rs.getString("content"),rs.getInt("version"),rs.getString("updated_at"));
    public SupportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** The authenticated principal supplies only the username; roles and workspace come from SQL. */
    public Actor actor(String username) {
        return jdbc.query("SELECT u.*,t.name AS workspace_name FROM users u JOIN tenants t ON t.id=u.tenant_id WHERE u.username=?",
            (rs,n) -> new Actor(rs.getLong("id"), rs.getLong("tenant_id"), rs.getString("username"), rs.getString("display_name"), rs.getString("role"), rs.getString("workspace_name")), username)
            .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,"unauthorized","请重新登录"));
    }
    /** Staff see tenant orders; customers see their own. Foreign and absent IDs share one 404. */
    public OrderView order(Actor actor,long orderId) {
        var args=new ArrayList<Object>();args.add(actor.tenantId());args.add(orderId);
        return jdbc.query("SELECT * FROM support_orders WHERE tenant_id=? AND id=?"+customerScope(actor,"customer_id",args),ORDER,args.toArray()).stream().findFirst().orElseThrow(SupportService::missing);
    }
    public Page<OrderView> orders(Actor actor,int page,int size,String q) {
        validatePage(page,size,q);var args=new ArrayList<Object>();args.add(actor.tenantId());
        String where=" WHERE tenant_id=?"+customerScope(actor,"customer_id",args);
        if(!q.isBlank()){where+=" AND (order_number LIKE ? ESCAPE '!' OR item_name LIKE ? ESCAPE '!')";args.add(search(q));args.add(search(q));}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM support_orders"+where,Long.class,args.toArray());
        args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query("SELECT * FROM support_orders"+where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",ORDER,args.toArray()),meta(page,size,total));
    }
    public TicketView ticket(Actor actor,long ticketId) { return findTicket(actor,ticketId,false); }
    /** REQUIRED joins an Agent publishing transaction, so ticket and draft are committed together. */
    @Transactional public TicketView lockTicket(Actor actor,long ticketId) { return findTicket(actor,ticketId,true); }
    private TicketView findTicket(Actor actor,long id,boolean lock) {
        var args=new ArrayList<Object>();args.add(actor.tenantId());args.add(id);
        return jdbc.query(TICKET_SQL+" AND t.id=?"+customerScope(actor,"t.customer_id",args)+(lock?" FOR UPDATE":""),TICKET,args.toArray()).stream().findFirst().orElseThrow(SupportService::missing);
    }
    public Page<TicketView> tickets(Actor actor,int page,int size,String q,String status) {
        validatePage(page,size,q);if(!status.isBlank()&&!Set.of("open","processing","resolved").contains(status))throw invalid("工单状态不合法");
        var args=new ArrayList<Object>();args.add(actor.tenantId());String where=customerScope(actor,"t.customer_id",args);
        if(!q.isBlank()){where+=" AND (t.subject LIKE ? ESCAPE '!' OR t.ticket_number LIKE ? ESCAPE '!')";args.add(search(q));args.add(search(q));}
        if(!status.isBlank()){where+=" AND t.status=?";args.add(status);}
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM tickets t WHERE t.tenant_id=?"+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query(TICKET_SQL+where+" ORDER BY t.updated_at DESC,t.id DESC LIMIT ? OFFSET ?",TICKET,args.toArray()),meta(page,size,total));
    }
    @Transactional public TicketView createTicket(Actor actor,TicketInput input) {
        if(!actor.role().equals("customer"))throw forbidden();
        var order=order(actor,input.orderId());if(order.status().equals("cancelled"))throw conflict("取消的演示订单不能新建售后工单");
        String time=now();String number="SDT"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
        long id=insert("INSERT INTO tickets(tenant_id,customer_id,order_id,ticket_number,subject,body,status,version,created_at,updated_at) VALUES(?,?,?,?,?,?,'open',0,?,?)",actor.tenantId(),actor.userId(),input.orderId(),number,input.subject().trim(),input.body().trim(),time,time);
        event(id,actor,"created","客户新建工单",time);return ticket(actor,id);
    }
    public List<MessageView> messages(Actor actor,long ticketId) {
        ticket(actor,ticketId);var rows=jdbc.query("SELECT m.*,u.display_name,u.role FROM ticket_messages m JOIN users u ON u.id=m.author_id WHERE m.ticket_id=? ORDER BY m.id DESC LIMIT 100",(rs,n)->new MessageView(rs.getLong("id"),rs.getLong("ticket_id"),rs.getString("display_name"),rs.getString("role"),rs.getString("body"),rs.getString("created_at")),ticketId);
        Collections.reverse(rows);return rows;
    }
    /** Lock first, check the page's version, then write ticket/message/event in the same transaction. */
    @Transactional public TicketView reply(Actor actor,long ticketId,MessageInput input) {
        if(input==null||input.version()==null||input.version()<0||input.body()==null||input.body().isBlank()||input.body().length()>1500)throw invalid("回复内容或版本不合法");
        var ticket=lockTicket(actor,ticketId);requireVersion(ticket.version(),input.version());if(ticket.status().equals("resolved"))throw conflict("工单已解决，请先重新打开");
        String time=now();
        jdbc.update("UPDATE tickets SET version=version+1,updated_at=? WHERE id=? AND tenant_id=?",time,ticketId,actor.tenantId());
        jdbc.update("INSERT INTO ticket_messages(ticket_id,author_id,body,created_at) VALUES(?,?,?,?)",ticketId,actor.userId(),input.body().trim(),time);
        event(ticketId,actor,"reply","发布一条回复",time);return ticket(actor,ticketId);
    }
    /** Explicit transitions keep the workflow explainable; a no-op is a conflict with no audit entry. */
    @Transactional public TicketView status(Actor actor,long ticketId,StatusInput input) {
        if(!Set.of("open","processing","resolved").contains(input.status()))throw invalid("工单状态不合法");
        var ticket=lockTicket(actor,ticketId);requireVersion(ticket.version(),input.version());
        if(ticket.status().equals(input.status()))throw conflict("状态未改变，请刷新工单后操作");
        boolean allowed=actor.role().equals("agent")?((ticket.status().equals("open")&&input.status().equals("processing"))||(ticket.status().equals("processing")&&input.status().equals("resolved"))):(ticket.status().equals("resolved")&&input.status().equals("open"));
        if(!allowed){if(actor.role().equals("customer"))throw forbidden();throw conflict("请按打开、处理中、已解决的顺序处理");}
        String time=now();jdbc.update("UPDATE tickets SET status=?,version=version+1,updated_at=? WHERE id=? AND tenant_id=?",input.status(),time,ticketId,actor.tenantId());event(ticketId,actor,"status",ticket.status()+" → "+input.status(),time);return ticket(actor,ticketId);
    }
    public List<EventView> events(Actor actor,long ticketId) {
        ticket(actor,ticketId);var rows=jdbc.query("SELECT e.*,u.display_name FROM ticket_events e JOIN users u ON u.id=e.actor_id WHERE e.ticket_id=? ORDER BY e.id DESC LIMIT 100",(rs,n)->new EventView(rs.getLong("id"),rs.getString("action"),rs.getString("display_name"),rs.getString("detail"),rs.getString("created_at")),ticketId);Collections.reverse(rows);return rows;
    }
    public List<PolicyView> policies(Actor actor) { return jdbc.query("SELECT * FROM policies WHERE tenant_id=? ORDER BY id DESC LIMIT 20",POLICY,actor.tenantId()); }
    @Transactional public PolicyView createPolicy(Actor actor,PolicyInput input) {
        requireAgent(actor);validateFilename(input.filename());
        // Serialize only additions in this workspace; the last remaining slot cannot be taken twice.
        jdbc.queryForObject("SELECT id FROM tenants WHERE id=? FOR UPDATE",Long.class,actor.tenantId());
        if(jdbc.queryForObject("SELECT COUNT(*) FROM policies WHERE tenant_id=?",Long.class,actor.tenantId())>=20)throw conflict("当前企业最多保存 20 份资料，请更新已有资料");
        try { long id=insert("INSERT INTO policies(tenant_id,title,filename,content,version,updated_at) VALUES(?,?,?,?,0,?)",actor.tenantId(),input.title().trim(),input.filename(),input.content(),now());return policy(actor,id); }
        catch(DuplicateKeyException e){throw conflict("该资料文件名已存在");}
    }
    @Transactional public PolicyView updatePolicy(Actor actor,long id,PolicyUpdateInput input) {
        requireAgent(actor);validateFilename(input.filename());
        var current=jdbc.query("SELECT * FROM policies WHERE tenant_id=? AND id=? FOR UPDATE",POLICY,actor.tenantId(),id).stream().findFirst().orElseThrow(SupportService::missing);requireVersion(current.version(),input.version());
        try { jdbc.update("UPDATE policies SET title=?,filename=?,content=?,version=version+1,updated_at=? WHERE id=? AND tenant_id=?",input.title().trim(),input.filename(),input.content(),now(),id,actor.tenantId());return policy(actor,id); }
        catch(DuplicateKeyException e){throw conflict("该资料文件名已存在");}
    }
    private PolicyView policy(Actor actor,long id) { return jdbc.query("SELECT * FROM policies WHERE tenant_id=? AND id=?",POLICY,actor.tenantId(),id).stream().findFirst().orElseThrow(SupportService::missing); }
    public Summary summary(Actor actor) {
        var args=new ArrayList<Object>();args.add(actor.tenantId());String where=" WHERE tenant_id=?"+customerScope(actor,"customer_id",args);
        return jdbc.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(CASE WHEN status='open' THEN 1 ELSE 0 END),0) opened,COALESCE(SUM(CASE WHEN status='processing' THEN 1 ELSE 0 END),0) processing,COALESCE(SUM(CASE WHEN status='resolved' THEN 1 ELSE 0 END),0) resolved FROM tickets"+where,(rs,n)->new Summary(rs.getLong("total"),rs.getLong("opened"),rs.getLong("processing"),rs.getLong("resolved")),args.toArray());
    }
    public static void requireAgent(Actor actor) { if(!actor.role().equals("agent"))throw forbidden(); }
    private static void validateFilename(String filename) {
        if(filename==null||filename.length()>80||!filename.matches("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]{0,76}\\.(md|txt)")||filename.contains(".."))throw invalid("资料名只能包含文字、数字、点、短横线或下划线，扩展名为 .md 或 .txt");
        String stem=filename.substring(0,filename.indexOf('.')).toUpperCase(Locale.ROOT);if(Set.of("CON","PRN","AUX","NUL","COM1","COM2","COM3","COM4","COM5","COM6","COM7","COM8","COM9","LPT1","LPT2","LPT3","LPT4","LPT5","LPT6","LPT7","LPT8","LPT9").contains(stem))throw invalid("该资料文件名不可使用");
    }
    private long insert(String sql,Object... args) {
        var keys=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},keys);return Objects.requireNonNull(keys.getKey()).longValue();
    }
    private void event(long ticket,Actor actor,String action,String detail,String time) { jdbc.update("INSERT INTO ticket_events(ticket_id,actor_id,action,detail,created_at) VALUES(?,?,?,?,?)",ticket,actor.userId(),action,detail,time); }
    // Column names are fixed strings from this class; all request/identity values remain SQL parameters.
    private static String customerScope(Actor actor,String column,List<Object> args) { if(actor.role().equals("agent"))return "";args.add(actor.userId());return " AND "+column+"=?"; }
    private static String search(String text) { return "%"+text.replace("!","!!").replace("%","!%").replace("_","!_")+"%"; }
    private static void validatePage(int page,int size,String q) { if(page<0||page>100000||size<1||size>100||q==null||q.length()>100)throw invalid("分页或搜索内容不合法"); }
    private static Meta meta(int page,int size,long total) { return new Meta(page,size,total,(int)((total+size-1)/size)); }
    private static void requireVersion(int actual,Integer expected) { if(expected==null||actual!=expected)throw conflict("内容已更新，请刷新后重新核对"); }
    public static String now() { return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC).format(Instant.now()); }
    private static ApiException missing() { return new ApiException(404,"not_found","资源不存在或不可访问"); }
    private static ApiException forbidden() { return new ApiException(403,"forbidden","当前账号无权执行此操作"); }
    private static ApiException invalid(String message) { return new ApiException(400,"validation_error",message); }
    private static ApiException conflict(String message) { return new ApiException(409,"conflict",message); }
}
