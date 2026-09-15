#!/usr/bin/env python3
"""Build the frozen, manually curated RAG baseline corpus and judgments."""

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evaluation" / "rag"

# Each query is deliberately written and reviewed in this source file; no query or
# relevance label is inferred from retrieval output.
DOCUMENTS = [
    {
        "id": "ops-redis-avalanche",
        "title": "Redis 缓存雪崩处置",
        "content": "Redis 缓存雪崩表现为大量缓存同时失效，数据库 QPS 突增。处置步骤：先限流和熔断保护数据库，再为热点键预热；长期方案是 TTL 加随机抖动、热点永不过期配合异步刷新，并建立缓存命中率告警。",
        "queries": ["Redis 大量 key 同时过期怎么办？", "缓存同时失效导致数据库被打满如何处理？", "缓存雪崩的应急处置和长期治理是什么？", "TTL 随机抖动与热点预热分别解决什么问题？"],
        "related": [("ops-rate-limit", 2), ("ops-circuit-breaker", 1)],
    },
    {
        "id": "ops-redis-penetration",
        "title": "Redis 缓存穿透治理",
        "content": "缓存穿透是请求持续查询不存在的数据，缓存和数据库都无法命中。应使用布隆过滤器在入口拦截不存在的键，并对合法但不存在的数据写入短 TTL 空对象；同时限制异常来源请求频率，监控空值命中率。",
        "queries": ["不存在的数据一直打到数据库怎么防？", "布隆过滤器和空对象缓存如何组合？", "什么是缓存穿透，应该监控哪个指标？", "恶意查询随机 ID 时如何保护 MySQL？"],
        "related": [("ops-rate-limit", 2), ("ops-redis-avalanche", 1)],
    },
    {
        "id": "ops-redis-hotkey",
        "title": "Redis 热点 Key 击穿治理",
        "content": "缓存击穿是单个热点 Key 过期瞬间大量并发回源。可用互斥锁保证只有一个线程重建缓存，其他请求返回旧副本；也可采用逻辑过期和双缓存副本实现读请求不阻塞。锁必须设置租约并在 finally 中释放。",
        "queries": ["单个热点 key 过期导致并发回源怎么处理？", "缓存击穿为什么要用互斥重建？", "逻辑过期和双缓存副本有什么作用？", "热点缓存重建锁需要注意什么？"],
        "related": [("ops-redis-avalanche", 1), ("ops-rate-limit", 1)],
    },
    {
        "id": "ops-mysql-slow-query",
        "title": "MySQL 慢查询排查",
        "content": "MySQL 慢查询排查先确认慢日志和 performance_schema，再使用 EXPLAIN ANALYZE 查看执行计划、扫描行数、回表与临时表。检查联合索引最左前缀、隐式类型转换和选择性；修改索引后对比 P95 延迟与 rows_examined，避免只看单次耗时。",
        "queries": ["MySQL 接口变慢如何定位 SQL？", "EXPLAIN ANALYZE 排查慢查询看哪些信息？", "联合索引最左前缀失效怎么确认？", "慢 SQL 优化后应该对比哪些指标？"],
        "related": [("ops-cpu-high", 1), ("ops-observability", 1)],
    },
    {
        "id": "ops-mysql-deadlock",
        "title": "MySQL 死锁处置",
        "content": "出现 MySQL 死锁时先保存 SHOW ENGINE INNODB STATUS 和事务日志，识别锁等待环。应用应捕获死锁错误并仅对幂等事务做有限次数退避重试；长期通过统一访问顺序、缩短事务、补充索引减少锁范围，不能简单提高锁等待超时。",
        "queries": ["MySQL 报 deadlock found 怎么处理？", "如何从 InnoDB 状态分析锁等待环？", "死锁后哪些事务可以自动重试？", "统一加锁顺序为什么能减少死锁？"],
        "related": [("ops-mysql-slow-query", 1), ("ops-observability", 1)],
    },
    {
        "id": "ops-postgres-connections",
        "title": "PostgreSQL 连接耗尽处置",
        "content": "PostgreSQL 出现 remaining connection slots are reserved 时，先按 application_name 和 state 查询 pg_stat_activity，终止确认无用的 idle in transaction 会话。随后检查连接池泄漏、最大池大小和超时；不要直接无限上调 max_connections，应结合内存和 PgBouncer。",
        "queries": ["PostgreSQL 连接槽耗尽怎么办？", "remaining connection slots are reserved 如何排查？", "pg_stat_activity 如何找连接泄漏？", "为什么不能直接无限增大 max_connections？"],
        "related": [("ops-memory-high", 1), ("ops-observability", 1)],
    },
    {
        "id": "ops-rabbitmq-backlog",
        "title": "RabbitMQ 消息堆积处置",
        "content": "RabbitMQ 消息堆积先确认 ready、unacked、publish rate 和 ack rate。消费者健康但吞吐不足时水平扩容并检查 prefetch；大量 unacked 要检查慢处理和手工 ack；发布突增时启用入口限流。扩容前确认下游数据库承载能力，避免把堆积转移到数据库。",
        "queries": ["RabbitMQ 队列消息越堆越多怎么排查？", "ready 很高和 unacked 很高分别说明什么？", "消费者扩容前为什么要确认数据库容量？", "prefetch 和 ack rate 如何影响消息堆积？"],
        "related": [("ops-rate-limit", 2), ("ops-mysql-slow-query", 1)],
    },
    {
        "id": "ops-rabbitmq-dlq",
        "title": "RabbitMQ 死信队列处理",
        "content": "死信消息必须记录原队列、异常类型、重试次数和业务键。处理流程是隔离消费者、分析可重试性、修复根因后按幂等键小批量重放；不可恢复消息进入人工工单。禁止无上限 requeue，否则会形成毒消息循环并持续占用消费者。",
        "queries": ["RabbitMQ 死信队列里的消息怎么重放？", "毒消息为什么不能无限 requeue？", "DLQ 记录需要包含哪些字段？", "死信消息如何区分可重试和人工处理？"],
        "related": [("ops-rabbitmq-backlog", 2), ("ops-incident-ticket", 1)],
    },
    {
        "id": "ops-jvm-oom",
        "title": "JVM OutOfMemoryError 处置",
        "content": "JVM OOM 时先保留 heap dump、GC 日志和进程指标，再根据 Java heap space、Metaspace、Direct buffer memory 分类。使用 MAT 查看 dominator tree 和可疑引用链；恢复服务可临时扩容或重启，但根治需要修复泄漏、无界缓存或批量加载。",
        "queries": ["Java 服务 OOM 后第一时间保留什么？", "Java heap space 和 Metaspace 如何区分？", "MAT dominator tree 用来查什么？", "重启为什么不能算 OOM 根治方案？"],
        "related": [("ops-gc-pause", 2), ("ops-memory-high", 2)],
    },
    {
        "id": "ops-gc-pause",
        "title": "JVM GC 停顿排查",
        "content": "GC 停顿升高先关联暂停时间、分配速率、老年代占用和请求 P99。G1 下关注 evacuation failure、humongous allocation 与 concurrent cycle；先减少大对象和瞬时分配，再评估堆大小、暂停目标和 Region 参数，避免只靠增大堆掩盖泄漏。",
        "queries": ["Java 请求 P99 抖动怀疑 GC 怎么验证？", "G1 evacuation failure 表示什么？", "humongous allocation 如何导致长停顿？", "为什么盲目增大 JVM 堆可能适得其反？"],
        "related": [("ops-jvm-oom", 2), ("ops-memory-high", 1)],
    },
    {
        "id": "ops-cpu-high",
        "title": "Linux CPU 飙高排查",
        "content": "CPU 飙高先用 top 或 pidstat 区分 user、system、iowait 和 steal，再定位进程与线程。Java 进程将线程十进制 ID 转十六进制，在 jstack 中匹配 nid；持续采样火焰图确认热点。若 iowait 高应转查磁盘，不要误判为纯计算瓶颈。",
        "queries": ["Linux CPU 100% 怎么定位到 Java 线程？", "top 里的 user system iowait 如何解释？", "线程 ID 转十六进制后怎样配合 jstack？", "CPU 高但 iowait 占比大应该查什么？"],
        "related": [("ops-disk-full", 1), ("ops-observability", 1)],
    },
    {
        "id": "ops-memory-high",
        "title": "Linux 内存占用高排查",
        "content": "Linux 内存高要区分进程 RSS、page cache、swap 和 cgroup 限制。先看 free、vmstat、smem 与容器 memory.current；page cache 可回收不等同泄漏。持续 swap in/out 或容器接近 memory.max 时应降载并定位增长进程，避免直接 drop_caches。",
        "queries": ["Linux available 很低是否一定内存泄漏？", "RSS page cache 和 swap 怎么区分？", "容器内存接近 memory.max 怎么处理？", "为什么不建议直接执行 drop_caches？"],
        "related": [("ops-jvm-oom", 2), ("ops-cpu-high", 1)],
    },
    {
        "id": "ops-disk-full",
        "title": "磁盘空间耗尽处置",
        "content": "磁盘满先用 df 检查文件系统与 inode，再用 du 定位目录。若 df 与 du 不一致，检查被删除但仍由进程占用的文件 lsof +L1。优先轮转或归档日志并设置保留策略；数据库磁盘满时先阻止继续写入，禁止直接删除数据目录文件。",
        "queries": ["服务器 No space left on device 怎么排查？", "df 和 du 结果不一致通常是什么原因？", "inode 用尽与容量用尽如何区分？", "数据库磁盘满了为什么不能手删数据文件？"],
        "related": [("ops-observability", 1), ("ops-backup-restore", 1)],
    },
    {
        "id": "ops-network-timeout",
        "title": "网络超时分层排查",
        "content": "网络超时按 DNS、建连、TLS、首包和读取五阶段定位。使用 dig、curl -w、openssl s_client、ss 和抓包确认耗时；同时检查客户端连接池、代理超时与服务端线程池。重试必须有总预算、指数退避和抖动，非幂等请求不得盲目重试。",
        "queries": ["接口 timeout 如何判断卡在 DNS 还是 TLS？", "curl -w 能帮助分析哪些网络阶段？", "网络重试为什么要指数退避和抖动？", "非幂等 POST 超时后能否直接重试？"],
        "related": [("ops-circuit-breaker", 2), ("ops-observability", 1)],
    },
    {
        "id": "ops-k8s-crashloop",
        "title": "Kubernetes CrashLoopBackOff 排查",
        "content": "Pod 进入 CrashLoopBackOff 时先看 kubectl describe 的退出码和事件，再用 kubectl logs --previous 获取上一次容器日志。检查启动命令、配置、Secret、依赖可达性与探针；OOMKilled 要转查内存限制。不要只反复 delete pod，因为控制器会重建同样故障实例。",
        "queries": ["Pod CrashLoopBackOff 怎么排查？", "kubectl logs --previous 有什么作用？", "容器退出码 OOMKilled 应该检查什么？", "为什么删除 Pod 不能解决持续崩溃？"],
        "related": [("ops-memory-high", 2), ("ops-spring-startup", 1)],
    },
    {
        "id": "ops-k8s-pending",
        "title": "Kubernetes Pod Pending 排查",
        "content": "Pod 长期 Pending 先看调度事件，常见原因是资源不足、nodeSelector 或 affinity 不匹配、污点未容忍、PVC 未绑定。对比 requests 与节点 allocatable；若是镜像拉取失败通常状态会进入 ImagePullBackOff，应转到镜像排查流程。",
        "queries": ["Kubernetes Pod 一直 Pending 怎么处理？", "调度失败时 requests 和 allocatable 怎么比？", "污点和 toleration 不匹配会有什么现象？", "PVC 未绑定为什么会阻止 Pod 调度？"],
        "related": [("ops-image-pull", 1), ("ops-observability", 1)],
    },
    {
        "id": "ops-image-pull",
        "title": "容器镜像拉取失败",
        "content": "ImagePullBackOff 先从事件区分镜像不存在、仓库鉴权、DNS/TLS 和限流。确认 image 标签与 digest，检查 imagePullSecrets 及节点到仓库的连通性；生产建议固定 digest 并配置镜像缓存。修复后可删除失败 Pod 触发重新拉取。",
        "queries": ["ImagePullBackOff 常见原因有哪些？", "私有仓库拉取失败如何检查 imagePullSecrets？", "生产部署为什么建议固定镜像 digest？", "镜像仓库返回 429 时怎么处理？"],
        "related": [("ops-k8s-pending", 1), ("ops-rate-limit", 1)],
    },
    {
        "id": "ops-spring-startup",
        "title": "Spring Boot 启动失败排查",
        "content": "Spring Boot 启动失败先找最底层 Caused by，不要只看 BeanCreationException 外层。按配置绑定、Bean 循环依赖、端口占用、数据库迁移和外部依赖分类；使用 --debug 查看条件装配报告。配置问题要核对生效 profile 与环境变量来源。",
        "queries": ["Spring Boot BeanCreationException 怎么定位根因？", "启动日志多层 Caused by 应该看哪一层？", "如何查看自动配置条件报告？", "本地配置正确但启动仍失败为什么要检查 profile？"],
        "related": [("ops-network-timeout", 1), ("ops-observability", 1)],
    },
    {
        "id": "ops-circuit-breaker",
        "title": "服务熔断器配置与处置",
        "content": "熔断器 CLOSED 正常放行，失败率超过阈值进入 OPEN 快速失败，等待后进入 HALF_OPEN 试探恢复。阈值应同时考虑最小调用数、滑动窗口、慢调用比例和打开时长。熔断必须配合降级结果、监控和恢复探测，不能把业务错误都计为系统故障。",
        "queries": ["Resilience4j CLOSED OPEN HALF_OPEN 如何流转？", "熔断器为什么需要 minimum number of calls？", "慢调用比例如何参与熔断判断？", "熔断后如何通过半开探测恢复？"],
        "related": [("ops-network-timeout", 2), ("ops-rate-limit", 1)],
    },
    {
        "id": "ops-rate-limit",
        "title": "接口限流与 429 处置",
        "content": "接口限流可采用令牌桶或 Redis Lua 滑动窗口，键应包含租户与接口维度。返回 429 时提供 Retry-After，并区分用户配额和系统过载；客户端按 Retry-After 退避。容量恢复前不要立即放开全部流量，可逐级提升阈值观察下游。",
        "queries": ["API 返回 429 应该怎样处理？", "Redis Lua 滑动窗口限流如何设计 key？", "Retry-After 响应头有什么作用？", "限流解除为什么要逐级放量？"],
        "related": [("ops-circuit-breaker", 2), ("ops-observability", 1)],
    },
    {
        "id": "ops-auth-401",
        "title": "API 401 鉴权失败排查",
        "content": "401 表示未通过身份认证，先检查 Authorization 格式、令牌过期时间、签名算法、issuer 与 audience。若使用 API Key，核对请求头名和密钥轮换状态；403 则通常是已认证但权限不足。日志禁止打印完整 token，只记录哈希前缀或 jti。",
        "queries": ["接口返回 401 从哪些地方排查？", "401 和 403 的区别是什么？", "JWT issuer audience 不匹配会怎样？", "鉴权日志为什么不能打印完整 token？"],
        "related": [("ops-observability", 1), ("ops-network-timeout", 1)],
    },
    {
        "id": "ops-sse-disconnect",
        "title": "SSE 流式连接中断排查",
        "content": "SSE 中断要检查代理 buffering、idle timeout、客户端取消与服务端异常。响应需使用 text/event-stream，定期发送心跳并在完成时清理 emitter；首个 token 发出后切换上游会造成内容重复，因此降级只允许发生在首包前。客户端可携带 Last-Event-ID 做可恢复事件续传。",
        "queries": ["SSE 连接经 Nginx 一会就断怎么排查？", "text/event-stream 为什么要定期发心跳？", "流式响应首 token 后为什么不能切换 Provider？", "Last-Event-ID 能解决什么问题？"],
        "related": [("ops-network-timeout", 2), ("ops-observability", 1)],
    },
    {
        "id": "ops-deploy-rollback",
        "title": "生产发布回滚流程",
        "content": "发布异常先冻结继续放量，确认版本、变更项和错误预算，再按预案回滚应用与配置。数据库变更必须向后兼容，破坏性迁移不能只回滚二进制；回滚后验证健康检查、核心交易、错误率和数据一致性，并记录时间线。",
        "queries": ["生产发布后错误率飙升如何回滚？", "为什么数据库迁移会让应用回滚失败？", "回滚完成后必须验证哪些指标？", "发布事故中为什么要先冻结继续放量？"],
        "related": [("ops-canary", 2), ("ops-incident-ticket", 1)],
    },
    {
        "id": "ops-canary",
        "title": "灰度发布与放量",
        "content": "灰度发布按 1%、5%、20%、50%、100% 分阶段放量，每阶段观察错误率、P95/P99、资源和业务转化。设置自动停止阈值并保留稳定版本实例；样本量不足时不能因短时零错误直接全量。配置和数据库兼容性也必须纳入灰度验证。",
        "queries": ["灰度发布应该如何分阶段放量？", "金丝雀发布每阶段观察哪些指标？", "为什么 1% 流量零错误不能直接全量？", "灰度期间如何设置自动停止阈值？"],
        "related": [("ops-deploy-rollback", 3), ("ops-observability", 1)],
    },
    {
        "id": "ops-observability",
        "title": "日志指标链路关联排障",
        "content": "排障应使用 traceId 关联日志、指标和调用链。先由 SLO 告警确定影响范围和时间窗，再从入口 trace 下钻到慢 Span，并结合实例 CPU、GC、数据库与队列指标验证。结构化日志包含时间、级别、服务、实例、traceId 和错误码，但必须脱敏。",
        "queries": ["如何用 traceId 关联日志和调用链？", "从 SLO 告警开始排障的步骤是什么？", "结构化日志至少应包含哪些字段？", "慢 Span 发现后为什么还要结合资源指标验证？"],
        "related": [("ops-incident-ticket", 1), ("ops-cpu-high", 1)],
    },
    {
        "id": "ops-incident-ticket",
        "title": "故障工单与时间线",
        "content": "故障工单应记录影响范围、严重级别、发现时间、负责人、处置动作和证据链接。时间线使用统一时区并区分事实与推测；恢复后补充根因、促成因素、改进项、责任人和截止时间。复盘目标是改进系统，不做个人归责。",
        "queries": ["线上故障工单需要记录哪些内容？", "事故时间线为什么要区分事实和推测？", "复盘改进项必须包含什么？", "无责复盘的目标是什么？"],
        "related": [("ops-observability", 2), ("ops-deploy-rollback", 1)],
    },
    {
        "id": "ops-backup-restore",
        "title": "数据库备份恢复演练",
        "content": "备份可信度必须通过恢复演练验证。先定义 RPO 与 RTO，执行全量加增量或 WAL/binlog 备份，并将副本存放在独立故障域。演练恢复到隔离环境后校验表数量、校验和、关键业务查询与时间点；记录实际恢复耗时和缺失数据窗口。",
        "queries": ["为什么有备份不等于能恢复？", "RPO 和 RTO 分别表示什么？", "数据库恢复演练后如何校验数据？", "备份副本为什么要放在独立故障域？"],
        "related": [("ops-disk-full", 1), ("ops-incident-ticket", 1)],
    },
    {
        "id": "ops-pgvector-hnsw",
        "title": "pgvector HNSW 索引调优",
        "content": "pgvector HNSW 以更高内存和构建成本换取高召回低延迟。建索引需选择与查询一致的 vector_cosine_ops、vector_l2_ops 或 inner product 操作类；查询时用 hnsw.ef_search 调整召回与延迟。评估必须使用固定语料和标注集，不能只比较单条查询。",
        "queries": ["pgvector HNSW 如何平衡召回率和延迟？", "余弦距离应该选择哪个 HNSW operator class？", "hnsw.ef_search 参数有什么作用？", "向量索引调优为什么要用固定评测集？"],
        "related": [("ops-rag-tuning", 2), ("ops-postgres-connections", 1)],
    },
    {
        "id": "ops-rag-tuning",
        "title": "RAG 混合检索调优",
        "content": "RAG 混合检索应分别评估向量通道、关键词通道与 RRF 融合。固定 topK 后用 HitRate、Recall、Precision、MRR 和 nDCG 比较；术语与错误码查询通常提高关键词权重，语义改写问题偏向向量。candidateK 影响候选覆盖和延迟，调优报告必须记录语料快照、模型和参数。",
        "queries": ["RAG 检索应该用哪些离线指标评测？", "RRF 混合检索如何比较向量和关键词策略？", "错误码查询为什么适合提高关键词权重？", "candidateK 调大对召回和延迟有什么影响？"],
        "related": [("ops-pgvector-hnsw", 2), ("ops-observability", 1)],
    },
    {
        "id": "ops-idempotency",
        "title": "接口幂等与重试安全",
        "content": "写接口使用 Idempotency-Key 标识一次业务操作，服务端保存请求指纹与首次结果。相同 key 且指纹一致直接返回缓存结果，指纹不同应返回冲突；处理中状态要防止并发重复执行。幂等记录 TTL 应覆盖客户端最大重试窗口，支付等场景还需业务唯一约束。",
        "queries": ["POST 请求超时重试怎样避免重复扣款？", "Idempotency-Key 对应的请求指纹有什么作用？", "相同幂等键但参数不同应该返回什么？", "幂等记录 TTL 应该如何确定？"],
        "related": [("ops-network-timeout", 2), ("ops-mysql-deadlock", 1)],
    },
    {
        "id": "ops-secret-rotation",
        "title": "密钥轮换与泄漏处置",
        "content": "密钥疑似泄漏时先吊销或禁用旧密钥，再签发新密钥并分阶段更新消费者。密钥只应存放在 Secret 管理系统，通过短期凭证注入，禁止提交到 Git 或打印到日志。轮换期间监控旧 key 调用来源，确认归零后完成删除，并审计泄漏窗口内的访问。",
        "queries": ["API Key 泄漏后正确处置顺序是什么？", "密钥为什么不能写进 application.yml 提交？", "密钥轮换期间如何发现遗漏的消费者？", "旧密钥调用归零后还需要做什么？"],
        "related": [("ops-auth-401", 2), ("ops-observability", 1)],
    },
]


