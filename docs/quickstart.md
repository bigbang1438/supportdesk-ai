# 快速开始

## 1. 构建

需要 Java 21、Maven 3.6.3 及以上、Node.js 22.12 及以上。首次构建需要联网下载 Maven 与 npm 依赖。从仓库根目录执行：

```powershell
cd frontend
npm ci
npm test
npm run build
cd ..
mvn -B -ntp clean verify
```

先构建前端，再构建 Java。Maven 会把 `frontend/dist` 复制进 JAR，最终由一个服务提供页面与接口。`frontend/dist`、`target` 和 `node_modules` 是本地生成目录，不在源码仓库中。

## 2. 使用 H2 启动

在同一个终端设置演示密码并启动。输入的密码至少 12 个字符，按 UTF-8 编码不超过 72 字节；建议使用自己生成的较长 ASCII 密码。

```powershell
$env:SUPPORTDESK_DEMO_PASSWORD = Read-Host '为本机虚拟账号设置演示密码'
java -jar target/supportdesk-ai-1.0.0.jar
```

打开 `http://127.0.0.1:8080`。登录 `demo_customer`，选择订单并提交工单；退出后登录 `demo_agent`，即可回复和跟进刚才的工单。

H2 数据保存在启动目录下的 `data` 中。新数据库才会初始化虚拟数据和账号密码，修改环境变量不会重置旧库密码。停止服务后重新启动，工单和事件仍会保留。不要在 Git 中提交此目录。

`.env.example` 只是配置清单；Java 不会自动读取 `.env`。请在终端、IDE 运行配置或自己的进程管理工具中设置变量。不要把真实密码写进源码。

## 3. 可选 MySQL

自行准备 MySQL 8 的**专用空数据库和应用账号**，给账号授予该数据库内建表、读写与 Flyway 迁移需要的权限。无需授予全局权限。这里的运行配置与自动测试配置是两组独立变量。

```powershell
$env:SUPPORTDESK_DB_URL = 'jdbc:mysql://127.0.0.1:3306/supportdesk?characterEncoding=UTF-8&serverTimezone=UTC'
$env:SUPPORTDESK_DB_USERNAME = Read-Host '专用数据库账号'
$env:SUPPORTDESK_DB_PASSWORD = Read-Host '专用数据库密码'
$env:SUPPORTDESK_DEMO_PASSWORD = Read-Host '新数据库的虚拟账号演示密码'
java -jar target/supportdesk-ai-1.0.0.jar --spring.profiles.active=mysql
```

`mysql` 配置关闭 H2 的 SQL 初始化，改用 `src/main/resources/db/migration/mysql` 中的 Flyway V1/V2。已经执行过的迁移文件应保持不变，后续表结构变更添加新的迁移。

如要执行仓库的 MySQL 自动测试，必须使用本机 **3308** 端口、数据库名为 `supportdesk_jobready` 或 `supportdesk_validation` 的专用练习库。测试会写入虚拟数据并多次启动应用，不会删除数据库；不要指向业务数据库。设置以下变量后再执行 `mvn verify`：

```text
SUPPORTDESK_MYSQL_TEST_URL
SUPPORTDESK_MYSQL_TEST_USERNAME
SUPPORTDESK_MYSQL_TEST_PASSWORD
SUPPORTDESK_MYSQL_TEST_DEMO_PASSWORD
```

不要把运行时 `SUPPORTDESK_DB_*` 变量同时带入默认 H2 测试进程。MySQL 测试是否执行成功，以实际测试报告为准；未设置测试 URL 时它们会被跳过。

## 4. 可选本地模型

先从 Ollama 官方渠道安装并运行本机服务，选择支持工具调用的模型。模型文件需单独下载，不包含在仓库中；请查看所选模型的许可与机器需求。

在启动 Java 前设置：

```powershell
$env:SUPPORTDESK_OLLAMA_URL = 'http://127.0.0.1:11434'
$env:SUPPORTDESK_OLLAMA_MODEL = Read-Host 'Ollama 中已安装的模型名称'
java -jar target/supportdesk-ai-1.0.0.jar
```

客服打开工单后选择模型模式。可以问“当前订单是什么状态”，或“查找售后处理规则”，再尝试“根据订单和规则起草一份客户回复”。模型需要按约定调用工具并返回结构化答案；不兼容、超时或不合格输出会显示错误，不会用固定话术伪装成模型成功。

服务只接受回环地址的 Ollama URL，不跟随重定向，不使用代理。一次运行最多 8 个模型轮次与工具步骤，整体 90 秒；CPU 或较大模型可能超时。普通工单和检索演示仍可使用。

`SUPPORTDESK_THOUGHT_IN_CONTENT` 默认 `false`。只有在明确确认所用适配器把完整思考结束标记和最终内容一起放在 `content` 中时，才使用兼容选项 `true`。它不会显示或保存模型思考内容；不完整标记仍会被拒绝。优先使用不需要该兼容选项的模型与适配器。

## 5. 开发页面

后端保持默认 8080 端口。在另一个终端执行：

```powershell
cd frontend
npm run dev
```

打开终端显示的本机 Vite 地址。开发代理将 `/api` 转发到 `127.0.0.1:8080`。打包后的页面与接口由同一 Java 服务提供。

## 常见情况

| 情况 | 处理 |
| --- | --- |
| 首次启动提示演示密码无效 | 设置 `SUPPORTDESK_DEMO_PASSWORD`，检查最少字符数和 UTF-8 字节上限 |
| 页面 404 或只有接口可用 | 先运行前端构建，再执行 Maven 构建并重启新 JAR |
| 修改返回 409 | 数据已改变、状态不允许或草稿已处理；刷新后重新核对，不自动重复提交 |
| 没有模型模式 | 启动 Java 前设置本地模型名称；确认 Ollama 中已经安装对应模型 |
| 模型超时或拒绝输出 | 检查模型服务、工具调用支持及运行环境；保留错误记录，继续使用检索演示 |
| 更换密码后仍无法登录旧库 | 旧库密码不会自动重置，使用初始化时的密码 |

默认端口可用 `PORT` 环境变量更改。应用默认只绑定本机；公开部署还需要独立设计 HTTPS、账号管理、备份、限流和运维配置，不在本项目的本机演示范围内。
