# 智维（ZhiWei）— 企业级 AI 知识管理与智能运维平台

Spring Boot 3.5.3 + Java 21，集成 LLM 多模型路由、RAG 知识检索、Agent 运维工具、MCP Server。

---

## 技术栈

| 层 | 技术 |
|---|------|
| 框架 | Spring Boot 3.5.3, Java 21 |
| AI | Spring AI Alibaba (DashScope), LangChain4j, Native HTTP, Ollama |
| 向量库 | PostgreSQL + pgvector (HNSW 索引, 1536/768 维双列) |
| 数据库 | MySQL 8.0 (业务), Flyway 迁移 |
| 缓存 | Redis 7 (限流窗口 + 成本权重 + 滑动指标) |
| 消息 | RabbitMQ (文档管道异步处理) |
| 熔断 | Resilience4j CircuitBreaker |
| 文档解析 | Apache Tika (PDF/Word/MD/TXT) |
| 部署 | Docker Compose |

---

## 快速开始

### 前置条件

- Docker Desktop
- JDK 21+
- DashScope API Key ([阿里云百炼](https://bailian.console.aliyun.com/))

### 1. 启动所有中间件

```bash
docker compose up -d
```

一键启动 MySQL + PostgreSQL(pgvector) + Redis + RabbitMQ。Ollama（本地模型）按需：

```bash
docker compose --profile ollama up -d ollama
```

### 2. 配置 API Key

```bash
# Linux / macOS
export DASHSCOPE_API_KEY=sk-your-key-here

# Windows PowerShell
$env:DASHSCOPE_API_KEY = "sk-your-key-here"
```

### 3. 启动应用

```bash
./mvnw spring-boot:run
# 或
mvn clean package -DskipTests && java -jar target/zhiwei-0.0.1-SNAPSHOT.jar
```

使用 Docker profile 全栈启动（含应用）：

```bash
# 先打 jar
mvn clean package -DskipTests
# 启动
docker compose up -d --build
```

### 4. 访问

| 地址 | 说明 |
|------|------|
| http://localhost:8080/swagger-ui.html | API 文档 (SpringDoc) |
| http://localhost:15672 | RabbitMQ 管理面板 (zhiwei / zhiwei123) |
| http://localhost:8080/actuator | Spring Actuator |

---

## API 概览

### AI 服务

```bash
# 普通聊天
curl -X POST http://localhost:8080/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","message":"Redis 集群如何扩容？"}'

# Agent 全链路（意图识别 + 工具调用 + 结构化卡片）
curl -X POST http://localhost:8080/api/ai/agent \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","message":"nginx-01 宕机了"}'

# SSE 流式
curl -X POST http://localhost:8080/api/ai/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","message":"Redis 集群的原理是什么"}'
```

### 知识库

```bash
# 上传文档（PDF/Word/MD/TXT）
curl -X POST http://localhost:8080/api/rag/upload \
  -F "file=@redis-manual.pdf" -F "userId=u1"

# 查询处理状态
curl http://localhost:8080/api/rag/document/1

# RAG 检索
curl -X POST http://localhost:8080/api/rag/search \
  -H "Content-Type: application/json" \
  -d '{"query":"Redis 扩容","topK":5}'

# 质量评估
curl -X POST http://localhost:8080/api/rag/evaluate \
  -H "Content-Type: application/json" \
  -d '{"queries":[{"query":"Redis","expectedSourceId":"doc-redis"}]}'
```

### MCP Server

```bash
# 工具列表
curl -X POST http://localhost:8080/api/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 调用工具
curl -X POST http://localhost:8080/api/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"rag_search","arguments":{"query":"Redis"}}}'
```

### 系统监控

```bash
# 路由状态
curl http://localhost:8080/api/system/router/status

# 单 Provider 详情（含熔断状态 + 决策日志）
curl http://localhost:8080/api/system/router/detail/spring-ai-alibaba

# 用量统计
curl http://localhost:8080/api/system/usage

# 降级事件日志
curl http://localhost:8080/api/system/router/events?limit=20
```

---

## 架构

```
┌──────────────────────────────────────────────────────┐
│ AiController /api/ai/{chat|agent}                    │
│ McpController /api/mcp                               │
│ KnowledgeController /api/rag                         │
│ SystemController /api/system                         │
├──────────────────────────────────────────────────────┤
│ AgentService → 意图识别 → Prompt模板 → 工具调用      │
│ ChatService  → RAG注入 → ProviderRouter → Failover   │
├──────────────────────────────────────────────────────┤
│ 4 Provider: SpringAI | LangChain4j | Native | Ollama │
│ FailoverHandler: Resilience4j 熔断器 + 降级链         │
──────────────────────────────────────────────────────┤
│ AiRagService → CompatibleEmbeddingClient             │
│              → OllamaEmbeddingClient                 │
│              → PgVectorKnowledgeRepository           │
│ DocumentParser(Tika) + SmartChunker(512+64滑动窗口)   │
├──────────────────────────────────────────────────────┤
│ MySQL (业务数据) | pgvector (向量, 双列)              │
│ Redis (限流/缓存)  | RabbitMQ (文档管道)              │
│ Ollama (本地模型, 按需)                               │
──────────────────────────────────────────────────────┘
```

---

## 容器化部署

### 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                      Docker Compose 全栈                         │
│                                                                  │
│  ┌──────────┐  ┌──────────┐  ┌────────┐  ┌──────────┐          │
│  │  MySQL   │  │PostgreSQL│  │ Redis  │  │RabbitMQ  │          │
│  │  :3306   │  │  :5432   │  │ :6379  │  │ :5672    │          │
│  │ (业务DB) │  │ (pgvector)│  │ (缓存) │  │ (消息队列)│          │
│  └────┬─────┘  └────┬─────┘  ───┬────┘  ────┬─────┘          │
│       │              │            │             │                │
│       ──────────────────────────┴─────────────┘                │
│                              │                                    │
│                       ┌────────────┐                            │
│                       │   ZhiWei    │  :8080                     │
│                       │  (Spring    │                            │
│                       │   Boot 3)   │                            │
│                       └──────┬──────┘                            │
│                              │                                    │
│                    ┌─────────┴─────────┐                         │
│                    │  Ollama (按需)     │  :11434                 │
│                    │  qwen2.5:7b       │                         │
│                    │  nomic-embed-text │                         │
│                    └───────────────────                         │
└─────────────────────────────────────────────────────────────────┘
```

### 前置条件

| 项目 | 要求 |
|------|------|
| Docker | Docker Desktop 4.x+ 或 Docker Engine 24.x+ |
| 内存 | 建议 ≥ 8 GB（Ollama 7B 模型需额外 ~6 GB） |
| 磁盘 | 建议 ≥ 20 GB（模型 + 数据卷） |
| API Key | DashScope API Key ([阿里云百炼](https://bailian.console.aliyun.com/)) |

### 部署步骤

#### 1. 配置环境变量

```bash
# 复制模板
cp .env.example .env

# 编辑 .env，填入 DashScope API Key
# DASHSCOPE_API_KEY=sk-your-actual-key-here
```

#### 2. 构建并启动（不含 Ollama）

```bash
# 构建应用镜像 + 启动所有中间件 + 启动应用
docker compose up -d --build
```

启动顺序由 `depends_on` + `healthcheck` 保证：
```
MySQL ──┐
Postgres├──→ 全部 healthy → ZhiWei App 启动
Redis ──┤
RabbitMQ┘
```

#### 3. 按需启用 Ollama（本地模型）

```bash
# 启动 Ollama 并自动拉取模型（首次约 5 GB，需等待 10-20 分钟）
docker compose --profile ollama up -d ollama

# 查看模型拉取进度
docker compose logs -f ollama
```

Ollama 启动后会自动拉取 `qwen2.5:7b` 和 `nomic-embed-text`，拉取完成后应用自动识别并加入降级链。

#### 4. 验证部署

```bash
# 查看所有服务状态
docker compose ps

# 期望输出：所有服务 STATUS 为 "healthy" 或 "running"

# 检查应用健康
curl http://localhost:8080/actuator/health

# 查看应用日志
docker compose logs -f zhiwei
```

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| ZhiWei App | 8080 | HTTP API + Swagger |
| MySQL | 3306 | 业务数据库 |
| PostgreSQL | 5432 | pgvector 向量库 |
| Redis | 6379 | 缓存 + 限流 |
| RabbitMQ | 5672 / 15672 | 消息队列 / 管理面板 |
| Ollama | 11434 | 本地模型推理 |

### 访问地址

| 地址 | 说明 |
|------|------|
| http://localhost:8080/swagger-ui.html | API 文档 (SpringDoc) |
| http://localhost:8080/actuator | Spring Actuator 健康检查 |
| http://localhost:15672 | RabbitMQ 管理面板 (zhiwei / zhiwei123) |

### 常用运维命令

```bash
# 查看实时日志
docker compose logs -f zhiwei

# 查看 Ollama 日志
docker compose logs -f ollama

# 重启应用（不重建镜像）
docker compose restart zhiwei

# 重建并重启应用
docker compose up -d --build zhiwei

# 停止所有服务（保留数据）
docker compose down

# 停止并清除数据卷（谨慎！）
docker compose down -v

# 查看资源占用
docker stats
```

### 故障排查

| 现象 | 排查命令 | 可能原因 |
|------|---------|---------|
| 应用启动失败 | `docker compose logs zhiwei` | 中间件未就绪 / API Key 未配置 |
| Ollama 连接失败 | `docker compose logs ollama` | 模型未拉完 / 内存不足 |
| pgvector 查询报错 | `docker compose logs postgres` | 扩展未加载 / 维度不匹配 |
| Redis 连接拒绝 | `docker compose logs redis` | 密码不匹配 |

---

## 开发

```bash
# 运行测试 (零依赖, 纯单元测试)
mvn test -Dtest="com.zihan.zhiwei.ai.**"
# 共 220+ 测试覆盖全部 AI 模块

# 组合测试 (需要 Docker)
mvn test -Dtest="com.zihan.zhiwei.integration.**"
```

---

## 项目结构

```
src/main/java/com/zihan/zhiwei/
├── ai/
│   ├── provider/     # 4 Provider + Failover + 熔断 + 指标
│   ├── intent/       # 意图识别 (5类)
│   ├── prompt/       # 模板化 Prompt
│   ├── tool/         # 5 运维工具
│   ├── rag/          # RAG 检索 + 上下文构建 + 注入
│   ├── stream/       # SSE 流式
│   ├── mcp/          # MCP JSON-RPC Server (6工具)
│   ├── safety/       # 敏感词 + 注入检测
│   ├── knowledge/    # 文档解析 + 分块 + 管道
│   ├── reply/        # 结构化卡片 + 兜底
│   └── usage/        # 用量记录 + 成本统计
├── config/           # Spring 配置
├── controller/       # HTTP 接口
├── security/         # API Key 认证 + 限流
├── service/          # 业务逻辑层
── mapper/           # MyBatis-Plus Mapper
└── pojo/             # 实体 + DTO

src/main/resources/
├── application.yml        # 基础配置
── application-dev.yml    # 开发环境
├── application-docker.yml # Docker 环境
├── db/migration/          # Flyway 迁移脚本 (MySQL)
── db/pgvector/           # pgvector 建表 (PostgreSQL)
── lua/                   # Redis Lua 脚本 (限流)
```

---

## 技术亮点

- **Strategy 模式**: 4 Provider 统一 `ModelProvider` 接口，支持无感切换（含 Ollama 本地兜底）
- **智能路由**: 按成功率×0.45 + 延迟×0.25 + 成本×0.15 打分选最优 Provider
- **故障降级**: Resilience4j 熔断器 CLOSED→OPEN→HALF_OPEN，降级链不丢数据
- **RAG 混合检索**: 85% 向量余弦相似度 + 15% 字面 Jaccard，解决术语匹配问题
- **SSE 真流式**: Native Provider 逐 token 推送，已发 token 后不可降级
- **Agent 全链路**: 意图识别 → 按意图选 Prompt → 工具调用 → 卡片组装 → 降级兜底
- **MCP Server**: JSON-RPC 2.0 协议，6 个工具, 外部 AI 自动发现并调用
- **本地模型**: Ollama 集成 qwen2.5:7b + nomic-embed-text，云端不可用时自动降级
