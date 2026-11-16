# Agent 能力评估体系建设

> 项目：iot-protocol-expert
> 文档状态：实施设计基线
> 编写日期：2026-11-16
> 适用仓库：iot-protocol-expert 项目根目录
> 参考源码：本地 Langfuse 参考仓库（HEAD b7c53d5223）、本地 Opik 参考仓库（HEAD d714cc9a3d）
> 实施约束：保持当前项目的 Java、Spring、AgentScope 和前端技术栈；Langfuse/Opik 仅作设计参考，不作为运行时依赖或评估后端。

## 1. 目标与结论

在现有 Agent 在线配置、发布、运行及 OpenTelemetry 链路之上，建设项目自有的 Agent Evaluate 能力，形成以下闭环：

1. 线上 Run 自动采集完整执行轨迹及业务版本信息。
2. 对整次任务、关键 Span 和会话执行多维评分。
3. 支持人工复核与评分修订。
4. 从真实失败 Run 建立评测集。
5. 使用固定的 Agent Release 对评测集执行离线回归。
6. 在 OPS Web 中查看评测任务、结果、Span 链路、评分及失败证据。
7. 可通过评估结果发现问题并迭代 Prompt、Skill、MCP、模型和 Agent Release。

核心决策：复用项目已经有的 OTel、Run、事件、PostgreSQL、MyBatis XML、Agent Runtime、OPS Web 和 Nacos 发布版本模型。项目内实现评估领域逻辑和执行器；不重写通用 Trace 后端，不引入 Langfuse/Opik SDK，不把配置管理迁移到 Langfuse。

架构边界：

- OTel 负责记录一次执行中发生的操作、输入输出、调用关系和耗时。
- Evaluate 负责组织评测数据、执行评分规则、关联评分与 Trace、比较不同 Release。
- Tempo 继续作为 Trace 存储与检索后端；评估记录与评分存入项目 PostgreSQL。
- Nacos 继续承担服务注册、发现和组件目录投影，Skill/MCP/Agent 配置主数据仍由现有 Control 持久化。
- OPS Web 继续提供登录、权限、API 请求封装和页面布局，在 AI 管理区域增加评估页面。

## 2. 范围

### 2.1 本次建设范围

- 会话新建行为：当前选中会话尚无任何聊天请求时，用户点击“新建”不再创建另一个空会话。
- AI Trace 内容采集：元数据、Token、完整消息正文、工具参数、工具定义和工具结果均写入 AI Agent Trace；不对这些 Trace 字段脱敏、不截断。
- 线上评估：Run 终态后针对任务完成度、回答质量、工具执行、轨迹效率、成本等执行评估。
- 离线评测集：创建/维护评测集和用例，指定已发布 Agent Release 执行回归实验。
- 评分：支持确定性规则评分、Judge 模型评分和人工评分；评分可关联 Run、Trace、Span 或 Case。
- Trace 详情：通过项目 AI API 读取 Tempo 中的 Trace，并在 OPS Web 中展现 Span 树、输入输出、耗时、Token、工具参数/结果及评分。
- 评估结果查询：汇总、分页、筛选、详情、评估规则版本、实验对比。
- 复用现有 OPS Web 页面工程，新增 AI 管理下的“能力评估”导航和页面。

### 2.2 不在本次范围

- 不实现通用可视化 DAG 工作流编辑器。
- 不改变 AgentScope 执行循环，不引入第二套 Agent Runtime。
- 不使用 Python 作为必需运行时。
- 不把 Langfuse/Opik 作为生产中的 SaaS、内嵌服务或依赖项。
- 不将 MCP Provider 托管迁移至 Langfuse；现有 MCP Provider 与 Nacos 注册/发现架构保持不变。
- 不修改现有已发布 Flyway 迁移；所有结构调整通过新迁移完成。

## 3. 当前项目基线

以当前代码和项目文档为准：