def dump(path: Path, value: object) -> str:
    text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8")
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    corpus = {
        "version": "1.0",
        "dataset": "zhiwei-ops-baseline-v1",
        "documentId": 990001,
        "documents": [
            {"sourceId": doc["id"], "title": doc["title"], "content": doc["content"]}
            for doc in DOCUMENTS
        ],
    }

    cases = []
    for doc_index, doc in enumerate(DOCUMENTS, start=1):
        for query_index, query in enumerate(doc["queries"], start=1):
            judgments = [{"sourceId": doc["id"], "relevance": 3}]
            if query_index == 4:
                judgments.extend(
                    {"sourceId": source_id, "relevance": relevance}
                    for source_id, relevance in doc["related"]
                )
            cases.append({
                "caseId": f"ops-{doc_index:03d}-{query_index}",
                "category": "multi-document" if query_index == 4 else (
                    "identifier" if query_index == 2 else "direct" if query_index == 1 else "paraphrase"),
                "query": query,
                "relevantDocuments": judgments,
                "annotationNote": f"主答案位于《{doc['title']}》"
                    + ("；同时需要关联处置文档" if query_index == 4 else ""),
            })

    eval_set = {
        "version": "1.0",
        "dataset": "zhiwei-ops-baseline-v1",
        "annotation": {
            "type": "static-manual-curation",
            "creator": "Hermes Agent",
            "humanReviewed": False,
            "guideline": {"3": "直接且充分回答", "2": "重要补充", "1": "边缘相关"},
            "note": "查询和标签逐项静态编写，未由检索结果反推；上线前应由两名领域人员抽检至少 20%。",
        },
        "queries": cases,
    }

    corpus_hash = dump(OUT / "corpus-v1.json", corpus)
    eval_hash = dump(OUT / "eval-set-v1.json", eval_set)
    print(json.dumps({
        "documents": len(corpus["documents"]),
        "queries": len(cases),
        "multiDocumentQueries": sum(len(case["relevantDocuments"]) > 1 for case in cases),
        "corpusSha256": corpus_hash,
        "evalSetSha256": eval_hash,
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
