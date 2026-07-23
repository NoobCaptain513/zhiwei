# 智维（ZhiWei）— 企业级 AI 知识管理与智能运维平台

> 面向 IT 运维场景的 AI 平台，集成多模型智能路由、故障自动降级、RAG 知识库检索、运维 Agent 与 MCP Server，实现从知识沉淀到智能运维的完整闭环。

[![Java](https://img.shields.io/badge/Java-21-blue)]()
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.0.7-green)]()
[![Spring AI](https://img.shields.io/badge/Spring_AI-2.0-orange)]()
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
 计算得分：score = w₁·(1/cost) + w₂·(1/latency) + w₃·capability_match + w₄·(success_rate)
  ↓
 返回最优 Provider（得分最高且 isAvailable()=true）
  ↓
⑤ Native Provider 的实际计费值异步校准各 Provider 的成本权重
```

路由策略可配置：`COST_OPTIMIZED`（成本优先）、`LATENCY_OPTIMIZED`（延迟优先）、`CAPABILITY_FIRST`（能力优先）、`ROUND_ROBIN`（负载均衡）。

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

检索与重排序：pgvector 余弦检索 → 85% 向量相似度 + 15% 字面匹配混合重排 → topK 返回。

### Agent 与 Tool Calling

意图识别模块支持 5 类意图（故障排查/日志查询/部署操作/工单创建/知识检索），运维工具集包含 5 个工具：`queryServerStatus`、`searchLogs`、`queryDeployHistory`、`createTicket`、`queryMetrics`。

Agent 执行流程：意图识别 → 按意图选 Prompt → 工具调用 → 结构化卡片组装（服务器卡片/工单卡片/指标卡片）→ 合并去重 → 降级兜底（模型未调工具时走 RAG 回查）。

### SSE 流式传输

公共事件协议：`start`（开始）、`delta`（增量内容）、`done`（完成）、`error`（错误）。三个 Provider 各自实现真流式：Spring AI（`ChatClient.stream()`）、LangChain4j（`TokenStream`）、Native（原生 SSE 解析）。

### MCP Server

基于 JSON-RPC 2.0 协议，暴露 6 个工具（5 个运维工具 + `rag_search` 知识库检索），支持外部 AI（如 Codex、Claude）通过 stdio bridge 自动发现并调用运维工具。

### 安全防护

请求级安全约束 + 敏感词过滤 + prompt 注入防护。基于 Redis + Lua 滑动窗口实现用户/IP 维度限流。

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 后端框架 | Spring Boot 4.0.7、Java 21、MyBatis-Plus 3.5.9 |
| AI 框架 | Spring AI Alibaba 2.0、LangChain4j 1.13.1、原生 OkHttp |
| 向量数据库 | PostgreSQL + pgvector（HNSW 索引，1536 维） |
| 关系数据库 | MySQL 8.0（业务数据）、Flyway 迁移 |
| 缓存 | Redis 7（滑动窗口指标、会话缓存、限流） |
| 消息队列 | RabbitMQ（文档管道异步处理） |
| 熔断降级 | Resilience4j 2.3.0（CircuitBreaker 三态熔断） |
| 文档解析 | Apache Tika 3.1.0（PDF/Word/MD/TXT） |
| 工具库 | Hutool 5.8.34、MapStruct 1.6.3、Caffeine、SpringDoc OpenAPI |
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
| `/api/knowledge/upload` | POST | 上传文档（PDF/Word/MD/TXT） |
| `/api/knowledge/rebuild` | POST | 重建知识库索引 |
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
- **智能路由引擎**：基于成本/延迟/能力/成功率多维度实时评分，4 种可配置策略，路由决策不是静态配置而是动态调整
- **Resilience4j 熔断降级**：三态熔断器（CLOSED/OPEN/HALF_OPEN）+ 降级链 + 幂等重试，Native Provider 作为最后一道防线
- **双重闭环成本模型**：指标驱动路由决策 + 实际计费值校准成本权重，成本统计实时喂给路由引擎
- **RAG 混合重排序**：85% 向量余弦相似度 + 15% 字面匹配，解决纯向量检索的术语匹配问题
- **Agent 全链路**：意图识别 → Prompt 模板 → 工具调用 → 结构化卡片 → 降级兜底，5 个运维场景专用工具
- **MCP Server**：JSON-RPC 2.0 协议，6 个工具暴露，支持外部 AI 自动发现并调用运维工具
- **SSE 真流式**：三个 Provider 各自实现真流式输出，统一 start/delta/done/error 事件协议

---

## 快速开始

### 前置条件

- JDK 21+
- Docker Desktop
- DashScope API Key（[阿里云百炼](https://bailian.console.aliyun.com/)）

### 启动

```bash
# 1. 启动中间件
docker compose up -d

# 2. 配置 API Key
export DASHSCOPE_API_KEY=sk-your-key-here

# 3. 启动应用
./mvnw spring-boot:run
```

### 访问

| 地址 | 说明 |
|------|------|
| http://localhost:8080/swagger-ui.html | API 文档 |
| http://localhost:8080/actuator | 健康检查 |
| http://localhost:15672 | RabbitMQ 管理面板 |

---

## License

MIT
