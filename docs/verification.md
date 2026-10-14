# 后端验证记录

验证日期：2026-10-14。运行环境：Java 21、Maven 3.8.1、MySQL 8.0.41。

本记录对应当前源码的自动化后端验证。浏览器操作、前端测试和真实模型效果需要分别记录；下列 Agent 自动化用例使用可控的本地模型 HTTP 响应，并非真实大模型准确率评测。

## 完整测试结果

执行完整 `mvn verify`，并显式启用独立的本地 MySQL 验证库。

| 测试类 | 测试数 | 结果 |
| --- | ---: | --- |
| AssistantDisabledHttpTest | 1 | 通过 |
| AssistantHttpTest | 11 | 通过 |
| AssistantMySqlHttpTest | 1 | 通过，真实 MySQL |
| AssistantQwenHttpTest | 2 | 通过 |
| AssistantRemoteConfigHttpTest | 1 | 通过 |
| MySqlSupportHttpTest | 1 | 通过，真实 MySQL |
| PersistenceTest | 1 | 通过 |
| PolicyLimitHttpTest | 1 | 通过 |
| RequestBoundaryHttpTest | 1 | 通过 |
| SessionHttpTest | 2 | 通过 |
| SupportApiHttpTest | 8 | 通过 |
| ValidationBoundaryHttpTest | 3 | 通过 |
| **合计** | **33** | **0 失败、0 错误、0 跳过** |

测试经真实 HTTP 请求穿过登录、CSRF、控制器、业务逻辑和 JDBC。覆盖客户/客服权限、企业及客户资源隔离、分页搜索、工单状态转换、并发版本冲突、消息和审计事务、资料编辑及容量边界、会话和请求边界，以及 Agent 工具与草稿发布约束。

## 真实 MySQL 验证范围

两项 opt-in MySQL 测试实际执行，没有因环境变量缺失而跳过。验证库仅允许指定的回环地址端口及隔离 schema；测试不删除数据库，也不访问系统 MySQL 服务。

- Flyway V1 核心表与 V2 Agent 表迁移均成功，重启后再次校验迁移记录。
- 同版本的两条客服并发回复，结果为一次 201 和一次 409；仅保存一条消息和一条对应回复审计。
- 关闭并重新创建应用上下文后，工单、版本及账号密码哈希保持不变。
- Agent 待确认草稿跨重启保留。原客服的两个登录会话并发确认同一草稿，结果为一次 200 和一次 409，仅发布一次。
- 其他客服不能消费原客服草稿。人工消息改变工单版本后，即使提交者改用最新版本号，也不能发布旧快照草稿。

这些用例验证 SQL 方言、持久化与事务行为；它们不代表生产负载测试、吞吐量结论或商业运行经历。

## 本轮缺陷修复证据

`ValidationBoundaryHttpTest` 在修复前实际运行：3 项中 1 项失败，合法的 80 字符 `.md` 文件名被返回 400，预期为 201。原因是正则中的文件主体长度限制少于接口声明的 80 字符总长度。

修复后分别校验总长度和允许的文件名字符，保留路径、保留设备名及扩展名限制。完整测试中的 80 字符合法名称被接受，81 字符名称被拒绝。其他两项同时核验：不可信 Host 被拒绝；审计插入失败时，回复、工单版本和审计整体回滚，响应不暴露 SQL。

## 复现

普通后端用例使用独立 H2 测试数据库：

```text
mvn verify
```

运行真实 MySQL 用例前，需要由运行者配置以下环境变量，使用自己创建的隔离数据库和账号。测试只接受源码中列明的本地测试地址与 schema。

```text
SUPPORTDESK_MYSQL_TEST_URL
SUPPORTDESK_MYSQL_TEST_USERNAME
SUPPORTDESK_MYSQL_TEST_PASSWORD
SUPPORTDESK_MYSQL_TEST_DEMO_PASSWORD
```

混合运行 H2 和 MySQL 用例时，不设置应用运行专用的 `SUPPORTDESK_DB_*` 环境变量，避免 H2 用例继承其他数据库的账号密码。测试结果可在 `target/surefire-reports` 查看。

构建包为 `target/supportdesk-ai-1.0.0.jar`，包含 Spring Boot 后端、MySQL 迁移及构建时的 `frontend/dist`。构建前应先完成前端构建，并使用干净的构建目录。
