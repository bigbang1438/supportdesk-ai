CREATE TABLE tenants (id BIGINT PRIMARY KEY,name VARCHAR(100) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE users (
 id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,username VARCHAR(60) NOT NULL UNIQUE,display_name VARCHAR(100) NOT NULL,
 role VARCHAR(20) NOT NULL CHECK(role IN ('customer','agent')),password_hash VARCHAR(100) NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE support_orders (
 id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,customer_id BIGINT NOT NULL,order_number VARCHAR(40) NOT NULL UNIQUE,item_name VARCHAR(120) NOT NULL,
 quantity INT NOT NULL CHECK(quantity>0),total_cents INT NOT NULL CHECK(total_cents>=0),status VARCHAR(20) NOT NULL CHECK(status IN ('paid','shipped','delivered','cancelled')),updated_at VARCHAR(24) NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),FOREIGN KEY(customer_id) REFERENCES users(id),INDEX idx_orders_scope(tenant_id,customer_id,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE tickets (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,customer_id BIGINT NOT NULL,order_id BIGINT NOT NULL,
 ticket_number VARCHAR(40) NOT NULL UNIQUE,subject VARCHAR(100) NOT NULL,body VARCHAR(2000) NOT NULL,status VARCHAR(20) NOT NULL CHECK(status IN ('open','processing','resolved')),
 version INT NOT NULL DEFAULT 0,created_at VARCHAR(24) NOT NULL,updated_at VARCHAR(24) NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),FOREIGN KEY(customer_id) REFERENCES users(id),FOREIGN KEY(order_id) REFERENCES support_orders(id),INDEX idx_tickets_scope(tenant_id,customer_id,status,updated_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE ticket_messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,ticket_id BIGINT NOT NULL,author_id BIGINT NOT NULL,body VARCHAR(1500) NOT NULL,created_at VARCHAR(24) NOT NULL,
 FOREIGN KEY(ticket_id) REFERENCES tickets(id),FOREIGN KEY(author_id) REFERENCES users(id),INDEX idx_messages_ticket(ticket_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE ticket_events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,ticket_id BIGINT NOT NULL,actor_id BIGINT NOT NULL,action VARCHAR(30) NOT NULL,detail VARCHAR(300) NOT NULL,created_at VARCHAR(24) NOT NULL,
 FOREIGN KEY(ticket_id) REFERENCES tickets(id),FOREIGN KEY(actor_id) REFERENCES users(id),INDEX idx_events_ticket(ticket_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE policies (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_id BIGINT NOT NULL,title VARCHAR(100) NOT NULL,filename VARCHAR(80) NOT NULL,
 content VARCHAR(4000) NOT NULL,version INT NOT NULL DEFAULT 0,updated_at VARCHAR(24) NOT NULL,
 FOREIGN KEY(tenant_id) REFERENCES tenants(id),UNIQUE KEY uq_policy_filename(tenant_id,filename)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
