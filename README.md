# 智维（ZhiWei）— 企业级 AI 知识管理与智能运维平台

> 面向 IT 运维场景的 AI 平台，集成多模型智能路由、故障自动降级、RAG 知识库检索、运维 Agent 与 MCP Server，实现从知识沉淀到智能运维的完整闭环。

[![Java](https://img.shields.io/badge/Java-21-blue)]()
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.3-green)]()
[![Spring AI](https://img.shields.io/badge/Spring_AI_Alibaba-1.0.0.2-orange)]()
[![License](https://img.shields.io/badge/License-MIT-yellow)]()

---

## 项目简介

智维是一个面向 IT 运维场景的企业级 AI 平台。传统运维知识库面临检索准确率低、多模型切换成本高、故障时无可用模型等问题。智维通过统一的 `ModelProvider` 抽象层接入多种 LLM，基于实时成本/延迟/能力指标进行智能路由，配合 Resilience4j 熔断器实现故障自动降级，确保运维场景下的高可用性。

平台核心能力包括：多模型智能路由与故障降级、RAG 知识库检索（支持 PDF/Word/Markdown/TXT）、运维 Agent（自动调用服务器状态查询、日志检索、部署历史、工单创建等工具）、SSE 流式对话、MCP Server（对外暴露运维工具供外部 AI 调用）、成本统计与校准闭环。

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

检索与重排序：pgvector 余弦检索（向量通道）+ 关键词 ILIKE 匹配（关键词通道）→ RRF 融合（Reciprocal Rank Fusion，k=60，向量权重 1.0 / 关键词权重 0.5）→ topK 返回。查询改写：LLM 调用（qwen-plus）实现指代消解 + 子问题分解，简单查询（≤5字符）走快路径跳过，改写结果 Caffeine 本地缓存（1000 条，TTL 600s）。

### Agent 与 Tool Calling

意图识别模块支持 5 类意图（故障排查/日志查询/部署操作/工单创建/知识检索），基于关键词规则匹配实现（非 LLM），硬编码关键词列表 + 置信度阈值（高置信 ≥0.65 直接输出，低置信 <0.20 触发澄清）。运维工具集包含 5 个工具：`queryServerStatus`、`searchLogs`、`queryDeployHistory`、`createTicket`、`queryMetrics`（当前为 Mock 实现，需配置 `zhiwei.ai.tool.mock-enabled=true` 启用）。

Agent 执行流程：意图识别 → 按意图选 Prompt → 工具调用 → 结构化卡片组装（服务器卡片/工单卡片/指标卡片）→ 合并去重 → 降级兜底（模型未调工具时走 RAG 回查）。

### SSE 流式传输

公共事件协议：`start`（开始）、`delta`（增量内容）、`done`（完成）、`error`（错误）。三个 Provider 各自实现真流式，机制不同：Spring AI 使用阿里云官方 DashScope SDK `Generation.streamCall()`（`Flowable<GenerationResult>` + `incrementalOutput=true`）；LangChain4j 使用 `StreamingChatLanguageModel` + `StreamingResponseHandler.onNext()` 回调；Native 使用 JDK HttpClient 逐行解析 SSE `data:` 事件。流式降级策略：首 token 发出前可切换备用 Provider，发出后封锁降级（避免内容重复/断裂）。

### MCP Server

基于 JSON-RPC 2.0 协议，暴露 6 个工具（5 个运维工具 + `rag_search` 知识库检索），支持外部 AI（如 Codex、Claude）通过 stdio bridge 自动发现并调用运维工具。

### 安全防护

请求级安全约束 + 敏感词过滤 + prompt 注入防护。基于 Redis + Lua 滑动窗口实现用户/IP 维度限流。

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
| 部署 | Docker Compose 全栈编排 |

---

## 项目结构

```
com.zhiwei
├── controller
│   ├── AiController              # /ai/chat, /ai/agent, /ai/chat/stream, /ai/agent/stream
│   ├── KnowledgeController       # /knowledge/upload, /knowledge/rebuild, /rag/search, /rag/evaluate
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
│   ├── tool                   # 运维工具执行 + 结果收集
│   ├── rag                    # pgvector 检索 + 重排 + sourceId 回查
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
- **Agent 全链路**：意图识别 → Prompt 模板 → 工具调用 → 结构化卡片 → 降级兜底，5 个运维场景专用工具
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
# 1. 启动中间件 + 主应用
docker compose up -d

# 2. 启动 MCP Server（独立进程，可选）
docker compose up -d zhiwei-mcp

# 3. 配置 API Key
export DASHSCOPE_API_KEY=sk-your-key-here
```

### 本地开发启动

```bash
# 主应用
./mvnw spring-boot:run

# MCP Server（新终端）
./mvnw spring-boot:run -Dspring-boot.run.profiles=mcp -Dspring-boot.run.arguments=--server.port=8081
```

### 访问

| 地址 | 说明 |
|------|------|
| http://localhost:8080/swagger-ui.html | API 文档 |
| http://localhost:8080/actuator | 健康检查 |
| http://localhost:8081/api/mcp | MCP Server（JSON-RPC 2.0） |
| http://localhost:15672 | RabbitMQ 管理面板 |

---

## License

MIT
