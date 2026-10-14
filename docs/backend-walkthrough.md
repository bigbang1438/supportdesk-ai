# 后端从哪里读

这是用原创虚拟企业、账号、订单和资料构造的练习项目。固定客户查看自己的订单并提交售后工单，客服在本企业内回复、推进工单状态并维护资料。系统不执行付款、退款或寄送。

先读 `ApiModels.java` 的请求与响应，再读 `SupportController.java`：控制器取得会话中的账号身份，做输入校验，交给 `SupportService.java`。客户端不能提交自己扮演的用户、角色或企业编号。

`SupportService.java` 的查询先限定 `tenant_id`。客户查询还限定 `customer_id`，客服只能看到当前企业的数据。未知编号和无权查看的编号统一返回 404。搜索值通过参数传入数据库，百分号和下划线被转义，不能把用户搜索变成 SQL 条件。

工单状态为 `open → processing → resolved`。客户可以把自己的已解决工单重新打开。相同状态提交返回 409，不增加版本或事件。每次回复和状态变更都携带页面读取时的版本，事务先锁住工单行再核对版本；版本过期返回 409，让用户刷新并再次核对。工单、回复、事件共同提交，两个客服同时回复时只有一个版本匹配的请求成功。

`SecurityConfig.java` 使用 Spring Security 会话和 BCrypt。浏览器先请求 `/api/v1/auth/session` 取得 CSRF 令牌，表单登录后再取一次。写接口必须带令牌。`LocalSecurityFilter.java` 额外检查本机 Host、来源以及已知长度的 24 KiB 请求上限，带 Transfer-Encoding 的分块写入直接返回 413。正常的浏览器 JSON 请求和表单登录使用已知长度。会话 Cookie 为 HttpOnly、SameSite=Strict。

`DemoSeed.java` 仅在新数据库初始化六个明确标记的虚拟账号。新库必须提供 `SUPPORTDESK_DEMO_PASSWORD`，最少 12 个字符且不超过 72 个 UTF-8 字节；程序重启不会重置原有密码哈希。默认 H2 文件数据库适合快速运行。`mysql` 配置用 `SUPPORTDESK_DB_URL`、`SUPPORTDESK_DB_USERNAME`、`SUPPORTDESK_DB_PASSWORD`，禁用 SQL 自动初始化并使用 Flyway V1/V2 迁移。

可从以下验证理解保证的边界：`SessionHttpTest` 测试真实登录和 CSRF；`SupportApiHttpTest` 测试权限、状态、过期版本、搜索、审计和并发；`PolicyLimitHttpTest` 测试共享资料容量的并发边界；`PersistenceTest` 对同一临时 H2 文件启动两次；`MySqlSupportHttpTest` 需要独立环境变量，限制为本机 3308 端口的指定练习库。真实 MySQL 是否通过，以执行报告为准。

AI 部分通过 `SupportService.lockTicket` 和 `reply` 加入同一个数据库事务。模型只产生待审核草稿，明确发布才成为工单回复。普通工单功能不依赖模型可用性。