| 领域 | 当前实现 | 本次复用点 |
|---|---|---|
| Java 与构建 | Java 21，Maven；根 POM 指定 Spring Boot 4.0.9、MyBatis Spring Boot 4.0.1 | 不升级版本，不引入不兼容的 Langfuse/Opik 依赖 |
| Agent 执行 | 独立 iot-protocol-expert-agent-runtime，AgentScope Java 2.0.4 | 在线和离线 Case 均经现有 Runtime 执行 |
| Control | iot-protocol-expert-ai 管理配置、会话、Run、事件和发布 | 在 AI 模块增加 evaluation 领域包及管理 API |
| 数据库 | PostgreSQL；MyBatis Mapper；现有 SQL 位于 resources/mapper/ai/*.xml | 新增 Mapper 接口和 XML；新建表由 Flyway 新迁移维护 |
| Trace | Spring Boot Micrometer/OTel 与 AgentScope TelemetryTracer 共用 OTel Provider；OTLP HTTP 经 Collector 至 Tempo | 复用 Trace ID 和 Tempo；补齐评估所需 Span 属性及查询 API |
| Trace 安全过滤 | ContentFilteringTracer 可截断/清洗内容；Collector 当前有属性删除/脱敏规则 | 按本次要求取消 AI Trace 的内容清洗、脱敏和长度截断 |
| Run | PostgreSQL 持久化 Run、事件、消息、工具调用、traceparent | 作为线上评估源及离线实验的执行载体 |
| 配置发布 | Agent Release 固定模型、Prompt、Skill、MCP 及 manifest digest；Control 为主事实源，Nacos 为注册/发现投影 | 每个评分及实验绑定 agentReleaseId、manifestDigest |
| Web | React 19、TypeScript、Vite、Ant Design、React Query；OPS 与 AI 管理共用同一个 Web 工程 | 沿用 AI 管理 Shell、API client、权限和组件 |
| 新建会话 | Assistant 当前点击“新建”会立即调用 POST /api/ai/v1/conversations | 增加当前会话是否已发起请求的判断，未发起时不创建第二个会话 |

核心现状文档：

- docs/observability.md：现有 OTel Collector、Tempo、AgentScope Span、数据导出与本地链路说明。
- docs/ai-assistant.md：会话、Run、事件、发布、Nacos 投影及 Runtime 链路说明。
- ConversationController、ConversationService：会话创建/Run 提交 API 与领域操作。
- ConversationMapper.xml、RunMapper.xml：现有 PostgreSQL 查询和写入。
- iot-protocol-expert-web/src/pages/Assistant.tsx：助手会话列表与“新建”入口。
- iot-protocol-expert-web/src/pages/ai-admin/AiAdmin.tsx：AI 管理菜单和页面容器。
- iot-protocol-expert-agent-runtime/.../observability/AgentRuntimeTelemetry.java：AgentScope OTel 注册。
- iot-protocol-expert-observability/.../ContentFilteringTracer.java：当前 Trace 内容过滤适配器。

## 4. 成熟实现调研与取舍

### 4.1 Langfuse 模式

本地研究版本：HEAD b7c53d5223。

借鉴：

- Trace / Observation / Session / Dataset Run / Score 分层建模。
- Score 可挂到 Trace、Observation、Session、Experiment Item 等不同粒度。
- 评测集、用例、任务执行、Evaluator、Score 和实验版本形成离线评测闭环。
- 线上评估规则从生产 Trace 触发；离线 Experiment 固定数据集和评估器，横向比较版本。
- 人工标注、代码评估、LLM Judge、最终分数使用统一评分对象呈现。
- SDK/REST 写入评分，平台负责追踪、展示、聚合和实验分析。
- Skill Management 是其新增 beta 能力，管理 SKILL.md 文件版本；本项目已有自己的 Skill Release/Nacos 投影，不迁移。

边界辨析：Langfuse 仓库中的 MCP 接口是供 Agent 访问 Langfuse 自身资源和工具的 MCP Server，不等同于本项目对任意 MCP Provider 的托管/注册中心。

### 4.2 Opik 模式

本地研究版本：HEAD d714cc9a3d。

借鉴：

- OTel Span 导入后从根 Span 构建 Trace。
- Evaluator 可声明作用域：Trace、Thread 或 Span，并定义过滤条件、采样比例和 Score。
- 线上评估与固定 Dataset 的离线 Experiment 分离。
- LLM Judge、代码规则、人工反馈可以产生同一类反馈分数。
- Score 保留 Evaluator 名称、分值及关联对象，便于看板和历史对比。
- OTel HTTP ingestion 支持通用语言的 OpenTelemetry SDK；项目 Java 埋点不需要改用 Python SDK。

边界辨析：本项目保留 OTel 与 Tempo；仅借鉴 Opik 的评估对象、作用域和工作流。Evaluator 必须使用 Java 实现或由当前项目模型客户端运行，不把 Opik Python Evaluator 当作生产依赖。

### 4.3 本项目取舍

1. 统一内部主对象为 Evaluation Dataset、Case、Evaluator Definition、Evaluation Run、Evaluation Result、Evaluation Score。
2. 将评估规则版本和 Agent Release 快照一起固定，保障结果可复核。
3. 线上 Evaluate 采用可配置采样和有界异步队列；基础确定性指标不调用 Judge 模型。
4. 离线实验使用正式 Agent Run 逻辑，但显式标记为 EVALUATION 来源；会话及 Run 在用户普通会话列表中隐藏。
5. Span Score 是可选的细粒度能力；默认评估整次 Run，并仅对有明确准则的模型调用/工具调用 Span 评分。
6. 业务结果使用确定性校验；LLM Judge 只负责语义判断，保留理由与证据；不把 Judge 单一分数当作系统事实。
7. 现有 OTel Trace 为事实执行证据；Evaluation DB 只持久化分数、规则版本、Run/Trace/Span ID、必要的简短证据和实验关系，不重复存储整份 Trace。
8. 线上 Trace 完整保留内容按本需求不脱敏、不截断；Trace 查询 API 必须经过应用身份和项目授权。

## 5. 总体架构

~~~mermaid
flowchart LR
  UI[OPS Web<br/>AI 能力评估] -->|HTTP JSON| CTRL[AI Control<br/>Evaluation API / Service]
  CTRL --> PG[(PostgreSQL<br/>Dataset / Case / Evaluator<br/>Eval Run / Result / Score)]
  CTRL -->|提交标准 Run| RUN[现有 Run Scheduler]
  RUN --> RUNTIME[Agent Runtime<br/>AgentScope]
  RUNTIME -->|OTLP HTTP| COL[OTel Collector]
  COL --> TEMPO[(Tempo)]
  CTRL -->|查询 Trace ID| TEMPO
  CTRL -->|Run 完成事件| EVAL[Evaluation Worker]
  EVAL -->|规则评分 / Judge| SCORE[Score Service]
  SCORE --> PG
  OPS[OPS Web] -->|查询结果与 Span 树| CTRL
  N[Nacos] -. Agent Release / Skill / MCP 版本发现 .-> RUNTIME
~~~

### 5.1 服务职责

| 组件 | 职责 |
|---|---|
| OPS Web | 数据集、用例、评估器和实验管理；运行状态、Trace、分数和对比展示；人工复核 |
| AI Control | 权限、API、评估领域对象、持久化、调度、Run/Trace/Score 聚合；查询 Tempo |
| Existing Run Scheduler | 继续处理 Run 队列、幂等、取消、状态迁移，不新增另一套 Agent 执行链 |
| Agent Runtime | 依不可变 Agent Release 装载 Agent 并执行；产生 AgentScope Trace 和完整 Agent 事件 |
| OTel Collector + Tempo | 收集、传输、保存和检索原始 Trace |
| PostgreSQL | 保存 Dataset、Case、Evaluator 配置、Eval Run/Result/Score、人工判定 |
| Nacos | 继续按现有职责注册/发现服务及已发布组件；不存储评测数据 |

### 5.2 评估流程

线上：

1. Agent Run 进入 COMPLETED、FAILED、CANCELLED 或 TIMED_OUT 终态。
2. Run 完成入口写入线上评估任务，按规则版本和采样策略产生待评记录。
3. Worker 读取 Run、消息、工具调用、固定 Release 信息及 OTel traceId。
4. 执行确定性规则；需要语义判断时调用已配置 Judge Model。
5. 每个 Evaluator 生成独立 Score；Score 记录 source、scope、definition version、reason/evidence。
6. OPS Web 可查看 Run 轨迹及评分；人工复核结果可生成 HUMAN 来源 Score。
7. 经人工确认或满足条件的线上失败样本可复制/转化为 Dataset Case。

离线：

1. 用户从评测集页面选择 Dataset、精确 Agent Release、评估器集合和并发/预算参数。
2. Control 创建 Evaluation Run 和待执行 Item。
3. Worker 为 Case 创建标记为 EVALUATION 的 Conversation/Run，通过现有 Run Scheduler 执行 Agent。
4. 收集最终结果和 Trace，逐项执行确定性和 Judge 评估。
5. Evaluation Run 结束后生成汇总：通过率、每指标均值、失败数、P50/P95 延迟、Tokens、估算成本。
6. 用户按同一 Dataset、同一 Evaluator Version 比较不同 Release 的结果，并查看变差的单项 Trace。
7. 发布流程可人工或 CI 查询实验结论；第一期不自动阻断发布，后续再加发布 Gate。

## 6. 核心数据模型

所有时间使用 timestamptz；ID 使用 UUID；结构化输入配置和证据使用 JSONB；枚举使用受控 VARCHAR。SQL 统一写入 ai 模块对应 MyBatis XML，不在 Mapper 注解、Service 或 Controller 中拼 SQL。

### 6.1 ai_eval_dataset

评测集元数据。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | 数据集 ID |
| dataset_key | varchar(100) | 用户可读稳定标识；同一 owner 唯一 |
| name | varchar(200) | 展示名称 |
| description | text | 目标、适用场景、维护说明 |
| status | varchar(24) | DRAFT / ACTIVE / ARCHIVED |
| version | bigint | 乐观锁版本 |
| owner_id | varchar(200) | 创建人 |
| created_at / updated_at | timestamptz | 创建/更新时间 |

唯一键：owner_id + dataset_key。列表索引：owner_id + status + updated_at desc。

### 6.2 ai_eval_case

数据集用例。评测集编辑采用不可变版本快照或 Dataset Revision，实验启动时固定 case 快照，避免执行中修改影响结果。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | Case ID |
| dataset_id | uuid FK 逻辑关联 | 所属数据集 |
| case_key | varchar(120) | 集内唯一标识 |
| input | jsonb | 用户输入、对话初始上下文及业务前置条件 |
| expected_output | jsonb nullable | 预期回答/语义要求/参考答案 |
| assertions | jsonb | 确定性断言定义，如包含指定协议字段/工具调用 |
| tags | jsonb | 协议类型、风险等级、场景标签 |
| weight | numeric(8,4) | 汇总统计权重，缺省 1 |
| enabled | boolean | 是否纳入新实验 |
| revision | bigint | 用例修订号 |
| created_at / updated_at | timestamptz | 时间 |

约束：dataset_id + case_key 唯一；input 必须非空 JSON 对象；用例一次不超过配置上限。用例快照复制到 Evaluation Item，实验运行期间以快照为准。

### 6.3 ai_eval_definition

评估器定义版本。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | Evaluator ID |
| evaluator_key | varchar(100) | 稳定名称 |
| name | varchar(200) | 展示名称 |
| scope | varchar(24) | RUN / TRACE / SPAN |
| evaluator_type | varchar(24) | RULE / LLM_JUDGE / HUMAN |
| definition_version | bigint | 单调递增版本 |
| config | jsonb | 有 schema 校验的配置/提示词/规则参数 |
| score_type | varchar(24) | BOOLEAN / NUMERIC / CATEGORICAL |
| enabled | boolean | 是否允许新评估任务使用 |
| created_by / created_at |  | 审计信息 |

配置更新创建新版本，已有 Score 永远绑定原 definition_version。禁止存储可执行脚本或动态 Java 类名；规则通过受控 Evaluator SPI 注册。

### 6.4 ai_eval_run

一次线上评估批次或离线实验。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | Eval Run ID |
| mode | varchar(24) | ONLINE / DATASET |
| dataset_id / dataset_revision | nullable | 离线数据集及固定修订 |
| agent_key / agent_version |  | 被测 Agent |
| agent_release_id | uuid | 精确 Release |
| manifest_digest | varchar(128) | 固定发布内容摘要 |
| evaluator_snapshot | jsonb | 本批评估器 ID + 版本快照 |
| status | varchar(24) | QUEUED / RUNNING / COMPLETED / FAILED / CANCEL_REQUESTED / CANCELLED |
| requested_by | varchar(200) | 发起人或线上系统身份 |
| total_items / completed_items / failed_items | integer | 进度计数 |
| config | jsonb | 预算、超时、并发、采样等快照 |
| summary | jsonb | 完成后聚合统计 |
| started_at / finished_at | timestamptz | 运行时间 |
| created_at / updated_at | timestamptz | 时间 |
| error_code / error_message |  | 失败摘要 |

约束：状态通过受控迁移；线上同一 Run + 同一 evaluator version 幂等；离线并发、Judge 调用数和总预算有明确上限。

### 6.5 ai_eval_result

Eval Run 下的单个 Case/线上 Run 结果。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | 结果 ID |
| eval_run_id | uuid | 所属评估批次 |
| case_id / case_revision | nullable | 离线 Case 快照来源 |
| conversation_id | uuid nullable | 执行会话 |
| run_id | uuid nullable | 项目 AI Run |
| trace_id | varchar(64) nullable | OTel Trace ID |
| root_span_id | varchar(32) nullable | Trace 根 Span |
| final_status | varchar(24) | Agent Run 终态 |
| input_snapshot / expected_snapshot | jsonb nullable | 必要的评估上下文快照 |
| output_snapshot | jsonb nullable | 最终回答快照 |
| duration_ms | bigint nullable | 端到端时间 |
| input_tokens / output_tokens | bigint nullable | Token 聚合 |
| estimated_cost | numeric(18,8) nullable | 可配置价格表估算 |
| status | varchar(24) | QUEUED / EXECUTING / EVALUATING / COMPLETED / FAILED / SKIPPED |
| error_code / error_message |  | 稳定错误信息 |
| created_at / updated_at | timestamptz | 时间 |

唯一约束：离线 eval_run_id + case_id；线上 run_id + evaluator snapshot digest。索引：eval_run_id + status、run_id、trace_id、case_id。

### 6.6 ai_eval_score

一条评估分数或人工反馈。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | uuid PK | Score ID |
| result_id | uuid | 结果 ID |
| evaluator_id / definition_version | nullable | 评分来源定义 |
| scope | varchar(24) | RUN / TRACE / SPAN |
| trace_id / span_id | nullable | Trace/Span 定位 |
| metric_key | varchar(100) | 指标稳定标识 |
| score_type | varchar(24) | BOOLEAN / NUMERIC / CATEGORICAL / TEXT |
| score_number | numeric(12,6) nullable | 数值评分 |
| score_boolean | boolean nullable | 布尔评分 |
| score_label | varchar(200) nullable | 类别评分 |
| score_text | text nullable | 文本判定 |
| reason | text nullable | 自然语言理由 |
| evidence | jsonb nullable | 证据字段/规则命中详情 |
| source | varchar(24) | RULE / LLM_JUDGE / HUMAN / USER_FEEDBACK |
| status | varchar(24) | ACTIVE / SUPERSEDED |
| reviewer_id | varchar(200) nullable | 人工复核用户 |
| created_at | timestamptz | 时间 |

同一结果、scope、span、metric、evaluator version 只能有一个 ACTIVE 自动分数。人工修订采用新增行并将旧行置为 SUPERSEDED，不覆盖原始评估记录。

### 6.7 Run 与 OTel 关联

- Run 已有 traceparent/trace context 字段时复用，不重复存储第二套 trace 表。
- 如果 Run 只有 traceparent 而没有 root traceId，则在 V13 migration 中新增 trace_id 和 trace_started_at，并在创建执行上下文时持久化。
- ai_run 增加 source（USER / EVALUATION）及 eval_result_id 可选列，或以 ai_eval_result.run_id 单向关联；优先采用后者减少 Run 核心域变更。
- 事件/工具调用已有关联 run_id；必要时补齐工具 span_id 与 trace_id 字段，但避免复制大块原始内容到 PostgreSQL。
- 会话列表仅展示 USER 来源会话；EVALUATION 会话由评估 UI 通过 eval_result 访问。

## 7. Trace 设计与完整内容采集

### 7.1 必须关联的属性

在根 Agent Run Span 与关键子 Span 上记录：

- ipe.run.id、ipe.conversation.id、ipe.eval.mode、ipe.eval.result_id（评估场景）
- ipe.agent.key、ipe.agent.version、ipe.agent.release_id、ipe.agent.manifest_digest
- ipe.skill.keys / version、ipe.mcp.keys / version（按实际装载内容）
- gen_ai.operation.name、gen_ai.provider.name、gen_ai.request.model
- gen_ai.usage.input_tokens、gen_ai.usage.output_tokens
- tool.name、tool.call.id、tool.call.arguments、tool.call.result、tool.execution.state
- span.status、error.type、错误消息和重试计数
- 运行时间、超时、取消、权限拒绝结果

业务 ID 作为 Span 属性可以用于 trace 查询；不得把高基数 Run/Trace/设备 ID 放入 Prometheus Metric 标签。

### 7.2 内容采集规则

按用户明确要求，Agent Trace 内容策略为完整原文：

- 始终采集 user/system/developer/tool 消息正文和 Agent 输出正文。
- 始终采集工具 Schema、工具名称、完整参数和完整结果。
- 不进行内容脱敏、凭据替换、关键字过滤或字符截断。
- 取消 ContentFilteringTracer 的 sanitize 和 maxContentChars 限制；AgentScope TelemetryTracer 使用原生 OpenTelemetry Tracer。
- 修改 Collector Trace pipeline，移除会删除或重写 AI GenAI 内容属性的 redaction/filter/transform 规则；Trace 属性完整送达 Tempo。
- 保留 OTel 标准结构，优先使用 GenAI 语义约定属性；项目自定义业务属性使用 ipe.* 前缀。
- 对内容型 Span Attribute/Events 使用结构化字符串/JSON，避免将同一消息重复写入多个字段。
- Metrics 和 Logs 不因本需求附带完整对话/工具正文；全量正文范围限定为 AI Trace。

### 7.3 Trace 展示与查询

- AI Control 新增 Tempo Query Client，使用配置项 IPE_TEMPO_QUERY_BASE_URL 查询指定 trace_id。
- 后端通过项目 API 鉴权后代理 Trace，浏览器不直接访问内部 Tempo 服务。
- Tempo Trace 查询失败或已过保留期时，评分和 Run 摘要仍可查看；Trace 面板显示明确不可用状态。
- 展示 Span 树、父子关系、时间线、状态、输入输出、工具参数/返回、模型及 Token。
- Span 详情中展示直接关联的 Score；Run 详情展示聚合分数和各指标分数。
- 线上 Eval 结果保留 trace_id、run_id 和评分证据；原始完整 Trace 由 Tempo 按现有 Trace 保留策略保存。

## 8. 评分体系

### 8.1 指标分类

| 类别 | 指标例子 | 推荐方法 | 作用域 |
|---|---|---|---|
| 任务结果 | task_completion、final_state_correct | 工具结果/业务状态断言；必要时 Judge | RUN |
| 答案质量 | answer_relevance、protocol_correctness、groundedness | Judge + 人工抽样 | RUN/关键 LLM Span |
| 工具使用 | tool_selection_correct、tool_argument_valid、tool_result_used | JSON Schema/策略/事件规则；复杂语义用 Judge | SPAN/TRACE |
| 过程效率 | unnecessary_calls、retry_count、trajectory_efficiency | 确定性规则 + Judge | TRACE |
| 安全合规 | permission_compliant、unauthorized_call、secret_exposure | 权限决策和审计事实做代码判定 | SPAN/TRACE |
| 成本性能 | latency_ms、tokens、estimated_cost | OTel/Run 事实聚合 | RUN/TRACE |

### 8.2 评分原则

1. 先记录多维原始分数，再计算总体分；总体分不能掩盖安全失败。
2. 安全违规、未授权写操作、关键业务状态错误为硬失败，不以其他高分抵消。
3. 通过率、平均分、加权分数、置信度、样本数分别展示。
4. Judge 输出结构化 JSON：score、label、reason、evidence；格式校验失败可重试一次，之后记为 evaluator failure。
5. Judge 不得参与同一 Agent Run 的工具执行，只读取已完成 Trace。
6. Judge Model 使用单独配置；价格、token、延迟计入 Evaluation Run 成本。
7. Judge Prompt 和算法每次修改产生新 definition_version；不同版本结果不混合做直接趋势对比。
8. 对低置信度、规则冲突、Judge disagreement 的结果进入人工复核队列。

### 8.3 第一批内置评估器

- task_completion：Agent Run 已成功结束且输出非空；复杂场景以 Case assertions 判定。
- tool_arguments_valid：工具调用参数通过冻结的 MCP JSON Schema。
- tool_authorized：每次调用均有通过 ToolGuard 的授权结果。
- tool_execution_success：没有工具失败，或者错误恢复后任务仍满足最终断言。
- answer_relevance：Judge 对齐用户请求与最终回答。
- protocol_correctness：Judge 对协议字段、交互顺序、错误码和参考知识进行核验。
- groundedness：回答事实须有 Trace 中检索/工具证据支持。
- trajectory_efficiency：重复调用、无效调用、明显偏离任务步骤分别评分。
- token_usage、duration_ms、estimated_cost：确定性聚合指标。
- human_quality：OPS Web 人工复核量表。

内置规则必须显式声明输入字段要求；数据不足时返回 NOT_EVALUATED/缺失原因，不伪造 0 分。

## 9. 执行与并发设计

### 9.1 Worker

在 AI Control 中增加 EvaluationScheduler/Worker，复用项目生命周期管理和线程池规范：

- 队列事实保存在 ai_eval_run/ai_eval_result。
- Worker 按数据库状态领取任务；通过原子 UPDATE/RETURNING 或行级锁实现并发 Claim。
- 使用独立有界线程池，配置最大线程数、队列容量、线程名称、拒绝策略和 shutdown 行为。
- 默认单个 Evaluation Run 顺序执行 Case，允许不同 Eval Run 有界并行；避免压垮模型 Provider 和 MCP 服务。
- 每个 Case 使用当前选择的不可变 Agent Release，并走现有 Run Scheduler。
- Worker 心跳、超时、取消与重启恢复有持久状态；重启后可继续未完成 Case，依赖幂等键防止重复 Run。
- 用户取消 Eval Run 时停止创建新 Case；当前 Agent Run 尝试协作取消；已发出的外部工具调用按现有取消语义处理。
- 单批设置最大用例数、最大总耗时、最大 Judge 预算和最大工具调用预算。

### 9.2 状态机

Evaluation Run：

~~~text
QUEUED -> RUNNING -> COMPLETED
                  -> FAILED
                  -> CANCEL_REQUESTED -> CANCELLED
                  -> CANCEL_REQUESTED -> COMPLETED
~~~

Evaluation Result：

~~~text
QUEUED -> EXECUTING -> EVALUATING -> COMPLETED
                 \-> FAILED          \-> FAILED
                 \-> SKIPPED
~~~

每次状态切换需验证允许的前置状态，记录 started_at/finished_at；批处理更新必须在 Mapper XML 中显式维护。

## 10. HTTP API 设计

沿用 /api/ai/v1、现有 session cookie、X-AI-Client 和 Spring Security 权限。AI 管理员仅管理 Dataset/Evaluator/Experiment；普通授权用户可提交人工反馈；评估详情按所属项目/Agent 使用权限控制。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /evaluations/overview | 总体趋势、线上评分、待复核数、最近实验 |
| GET/POST | /evaluations/datasets | 分页查询/创建数据集 |
| GET/PUT/DELETE | /evaluations/datasets/{id} | 查询/修改草稿/归档 |
| GET/POST | /evaluations/datasets/{id}/cases | 用例分页/新增 |
| PUT/DELETE | /evaluations/datasets/{id}/cases/{caseId} | 修改/删除草稿用例 |
| POST | /evaluations/datasets/{id}:archive | 归档数据集 |
| GET | /evaluations/evaluators | 查询内置及自定义评估器版本 |
| POST | /evaluations/experiments | 固定 Dataset、Release 和 Evaluator 快照并启动 |
| GET | /evaluations/runs | 查询线上评估任务与离线实验 |
| GET | /evaluations/runs/{id} | 查询批次、汇总和 Result |
| POST | /evaluations/runs/{id}:cancel | 请求取消实验 |
| GET | /evaluations/results/{id} | Result、分数和 Run/Trace 关联 |
| GET | /evaluations/results/{id}/trace | 从 Tempo 查询并返回 Trace 树 |
| POST | /evaluations/results/{id}/scores | 新增人工复核 Score |
| POST | /evaluations/runs/{id}:retry-failed | 创建仅重试失败 Case 的新批次 |
| POST | /evaluations/results/{id}:add-to-dataset | 将失败/代表性结果复制为新 Dataset Case |

接口契约要求：

- 创建操作接受 Idempotency-Key 或客户端生成请求键。
- 分页参数及筛选项使用项目既有约定；排序字段必须白名单。
- 返回稳定错误码、traceId 和 Problem Detail。
- 输入 JSON Schema、数量、字符串长度、并发、预算均验证上限。
- API 返回 Trace ID 和 Grafana 链接（配置后），但完整 Trace 通过鉴权 API 加载。
- 所有 Controller 参数和返回对象使用 Swagger/OpenAPI 注解，描述状态、作用域和限制。

## 11. OPS Web 页面设计

新增 AI 管理子路由 /ai-admin/evaluations，沿用 AiAdmin 页面布局、Ant Design 组件、React Query、统一 API client 和权限检查。

页面结构：

1. 总览：线上 Run 数、已评估数、成功率、均分、P95 延迟、Token/成本、待人工复核数；支持时间范围和 Agent 筛选。
2. 评测集：名称、说明、版本、Case 数、更新时间、状态；详情编辑 Case 输入、期望结果、断言、标签和权重。
3. 评估器：内置指标说明、作用域、评分类型、版本；展示 Judge Prompt 和模型 profile，但密钥不回显。
4. 实验列表：状态、数据集版本、Agent Release/manifest digest、评估器版本、总体指标、耗时、操作人。
5. 实验详情：各指标 aggregate、逐 Case 结果、失败类别、与基准实验对比。
6. Trace 详情：可展开 Span 树，展示正文/工具输入输出；每个 Span 的 Score 与理由；跳转 Grafana Tempo。
7. 人工复核：原评分、判据、Trace 证据、复核结果、评论；人工 Score 新增版本记录，不覆盖机器评分。
8. 线上记录：生产 Run 按评估分数、失败、Judge disagreement、成本、时延过滤；可创建 Case。

UI 操作约束：

- 启动长实验需展示 Release、Dataset 版本、Case 数和预计 Judge 成本，再由管理员点击启动。
- Case 执行进度使用轮询 API；不得通过浏览器持有后台长连接来承担执行。
- 失败项显式展示未评估与评估失败，不将其显示为零分。
- 页面 API 与现有 AI 管理请求入口保持隔离，不直接访问 PostgreSQL、Tempo 或 Runtime 内部接口。

### 11.1 空会话新建规则

Assistant 页面的“新建”逻辑：

1. 页面当前选中会话，且该会话从未有用户聊天请求/Run 时，点击“新建”不调用 POST /conversations，不新增空会话、不切换当前会话；提示“当前会话尚未发起请求”。
2. 没有当前会话时，首次点击“新建”仍可创建一个会话。
3. 当前会话存在任一聊天请求/Run（包括失败、取消、排队和完成）时，点击“新建”按现有流程创建新会话。
4. 判定以服务端持久化记录为准，不以当前页面 messages 数组或浏览器本地状态为唯一依据。
5. AI Control 提供 hasRequests/hasRuns 字段，或在详情返回字段中提供 hasSubmittedRequest。判断基于 ai_run / ai_message 中 role=USER 的持久记录。
6. 快速连续点击由按钮 pending 状态加 API 幂等键保护，不能产生重复空会话。
7. Agent 不同版本仍保持会话绑定不可变 Release；本需求不允许复用空会话时静默改变它的 Agent Release。

## 12. 数据访问、事务与代码分层

### 12.1 Java 结构建议

在 iot-protocol-expert-ai 下增加：

~~~text
evaluation/
  api/              EvaluationController、请求响应 DTO
  domain/           Dataset、Case、Evaluator、EvalRun、Result、Score records
  service/          DatasetService、EvaluationService、EvaluatorService、ScoreService
  worker/           EvaluationScheduler、EvaluationWorker、EvaluationLease
  evaluator/        Evaluator SPI、RuleEvaluator、JudgeEvaluator
  trace/            TempoTraceClient、Trace DTO/Mapper
  mapper/           DatasetMapper、EvaluationMapper、ScoreMapper
~~~

根据既有模块规范允许合并小型 DTO/Record 文件；Controller 不写业务规则，Service 不写 SQL。Run 执行通过既有 ConversationService/RunService/RunScheduler API，Evaluator 不直接调用底层 AgentScope Runtime。

### 12.2 MyBatis XML 规则

- 每个 Mapper 接口对应 resources/mapper/ai/Evaluation*.xml。
- SELECT 明确列出字段，不使用 SELECT *。
- 动态排序使用固定白名单；分页 limit/offset 通过参数绑定。
- 所有写语句、状态迁移、批量 Score 插入、聚合 SQL 均放 XML。
- PostgreSQL JSONB 采用项目现有 JSON TypeHandler 或显式 cast；UUID 使用项目 UUID TypeHandler。
- 事务边界放 Service；Dataset Case 快照和 EvalRun 创建在同一事务内。
- 任务领取使用数据库原子语句/行锁；不可在 Java 层先读后无锁更新。
- 禁止新增物理外键；逻辑关联通过 Service 校验和删除保护处理。
- 新数据库结构只通过新 Flyway migration，例如 V13__agent_evaluation.sql；已发布版本不得改写。

### 12.3 阿里巴巴规范

- Java 21 语言特性与项目既有编码风格一致；类名、方法、常量、异常及日志字段遵循项目和 Alibaba Java Coding Guidelines。
- 并发线程池使用显式容量、命名线程工厂、拒绝策略和关闭钩子。
- 禁止吞异常、宽泛 catch 后返回成功；Evaluator 错误单独记录并让批次按策略继续或失败。
- 金额与 Token 价格计算使用 BigDecimal；非金额耗时和数量使用 long。
- 状态使用受控枚举和显式数据库值，不用 ordinal。
- POM 保持现有 Boot/MyBatis/AgentScope 版本；新依赖必须先确认兼容 Java 21 与 Boot 4。
- 配置项通过 @ConfigurationProperties；秘密从现有 credential service 取，不写日志。
- 日志记录 evalRunId、resultId、runId、traceId、evaluatorKey，不输出凭据和大段内容。

## 13. 配置项建议

| 配置 | 默认值建议 | 说明 |
|---|---|---|
| ipe.ai.evaluation.enabled | true | 是否启用评估执行 |
| ipe.ai.evaluation.online.enabled | true | 是否对线上 Run 触发评估 |
| ipe.ai.evaluation.online.sample-rate | 1.0 | 线上采样比例 |
| ipe.ai.evaluation.worker.threads | 2 | 有界 Worker 线程 |
| ipe.ai.evaluation.worker.queue-capacity | 100 | 本地执行队列容量，持久队列仍以 DB 为准 |
| ipe.ai.evaluation.max-cases-per-run | 500 | 单实验 Case 上限 |
| ipe.ai.evaluation.max-concurrency | 2 | 单实例同时运行的实验批次 |
| ipe.ai.evaluation.max-duration | 2h | 单实验最长时间 |
| ipe.ai.evaluation.max-judge-cost | 按项目值配置 | Judge 总预算上限 |
| ipe.ai.evaluation.judge-model-profile | 空 | Judge Model Profile；为空时语义评分标记未配置 |
| ipe.tempo.query-base-url | 空 | Tempo HTTP 查询地址；未设置时 Trace 面板显示不可用 |
| ipe.ai.otel.content-enabled | true | 按需求默认采集完整 AI Trace 内容 |
| ipe.ai.otel.max-content-chars | 0 / unlimited | 按需求禁用内容截断；实现需避免将其误解释为默认长度 |

最终配置键命名以现有 RuntimeProperties/AiProperties 结构为准，不复制已有 IPE_OTLP_ENDPOINT 等配置。

## 14. 实施阶段

### 阶段一：设计与数据契约

- 固化指标定义、分值范围、Dataset/Case schema、Score 作用域和 Judge 输出 JSON schema。
- 检查 OTel GenAI 属性版本和 AgentScope 2.0.4 输出字段，记录兼容性事实。
- 确定 Tempo 查询地址与鉴权；确认 AI Trace 内容字段的 Collector 处理链路。
- 不升级技术栈。

### 阶段二：会话与 Trace 完整性

- “新建会话”空会话保护。
- 完整消息、工具参数和结果 Trace 属性。
- 业务 Run/Release/Conversation ID 与 OTel Trace 关联。
- 只将 AI Trace 内容清洗规则关闭；不要把业务数据复制到 Metric labels。

### 阶段三：线上评分

- 新增 Evaluator Definition 版本、Score 和 Evaluation Result 表。
- Run 终态事件触发线上评估。
- 先实现确定性指标与分数 API。
- 增加 Judge Model Profile 调用及失败重试、预算和审计。
- 通过 Tempo Trace 查询接口展示链路。

### 阶段四：数据集与离线实验

- Dataset/Case CRUD 和固定快照。
- Evaluation Worker 通过现有 Run Scheduler 执行 Case。
- 展示实验进度、逐 Case Score、模型/Release 差异。
- 支持从线上 Trace 生成 Dataset Case。

### 阶段五：OPS Web

- 新增 Evaluation 页面、API types/client、路由及 AI Admin 菜单。
- 总览、数据集、评估器、实验、Trace 详情、人工复核。
- 补齐 loading/empty/error/permission/long-running 状态。
- 复用已有布局与组件风格，不复制另一套前端。

每阶段实施时复核实际 Langfuse/Opik 源码与项目代码状态；参考项目仅提供设计样式，不直接复制其数据库和服务实现。

## 15. 验收标准

### 15.1 会话

- 当前空会话点击新建不会新增会话记录。
- 已发起过任意聊天请求的会话点击新建会创建会话。
- 页面重新加载后仍由服务端数据正确判断。
- 连续快速点击不会创建多个重复会话。

### 15.2 Trace

- 同一次 Run 中 HTTP/Run execute/Agent invoke/chat/tool spans trace_id 连续且父子关系正确。
- Trace 可查看完整消息正文、完整工具参数、完整返回结果和 Agent 元数据。
- 不发生内容脱敏、凭据替换或字符截断。
- Run、Agent Release、manifest digest、评估 Case 均可从 Trace 或 Result 互相定位。
- Tempo unavailable 不影响 Run 完成、Score 持久化和历史结果查询。

### 15.3 Evaluate

- Dataset 和 Case 可以创建、修改、归档；实验运行固定版本快照。
- 指定 Agent Release 可用 Dataset 执行离线实验。
- 每个 Result 展示运行状态、最终结果、trace_id、耗时、Token 和成本估算。
- 每个 Evaluator 产生独立 Score，并有版本、作用域、来源、判据和证据。
- 自动评估与人工评分并存；人工复核不覆盖原始机器评分。
- Judge 模型失败不会使已执行 Run 状态错乱；失败结果明确标记。
- 同一线上 Run 评估重试幂等。
- 评估任务重启恢复、取消和预算上限生效。
- UI 可按 Agent Release、Dataset、Evaluator、时间和分数过滤及对比。

### 15.4 工程约束

- Java 与 Maven 版本沿用根 POM：JDK 21；Spring Boot、MyBatis、AgentScope 不无故升级。
- 持久层全部使用 MyBatis；Mapper SQL 全在 XML 文件。
- 数据结构通过新 Flyway migration。
- Web 变更位于现有 OPS Web。
- Java 静态检查/代码规范检查遵循项目 AGENTS.md；是否运行验证命令以用户的验证要求为准。
- 设计中不引入 Langfuse/Opik 运行依赖。

## 16. 需要实施时验证的技术事实

- AgentScope Java 2.0.4 对消息、工具 Schema、参数、结果及异常事件实际导出的 GenAI 属性键。
- Micrometer Tracing 与 OTel 原生 Span 在 Runtime 的 context propagation、异步队列和 Scope 生命周期。
- 当前 Collector 配置中具体的 attributes/redaction/filter/transform processors，确认不再改写完整 AI 内容。
- Tempo query API 版本、响应格式、鉴权、最大 Trace 响应体限制和保留期限。
- LLM Judge 使用的 Provider/Model Profile 是否支持 JSON/结构化输出；Token 价格来源和价格表版本化方式。
- Run 完成状态和 ToolCall/Event 表之间的数据一致性及重试语义。
- OPS 当前菜单权限策略和用户角色对评估管理/人工复核的分权需求。


## 17. 本次代码落地说明（2026-11-16）

本节描述当前交付代码的实际行为；前文的目标指标与扩展规划不等同于已上线能力。

### 17.1 入口和部署

- OPS Web 菜单：`Agent 能力评估`，路由 `/ai-evaluation`；登录账号必须同时具备 `ai:use` 与 `ai:admin`。
- HTTP 根路径：`/api/ai/v1/evaluation`；沿用 OPS Cookie 身份校验与 `X-AI-Client` 写请求校验。服务端按当前用户检查 Dataset/Experiment/Result 所有权。
- 数据库新增 Flyway `V13__agent_evaluation.sql`，包含会话来源、数据集、用例、实验、结果及评分；未增加物理外键。
- 设置 AI 服务环境变量 `IPE_TEMPO_QUERY_BASE_URL=http://<tempo-host>:3200`。未配置或 Trace 不可查询时，结果和评分列表仍能使用，链路接口给出错误。
- 使用现有 JDK 21 / Spring Boot / AgentScope / MyBatis，无 Langfuse 或 Opik 运行时依赖。

### 17.2 实际执行状态机

1. 创建数据集，添加用例；输入必须包含长度 1～4000 的 `question`。支持参考答案、包含词断言、标签、权重、用例修改与禁用；数据集可以归档。
2. 创建实验时冻结 Agent 发布版本、manifest digest、评估器版本、Case revision、输入、参考答案、断言和权重。Judge 固定 Model Profile ID/revision/modelName，运行时若 revision 改变则记录 Judge 失败，不静默改用新配置。
3. 每个用户最多有 5 个未结束实验，单实验默认最多 200 个 Case，数据集最多 500 个 Case。当前串行执行 Case，`maxConcurrency` 仅支持 1；不创建无界线程池。
4. Worker 每 3 秒最多扫描 10 个待评分线上 Run 和 4 个离线实验。离线实验原子认领、事务持有行锁，通过租约和持久化 Result 实现进程重启恢复。
5. 用例建立来源为 `EVALUATION` 的独立会话，并经原 `RunService -> RunScheduler -> Agent Runtime` 执行；独立会话从普通历史列表排除。Run 提交与 Result 绑定在同一事务，重启不重复提交已绑定 Run。
6. Worker 不阻塞等待 Agent；下轮扫描完成 Run，记录答案、Trace ID、耗时和输入/输出 Token。执行超十分钟请求取消，Case 标记失败；取消不能撤销已发送的外部工具调用。
7. 实验取消标记 `CANCEL_REQUESTED`，Worker 取消未结束 Run，结束为 `CANCELLED`。事务内发生基础设施/权限错误时，实验记录 `FAILED`，服务端保留异常堆栈。
8. 实验完成时，分别汇总执行成功率与各指标加权均值，避免把“程序运行成功”视为“答案正确”。UI 支持选择两个实验比较汇总值。

### 17.3 已实现评分语义

- 确定性指标：任务终态、回答非空、工具成功比例、工具授权情况、Token、耗时。
- 参考答案：提供非空 `expectedOutput.answer` 时产生 `expected_answer_exact`，只表示文字一致性；不代表语义评估。
- 断言：`assertions.contains` 是答案必须包含的字符串列表；`assertions.requiredTools` 是必须成功调用的工具名列表。结果记为 `assertions_passed`。
- 可选 Judge：沿用项目已配置模型，temperature=0，最多 512 输出 Token，每次请求读取超时 30 秒；评分范围 0～1，记录标签、理由、证据。对不合法输出/模型错误明确保存 `EVALUATOR_FAILED`，不伪造分数。
- 线上 Run 默认产生确定性评分；离线实验可以选择模型额外执行 Judge。
- 人工评分支持 BOOLEAN/NUMERIC/CATEGORICAL/TEXT，指定 `spanId` 时关联 Span；后端通过该 Result 的 Tempo Trace 验证 Span 归属。更新同一人工指标时原记录标记 `SUPERSEDED`，机器评分不被覆盖。
- NUMERIC 人工质量分值为 0～1；Token 和耗时为不同量纲，不与质量分值直接相加。

### 17.4 Trace 内容与界面

- AgentScope 使用原生 OTel Tracer，不再包装内容过滤器；消息和工具参数不经过原有脱敏及字符截断。
- Runtime MCP 调用在结果给模型截断之前记录 `mcp.request` / `mcp.response` 事件，包含完整原始参数和结果；模型上下文的长度限制仍按执行配置生效。
- Runtime 增加 Run/Release 元数据和 Token 事件。异步链路沿用项目现有 Micrometer/OTel 上下文传播。
- Trace 查询由 AI 服务端代理 Tempo；Web 解析 Span 父子关系，显示属性和事件，并支持选中节点人工打分。
- 完整采集依赖实际部署的 OTel 采样、导出、Collector 与 Tempo 接收/保留配置；本次没有操作外部 Collector 或 Tempo 实例。应用配置不等同于基础设施无限容量。

### 17.5 实施边界与验收方式

- 成本字段预留；没有价格表时保持空值，不填入估算零成本。货币预算、任意自定义 Evaluator 插件、多轮仿真、分布式高并发评测、在线 Judge 采样策略、专用标注队列属于后续扩展。
- 当前界面查询前 100 个实验/数据集，结果 API 支持分页；实验指标对比基于各自数据集快照，跨不同快照不能认定是严格回归结论。
- 本次 Java 编译、前端 TypeScript 类型检查、前端生产构建、Mapper XML 语法检查和代码规范静态扫描均通过；不新增或运行测试，不向共享数据库执行迁移。数据库迁移、Runtime 实际模型调用、Tempo 联调和浏览器交互验收需要在部署环境完成。
