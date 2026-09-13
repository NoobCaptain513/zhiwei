# 智维（ZhiWei）— AI 知识管理与智能运维工程化 PoC

> 面向 IT 运维场景的 Java 21 / Spring Boot AI 后端，覆盖多模型路由、混合检索、Agentic RAG、可靠工具执行、分层记忆与 MCP Server。当前定位是学习/求职作品和企业 PoC，不宣称已经生产就绪。

[![Java](https://img.shields.io/badge/Java-21-blue)]()
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.3-green)]()
[![Spring AI](https://img.shields.io/badge/Spring_AI_Alibaba-1.0.0.2-orange)]()
[![License](https://img.shields.io/badge/License-MIT-yellow)]()

---

## 项目简介

智维是一个面向 IT 运维场景的工程化 AI 后端。传统运维知识库面临检索准确率低、多模型切换成本高、故障时缺少降级路径等问题。项目通过统一的 `ModelProvider` 抽象层接入多种 LLM，基于成本、延迟、成功率和能力指标进行路由，并使用 Resilience4j 实现调用级熔断与降级。

平台核心能力包括：多模型智能路由与故障降级、RAG 知识库检索（支持 PDF/Word/Markdown/TXT）、Agentic RAG 证据闭环、带预算和审批约束的运维 Agent、SSE 流式对话、分层记忆、MCP Server，以及成本统计与校准闭环。

### 当前验证状态

| 项目 | 当前结果 | 边界 |
|------|----------|------|
| Maven 构建 | `./mvnw clean verify` 与 `./mvnw verify` 均成功 | Java 21.0.7；生成主应用和 MCP JAR |
| 自动化测试 | 471 tests，0 failures，0 errors，1 skipped | 被跳过的是依赖外部基础设施的应用上下文测试 |
| RAG 数据集校验 | 31 篇文档、124 条查询、186 个相关性判断 | 其中 31 条为多文档查询 |
| RAG 报告 | 已提交离线 BM25/字符三元组/RRF 基线 | 不是生产 pgvector、Embedding、Reranker 实测 |
| Docker Compose | 已提交 Dockerfile、Compose 和容器配置 | 本轮复核环境没有 Docker CLI，未完成实际容器启动验收 |

项目已有较完整的功能和单元测试，但默认安全策略、真实数据库迁移、生产检索评测、异步记忆可靠投递和 CI 门禁仍需补齐后才能作为生产系统验收。

---

## 核心设计

### ModelProvider 抽象层

不追求"接了 N 个 Provider"，而是设计一个干净的抽象层，在它之上做智能路由和故障降级。

```java
public interface ModelProvider {
    ChatResponse chat(ChatRequest request);
    void streamChat(ChatRequest request, StreamCallback callback);
    String getName();
    boolean isAvailable();
    boolean supportsFunctionCalling();
    boolean supportsStreaming();
    boolean providesActualBilling();  // 是否返回厂商实际计费 token 数
    ProviderMetrics getMetrics();      // 实时指标（延迟/成功率/成本）
}
```

三个实现各有明确定位：

| Provider | 底层框架 | 定位 |
|---------|---------|------|
| `SpringAiAlibabaProvider` | Spring AI Alibaba | 效率优先，框架生态完善，Advisor 链接 RAG/Safety |
| `LangChain4jOpenAiProvider` | LangChain4j | 展示框架广度，AiServices 声明式 + 持久化 Memory |
| `NativeDashScopeProvider` | 原生 OkHttp | **降级链基座** + **精确成本校准源** |

第三个 Native Provider 的定位不是"多接一家"，而是两个工程目的：

1. **降级基座**：原生 OkHttp 依赖最少、调用链最短，不受框架版本冲突和 bug 影响。Spring AI 或 LangChain4j 升级出问题时，Native 仍然能提供基础对话能力。

2. **精确成本校准**：框架返回的 token 数是自身 tokenizer 的估算值，和厂商实际计费的 token 数存在偏差。原生 HTTP 直接解析厂商响应体里的 `usage` 对象，拿到的是实际计费值，用来校准路由引擎的成本模型。

### 智能路由 + 故障降级

```
请求到达
  ↓
① 提取请求特征：需要 Function Calling？是否流式？
  ↓
② 从 Redis 读取各 Provider 实时指标（滑动窗口 100 次）
  ↓
 计算得分：score = successRate×0.45 + latencyScore×0.25 + costScore×0.15 + preferBonus(0.15)
  ↓
 返回最优 Provider（得分最高且 isAvailable()=true）
  ↓
⑤ Native Provider 的实际计费值异步校准各 Provider 的成本权重
```

路由无策略枚举，打分权重硬编码（成功率 45% + 延迟 25% + 成本 15% + 偏好加成 15%），`preferred` 参数可指定优先 Provider，不指定时使用配置的 `default-provider`。

故障降级基于 Resilience4j CircuitBreaker 三态机制（CLOSED/OPEN/HALF_OPEN），降级链按配置优先级依次切换（SpringAI → LangChain4j → Native），仅对幂等请求重试 1 次避免重复扣费。

### 双重闭环成本模型

成本统计不是事后看报表，而是实时喂给路由引擎做决策。Native Provider 的实际计费值又反过来校准成本权重，形成双重闭环：

```
每次调用 → AiUsageLogService 记录 → 更新 Redis 滑动窗口指标
                                         ↓
                               ModelRouter 下次路由时读取（闭环 1：指标驱动路由）

Native Provider 调用 → 解析实际计费 usage
  → CostCalibrationInterceptor 对比框架估算值
  → 偏差超阈值 → 调整该 Provider 成本权重
  → ModelRouter 下次路由用校准后的权重（闭环 2：实际值校准估算值）
```

---

## 功能模块

### RAG 知识库

文档处理管道：Apache Tika 解析（PDF/Word/MD/TXT）→ 智能分块（512 Token + 64 重叠滑动窗口）→ RabbitMQ 异步 → 批量 Embedding（text-embedding-v4, 1536 维）→ pgvector 入库（HNSW 索引）。

检索与重排序：pgvector 余弦检索（向量通道）+ 关键词 ILIKE 匹配（关键词通道）→ RRF 融合（Reciprocal Rank Fusion，k=60，向量权重 1.0 / 关键词权重 0.5）→ Reranker → topK 返回。查询改写使用 LLM 实现指代消解与子问题分解，简单查询走快路径，改写结果进入 Caffeine 本地缓存。

当前生产检索实现会先完成 Embedding 和向量召回；向量结果为空时直接返回，因此关键词检索还不是可独立降级的召回通道。`VECTOR_HEAVY`、`KEYWORD_HEAVY` 和 `HYBRID` 当前主要用于调整融合权重。

### RAG 评测基线

仓库内置冻结运维语料和静态标注集：

- `evaluation/rag/corpus-v1.json`：31 条可独立索引的运维知识文档；
- `evaluation/rag/eval-set-v1.json`：124 条查询、186 个分级相关性判断，其中 31 条需要多个相关文档；
- `scripts/run-rag-evaluation.py`：校验数据，并可在应用启动后重建专用 `documentId=990001` 语料、调用在线评测接口；
- 指标：HitRate@K、Recall@K、Precision@K、MRR@K、nDCG@K。

```bash
# 只校验数据集
python scripts/run-rag-evaluation.py --validate-only

# 启动应用后，重建冻结语料并生成在线对比报告
python scripts/run-rag-evaluation.py --base-url http://localhost:8080 --index
```

原始逐查询结果和 Markdown 汇总写入 `evaluation/rag/reports/`。仓库当前提交的 `zhiwei-ops-baseline-v1-2026-09-12-offline-*` 报告仅用于验证评测集、指标和离线算法，不能替代线上 pgvector/Embedding/Reranker 的质量结论。标注由 Hermes Agent 静态逐项编写，未从检索结果反推；对外宣称“人工基线”前仍需领域人员复核并记录分歧裁决。

### Agentic RAG 闭环

Agentic RAG 已接入同步和流式 Agent 路径，执行链为：

```text
查询分类 → 检索规划 → 并发混合检索 → 证据评分
    → 证据不足时反馈改写并重试
    → 基于证据生成回答 → 引用校验 → 输出或拒答
```

证据评分覆盖相关性、充分性、子问题覆盖和冲突检测；证据 ID 被限制在真实召回结果内，最终答案中的 `[E{id}]` 引用还会再次校验。当前 checkpoint 主要记录节点和执行状态，`resume` 只执行状态流转，尚不能恢复完整的检索计划、证据集和 token 预算后从中间节点继续运行。

### Agent 与 Tool Calling

意图识别模块支持 5 类意图（故障排查/日志查询/部署操作/工单创建/知识检索），基于关键词规则和置信度阈值执行。运维工具集包含 `queryServerStatus`、`searchLogs`、`queryDeployHistory`、`createTicket`、`queryMetrics`；Mock 实现默认关闭，需显式配置 `zhiwei.ai.tool.mock-enabled=true` 才会启用。

Agent 执行流程：意图识别 → 按意图选 Prompt → 工具调用 → 结构化卡片组装 → 合并去重 → 降级兜底。运行时增加了总 token 预算、节点调用次数、总时限、节点观测，以及工具级超时、只读重试、Circuit Breaker、Bulkhead、审批和幂等缓存。副作用工具不会自动重试；但下游副作用与本地幂等结果写入尚未形成跨系统原子语义，真实接入工单系统时仍应由下游接受稳定幂等键。

### SSE 流式传输

公共事件协议：`start`（开始）、`delta`（增量内容）、`done`（完成）、`error`（错误）。三个 Provider 各自实现真流式，机制不同：Spring AI 使用阿里云官方 DashScope SDK `Generation.streamCall()`（`Flowable<GenerationResult>` + `incrementalOutput=true`）；LangChain4j 使用 `StreamingChatLanguageModel` + `StreamingResponseHandler.onNext()` 回调；Native 使用 JDK HttpClient 逐行解析 SSE `data:` 事件。流式降级策略：首 token 发出前可切换备用 Provider，发出后封锁降级（避免内容重复/断裂）。

### MCP Server

基于 JSON-RPC 2.0 协议，暴露 6 个工具（5 个运维工具 + `rag_search` 知识库检索），支持外部 AI（如 Codex、Claude）通过 stdio bridge 自动发现并调用运维工具。

### 安全防护

项目提供 API Key、JWT、请求级安全约束、敏感词过滤、prompt 注入防护，以及基于 Redis + Lua 的用户/IP 维度滑动窗口限流。

> [!WARNING]
> 当前 `application.yml` 默认关闭 API Key 和 JWT；JWT 关闭时 `SecurityConfig` 会放行 `/api/**`。这便于本地演示，但不适合作为生产默认值。部署到共享或公网环境前，必须启用 JWT、替换默认 Secret、限制 CORS，并对 `/api/rag/evaluate`、知识库重建、Memory 管理和 MCP 接口配置独立权限及限流。Memory 的用户隔离必须来自认证 principal，不能信任匿名请求中的 `userId`。

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.5.3、Java 21、MyBatis-Plus 3.5.12 |
| AI 框架 | Spring AI Alibaba 1.0.0.2（DashScope）、LangChain4j 0.36.2、原生 JDK HttpClient |
| 向量数据库 | PostgreSQL + pgvector（HNSW 索引，1536 维） |
| 关系数据库 | MySQL 8.0（业务数据）、Flyway 迁移 |
| 缓存 | Redis 7（滑动窗口指标、会话缓存、限流） |
| 消息队列 | RabbitMQ（文档管道异步处理） |
| 熔断降级 | Resilience4j 2.2.0（CircuitBreaker 三态熔断） |
| 文档解析 | Apache Tika 2.9.2（PDF/Word/MD/TXT） |
| 工具库 | Caffeine、SpringDoc OpenAPI、jtokkit（BPE tokenizer）、pgvector-java 0.1.6 |
| 本地模型 | Ollama（qwen2.5:7b，可选降级 Provider） |
| 部署 | Dockerfile + Docker Compose 全栈编排（配置已提交，尚待真实环境启动验收） |

---

## 项目结构

```
com.zhiwei
├── controller
│   ├── AiController              # /ai/chat, /ai/agent, /ai/chat/stream, /ai/agent/stream
│   ├── RagController             # 文档上传、重建和检索
│   ├── RagEvaluateController     # RAG 评测
│   ├── MemoryController          # 摘要、Checkpoint、事实、冲突、审计和遗忘
│   ├── McpController             # /api/mcp (JSON-RPC)
│   └── SystemController          # /system/usage, /system/router/status, /system/ratelimit
├── ai
│   ├── provider
│   │   ├── ModelProvider               # 统一接口（Strategy）
│   │   ├── ModelProviderRouter         # 智能路由（核心）
│   │   ├── FailoverHandler             # 故障降级 + Resilience4j 熔断器
│   │   ├── HealthMonitor              # 定期心跳 + 指标维护
│   │   ├── ProviderMetrics            # 滑动窗口指标
│   │   ├── springai/
│   │   │   ├── SpringAiAlibabaProvider     # Spring AI → DashScope
│   │   │   ├── SpringAiRagAdvisor          # Advisor 链注入 RAG
│   │   │   └── SpringAiSafetyAdvisor       # 安全约束
│   │   ├── langchain4j/
│   │   │   ├── LangChain4jOpenAiProvider   # LangChain4j → OpenAI 兼容
│   │   │   ├── LangChain4jPersistentChatMemoryStore  # 持久化记忆
│   │   │   └── LangChain4jRagContentRetriever        # RAG 检索器
│   │   └── native/
│   │       ├── NativeDashScopeProvider     # 原生 HTTP → DashScope（降级基座）
│   │       └── CostCalibrationInterceptor   # 解析实际计费 usage → 校准成本权重
│   ├── conversation           # 消息归一化/历史/裁剪/AgentReply 编解码
│   ├── prompt                 # 模板化 prompt + Few-Shot
│   ├── intent                 # 意图识别（5 类）
│   ├── tool                   # 工具执行、审批、超时、重试、隔离与幂等
│   ├── rag                    # pgvector、关键词、RRF、Reranker 与 Agentic RAG
│   ├── memory                 # 摘要、Checkpoint、长期事实、冲突、审计与遗忘
│   ├── agent/runtime          # Token Budget、运行上下文和节点观测
│   ├── embedding             # EmbeddingClient 抽象 + 实现
│   ├── stream                 # SSE 事件封装
│   ├── usage                  # 成本统计 + 聚合报表（喂给路由器）
│   ├── reply                  # 结构化卡片组装
│   └── mcp                    # MCP JSON-RPC Server
├── knowledge
│   ├── pipeline               # 文档处理管道（MQ 驱动）
│   ├── parser                 # Tika 文档解析
│   └── chunker                # 智能分块（512 Token + 64 重叠）
├── config                     # Spring 配置
├── entity                     # 数据库实体
├── mapper                     # MyBatis-Plus Mapper
├── common                     # 通用工具
└── security                   # 认证 + 限流
```

---

## API 概览

### AI 服务

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/ai/chat` | POST | 普通聊天（同步） |
| `/api/ai/agent` | POST | Agent 全链路（意图识别 + 工具调用 + 卡片） |
| `/api/ai/chat/stream` | POST | SSE 流式聊天 |
| `/api/ai/agent/stream` | POST | SSE 流式 Agent |

### 知识库

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/rag/upload` | POST | 上传单个文档（PDF/Word/MD/TXT） |
| `/api/rag/upload/batch` | POST | 批量上传文档 |
| `/api/rag/upload/stream` | POST | 上传文档并通过 SSE 实时推送处理进度 |
| `/api/rag/preview` | POST | 预览分块结果（不入库） |
| `/api/rag/document/{id}` | GET | 查询文档处理状态 |
| `/api/rag/documents` | GET | 文档列表（分页） |
| `/api/rag/search` | POST | RAG 检索 |
| `/api/rag/evaluate` | POST | 检索质量评估 |

### 分层记忆

| 接口前缀 | 说明 |
|----------|------|
| `/api/memories/summaries` | 会话摘要读取与维护 |
| `/api/memories/checkpoints` | Agent/RAG checkpoint 状态管理 |
| `/api/memories/facts` | 长期事实、版本和冲突管理 |
| `/api/memories/audit` | 记忆操作审计 |
| `/api/memories/forget` | 创建并查询不可逆遗忘任务 |

### MCP Server

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/mcp` | POST | JSON-RPC 端点（initialize/tools/list/tools/call） |

### 系统监控

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/system/router/status` | GET | 路由状态（各 Provider 指标/熔断状态） |
| `/api/system/usage` | GET | 用量统计 |
| `/api/system/ratelimit` | GET | 限流配置 |

---

## 技术亮点

- **Strategy + Adapter 模式**：`ModelProvider` 统一接口，3 个 Provider 各有明确工程定位，不是简单列举"支持 N 种模型"
- **智能路由引擎**：基于成功率 45% + 延迟 25% + 成本 15% + 偏好加成 15% 实时打分，路由决策动态调整而非静态配置
- **Resilience4j 熔断降级**：三态熔断器（CLOSED/OPEN/HALF_OPEN）+ 降级链 + 幂等重试，Native Provider 作为最后一道防线
- **双重闭环成本模型**：指标驱动路由决策 + 实际计费值校准成本权重，成本统计实时喂给路由引擎
- **RAG 混合检索**：RRF 融合（Reciprocal Rank Fusion，k=60）合并向量通道与关键词通道，解决纯向量检索的术语匹配问题；LLM 查询改写（指代消解 + 子问题分解）+ Caffeine 本地缓存
- **可量化 RAG 评测**：冻结语料、分级相关性判断，以及 HitRate/Recall/Precision/MRR/nDCG 指标；严格区分离线算法报告与线上生产检索报告
- **Agentic RAG**：查询分类、检索规划、并发检索、证据评分、反馈改写、受证据约束生成与引用校验
- **可靠 Agent 执行**：Token Budget、节点预算、工具超时、只读重试、Circuit Breaker、Bulkhead、审批、幂等与结构化终态
- **分层记忆**：滚动摘要、Checkpoint、带不可变版本的长期事实、冲突裁决、审计、逻辑删除和不可逆遗忘任务
- **MCP Server**：独立进程部署，JSON-RPC 2.0 协议，6 个工具暴露，支持外部 AI 自动发现并调用运维工具
- **SSE 真流式（三种机制）**：Spring AI 使用 DashScope SDK `Generation.streamCall()` + `incrementalOutput(true)`；LangChain4j 使用 `StreamingChatLanguageModel` + `StreamingResponseHandler`；Native 使用 JDK HttpClient 手工解析 SSE 行。统一 start/delta/done/error 事件协议

---

## 快速开始

### 前置条件

- JDK 21+
- Docker Desktop
- DashScope API Key（[阿里云百炼](https://bailian.console.aliyun.com/)）

### 启动

```bash
# 1. 配置模型 API Key
export DASHSCOPE_API_KEY=你的_DashScope_API_Key

# 生产或共享环境必须额外启用 JWT，并使用随机高强度 Secret
export ZHIWEI_SECURITY_JWT_ENABLED=true
export ZHIWEI_JWT_SECRET=至少32字节的随机Secret

# 2. 构建镜像并启动中间件 + 主应用
docker compose up -d --build

# 3. 如需独立 MCP Server，再启用 mcp profile
docker compose --profile mcp up -d --build zhiwei-mcp
```

首次启动会自动初始化 MySQL Flyway 表和 PostgreSQL pgvector 表。可通过
`docker compose ps` 查看各服务状态。

### 本地开发启动

```bash
# 1. 配置 API Key，并仅启动本地开发所需中间件
export DASHSCOPE_API_KEY=你的_DashScope_API_Key
docker compose up -d mysql postgres redis rabbitmq

# 2. 主应用（Windows 使用 mvnw.cmd）
./mvnw spring-boot:run

# 3. MCP Server（新终端，可选）
./mvnw spring-boot:run -Dspring-boot.run.profiles=mcp -Dspring-boot.run.arguments="--server.port=8081"
```

默认配置连接 `localhost` 上由 Compose 启动的中间件；容器内通过已提交的
`application-docker.yml` 切换为 Compose 服务名。私有的
`application-dev.yml` 只用于个人覆盖配置，不是启动必需文件。

### 第五阶段：分层记忆

记忆维护和上下文注入默认关闭。启用前应确认 Flyway 已成功应用 `V4__agent_memory.sql`，并先启用 JWT 用户身份隔离：

```bash
export MEMORY_ENABLED=true
export MEMORY_INJECT_ENABLED=true
# 可选：仅生成候选事实，不会自动覆盖已确认事实
export MEMORY_FACT_EXTRACTION_ENABLED=false
```

记忆分为三层：

- **短期摘要**：按会话滚动压缩，记录 `coveredThroughMessageId`，只覆盖已处理消息；
- **Checkpoint**：保存 Agent/RAG 节点、待办、工具结果摘要和运行状态，禁止写入凭证、完整 prompt 或完整日志；当前支持状态流转和审计，不代表已实现中间节点完整续跑；
- **长期事实**：按 `namespace/subject/predicate/value` 保存。模型抽取结果先是 `PROPOSED`，显式事实修改产生不可变版本，冲突必须裁决后才能替换当前值。

记忆 API 位于 `/api/memories`，包含 summary、checkpoint、facts、versions、conflicts、audit 和 forget。更新/删除需要 `If-Match: "<version>"`；版本冲突返回 HTTP 409，缺少版本返回 HTTP 428。`DELETE` 是保留期内可恢复的逻辑删除，`POST /api/memories/forget` 是清除在线 MySQL、Redis 和事实版本的不可逆任务，结果可通过 `/api/memories/forget/{jobId}` 查询。

摘要维护在对话事务提交后异步执行，Redis 不可用或摘要模型失败不会回滚已经完成的对话。审计只保存资源、版本、操作者、原因和哈希，不保存正文；遗忘完成后不能通过在线审计还原正文。数据库备份、日志平台和死信队列仍按各自保留策略过期，系统不宣称备份即时物理擦除。关闭 `MEMORY_INJECT_ENABLED` 可回退到原有最近消息上下文。

当前摘要和事实维护使用事务提交后的进程内异步事件；尚未接入 Outbox、持久队列或补偿扫描，因此进程崩溃和瞬时故障可能导致维护任务缺失。生产部署前应补充可重放的可靠投递链路。

### 访问

| 地址 | 说明 |
|------|------|
| http://localhost:8080/swagger-ui.html | API 文档 |
| http://localhost:8080/actuator | 健康检查 |
| http://localhost:8081/api/mcp | MCP Server（JSON-RPC 2.0） |
| http://localhost:15672 | RabbitMQ 管理面板 |

---

## 测试与质量边界

```bash
# 完整编译、测试并打包主应用与 MCP Server
./mvnw clean verify

# 校验冻结 RAG 数据集
python scripts/run-rag-evaluation.py --validate-only
```

当前自动化测试以单元测试和静态配置检查为主。尚未建立仓库级 CI，也未配置 JaCoCo、静态分析、依赖漏洞扫描和 Testcontainers 集成测试门禁。数据库迁移测试目前主要检查 SQL 文本，仍需在真实 MySQL/PostgreSQL/pgvector、Redis 和 RabbitMQ 环境中验证全新安装与升级路径。

### 已知限制

- 默认配置面向本地演示，API Key/JWT 默认关闭，不能直接暴露到公网；
- 仓库内现有 RAG 报告是离线模拟基线，不代表线上 pgvector 检索质量；
- 关键词检索仍依赖向量召回成功，尚不能作为独立降级通道；
- Agentic RAG checkpoint 尚不支持从完整中间状态续跑；
- Memory 异步维护缺少 Outbox/重放/补偿机制；
- Docker Compose 尚需在具备 Docker 的环境完成镜像构建、健康检查和端到端验收；
- CI、真实数据库迁移测试、覆盖率/静态分析/漏洞扫描门禁尚未补齐。

---

## License

MIT
