CREATE TABLE assistant_runs (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL, actor_id BIGINT NOT NULL, ticket_id BIGINT NOT NULL,
 ticket_version INT NOT NULL, state VARCHAR(20) NOT NULL,
 view_json LONGTEXT NOT NULL, created_at VARCHAR(40) NOT NULL, expires_at VARCHAR(40),
 CONSTRAINT fk_assistant_tenant FOREIGN KEY(tenant_id) REFERENCES tenants(id),
 CONSTRAINT fk_assistant_actor FOREIGN KEY(actor_id) REFERENCES users(id),
 CONSTRAINT fk_assistant_ticket FOREIGN KEY(ticket_id) REFERENCES tickets(id),
 CONSTRAINT ck_assistant_state CHECK(state IN ('none','pending','published','discarded')),
 INDEX idx_assistant_actor_ticket(tenant_id,actor_id,ticket_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
