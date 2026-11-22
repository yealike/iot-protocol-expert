# 在线 Agent 平台实施记录

> **历史文档（2026-11-16）**：本文记录旧模块拆分和 Nacos MCP/Skill Registry 方案，已不代表当前实现。当前 Nacos 仅负责可选的进程/Agent 实例发现；MCP/Skill 登记、版本和发布由 Control 管理，REST-to-MCP 由 Higress 承担。当前架构与验收证据见 [模块与组件治理](../module-architecture.md) 和 [Agent 全流程验收报告](../agent-full-flow-report-2026-11-17.md)。

> 设计基线：`设计文档/Agent在线配置、发布与运行方案.md`
> 记录日期：2026-11-16
> 代码基线：`b38a97c`（`develop`）；工作区包含尚未提交的在线 Agent 实现、V7/V8 迁移、前端页面和本地脚本。
> 结论口径：本文区分“代码已具备”“已知端到端事实”和“待验证”。未实际验证的并发、故障注入与真实环境测试不按通过计算。

## 1. 落地架构

在线 Agent 平台拆为两个独立 Java 进程，浏览器和业务数据面仍由现有 OPS Web/OPS 服务承载。

```text
浏览器 / OPS Web
  └─ /api/ai/v1、SSE ──> AI Control :19083
                            ├─ PostgreSQL ipe_ai：配置、发布、会话、Run、事件、审计
                            ├─ Nacos Naming：Control、Runtime 基础服务、Agent/MCP/Skill 精确版本
                            │    ai_registry_projection 保存投影；MCP/Skill 由 Control 注册，Agent 由 Runtime 注册
                            └─ 签名 HTTP ──> Agent Runtime :19084
                                               ├─ 通过 IPE_CONTROL_BASE_URL 回调 Control
                                               ├─ 拉取 manifest / Skill / MCP 描述
                                               ├─ AgentScope ReAct 执行、模型调用
                                               ├─ Higress MCP ──> OPS :19082
                                               └─ 事件、心跳、终态回写 Control
```

- Control 是配置、发布、Run 状态、事件游标和聊天历史的唯一持久事实源。
- Runtime 不读写 `ipe_ai`、`ipe_ops` 或任何业务数据库，只通过签名内部接口获取执行所需内容并回写状态。
- `ai-contract` 只包含 DTO、错误码、manifest、摘要算法、Nacos 命名和 HMAC 契约，不引入 Spring 自动配置、Mapper 或数据库实体。
- 模型密钥不进入 manifest、Nacos metadata、SSE 或前端；Runtime 在执行前用 manifest 中的 `credentialRef + secretRevision` 换取短期明文。
- Control 通过 Nacos 发现 Runtime；Runtime 通过配置的 `IPE_CONTROL_BASE_URL` 回调 Control，并不通过 Nacos 反向发现 Control。

### 1.1 Nacos/Higress 前置条件

OPS 必须启用 Spring Cloud Nacos Discovery，并以 `iot-protocol-expert-ops` 注册到 Nacos。Higress 依赖该注册发现 OPS；缺失时 MCP `tools/call` 会返回 503。已知 E2E 故障根因就是 OPS 未注册，而不是 MCP 凭据错误。启动 OPS 时至少确认：

```text
IPE_NACOS_DISCOVERY_ENABLED=true
IPE_OPS_ADVERTISE_IP=<Higress 可访问的本机地址>
IPE_NACOS_SERVER_ADDR=<Nacos 地址>
IPE_NACOS_NAMESPACE=<Nacos namespace>
```

建议启动顺序：

1. 启动并确认 OPS 已注册 `iot-protocol-expert-ops`；
2. 以 `IPE_AI_BOOTSTRAP_ENABLED=false` 启动 Control，先提供 Runtime readiness 所需的 Control 可达性；
3. 启动 Runtime 并等待 readiness；
4. 重启 Control 并设置 `IPE_AI_BOOTSTRAP_ENABLED=true`，触发 MCP/Skill/Agent bootstrap。

`seed-ai.ps1` 按上述四步执行：先预启动 Control（bootstrap=false），再启动 Runtime 并等待 readiness，最后重启 Control 开启 bootstrap。它不会启动 OPS，只做健康与 Nacos 注册检查；Nacos 3.x 会先登录再调用 v3 admin API。

## 2. 代码模块与职责

| 模块/目录 | 职责 | 关键实现 |
|---|---|---|
| `iot-protocol-expert-ai-contract` | Control/Runtime 共享契约 | `AgentManifest`、`InternalPaths`、`InternalSignature`、`ComponentNames`、摘要与工具 alias |
| `iot-protocol-expert-ai` | AI Control | 管理 API、模型/凭据/MCP/Skill/Agent、发布协调、Nacos 组件注册、Run 调度、SSE、bootstrap |
| `iot-protocol-expert-agent-runtime` | Agent Runtime | prepare、MCP 连接池、Skill 工具、ReAct 执行、heartbeat/fencing、事件批处理、Nacos 注册 |
| `iot-protocol-expert-ops` | 身份与业务数据面 | `/internal/ai/identity/*`、OPS 业务工具；必须注册 Nacos 供 Higress 发现 |
| `iot-protocol-expert-web` | 管理页面 | `/ai-admin/{models,mcps,skills,agents,publications}` 与助手对话页 |
| Flyway V1–V8 | `ipe_ai` 数据模型 | V1–V6 保留原 AI 基础能力；V7 新增在线 Agent 平台表；V8 增加 publication 租约 |

Runtime 的 Maven 依赖未声明 JDBC 驱动、MyBatis、Flyway 或数据源 starter；AgentScope 自带的 `sqlite-jdbc` 已显式排除，符合“Runtime 不接触数据库”的结构边界。

当前注册边界需要特别注意：

- Control 实际注册 `ipe-ai-control`；
- Runtime 实际注册基础服务 `ipe-agent-runtime`，并在 prepare 后注册精确 Agent 版本服务 `ipe-agent--<agentKey>--v<version>`；
- Control 发布 MCP/Skill 时通过 `NacosRegistryService.registerComponent` 注册 `ipe-mcp--...` / `ipe-skill--...`，并在启动后每 30 秒执行 `reconcileRegistrations`，为缺失的 ACTIVE 版本补注册；
- `ai_registry_projection` 仍保存注册期望/观测状态，但不再是 MCP/Skill 的唯一注册结果。

## 3. 数据表

### 3.1 在线配置与发布

| 表 | 用途 |
|---|---|
| `ai_model_profile` | OpenAI 兼容模型配置，绑定凭据引用 |
| `ai_credential` / `ai_credential_secret` | 凭据元数据与 AES-256-GCM 密文、nonce、keyId、secretRevision |
| `ai_mcp_component` / `ai_mcp_release` / `ai_mcp_test_report` | MCP 草稿、不可变发布目录与测试报告 |
| `ai_skill_component` / `ai_skill_draft_file` / `ai_skill_release` | Skill 草稿文件、不可变 bundle 与版本状态 |
| `ai_agent_component` / `ai_agent_mcp_binding` / `ai_agent_skill_binding` | Agent 草稿与精确版本依赖绑定 |
| `ai_agent_release` | 编译后的不可变 manifest、digest、generation 与生命周期状态 |
| `ai_publication` | `VALIDATING → PREPARING → REGISTERING → PUBLISHED/FAILED` 发布协调与租约 |
| `ai_registry_projection` | 注册期望/观测投影；MCP/Skill 由 Control 的 `NacosRegistryService` 注册，Agent 由 Runtime 的 `NacosRuntimeRegistry` 注册 |
| `ai_outbox` | 跨 PG/HTTP/Nacos 的至少一次事件投递与重试 |
| `ai_internal_nonce` | Control 内部 HMAC 防重放 nonce |

### 3.2 会话、Run 与既有能力

| 表 | 用途 |
|---|---|
| `ai_conversation` / `ai_message` | 会话固定 Agent 版本、消息序号、引用与事件游标 |
| `ai_run` | Run 状态、租约、executionToken、runtimeInstanceId、deadline、usage 与终态 |
| `ai_run_event` / `ai_tool_call` | 持久化事件与工具调用审计；事件带 cursor/sourceSeq/eventId |
| `ai_checkpoint` / `ai_context_summary` | 可恢复元信息与上下文摘要预留 |
| `ai_permission_policy` | 角色到 Agent/Tool/站点范围的授权策略 |
| `ai_document` / `ai_chunk` / `ai_index_version` | 既有协议知识索引，不由 reset 清理 |
| `ai_audit_log` | AI 侧审计，不纳入在线 Agent reset 清理范围 |

不新增物理外键和数据库级联；跨表引用、删除保护、发布快照与绑定替换由应用事务显式处理。

已知数据缺口：

- `ai_run.compiled_plan` 列已存在，但新 Runtime 路径没有写入点；
- `ai_tool_call` 的平台审计列与写入路径已存在（`RuntimeEventIngestService` 在 `tool.started/completed/failed` 时写表），但尚未核对参数摘要、Provider 追溯和全部失败分支；
- `ai_audit_log` 尚未接入新的平台管理操作；
- 旧版 `ai_agent_definition` / `ai_agent_binding` 仅保留历史审计，新在线平台不使用；reset 脚本默认保留。

## 4. API

### 4.1 浏览器管理 API

统一前缀 `/api/ai/v1`，管理接口要求 `ai:admin`，写请求还要求 `X-AI-Client` 头。

| 资源 | 主要接口 |
|---|---|
| 模型 | `GET/POST /models`、`PUT /models/{id}`、`POST /models/{id}/test` |
| 凭据 | `GET/POST /credentials`、`PUT /credentials/{id}`（轮换，不回显明文） |
| MCP | `GET/POST /mcps`、`PUT/DELETE /mcps/{id}`、`POST /mcps/{id}/test`、`GET /mcps/{id}/tools`、`GET /mcps/{id}/releases`、`POST /mcps/{id}/publish` |
| Skill | `GET/POST /skills`、`PUT/DELETE /skills/{id}`、文件读写删除、`POST /skills/{id}/validate`、`GET /skills/{id}/releases`、`POST /skills/{id}/publish` |
| Agent | `GET/POST /agents`、`GET/PUT/DELETE /agents/{id}`、`POST /agents/{id}/validate`、`POST /agents/{id}/publish`、`POST /agents/{id}/rollback` |
| 发布/注册 | `GET /publications/{id}`、`POST /publications/{id}/retry`、`GET /registry/components`、`GET /registry/components/{type}/{key}/versions/{version}` |

当前未实现设计稿中的草稿预览 Run、组件 disable/revoke、发布列表分页和完整 OpenAPI/JSON Schema 导出；不得把现有接口说明为已具备这些能力。错误体当前是 `{error,message}`，不是设计要求的 ProblemDetail + `code,traceId`；Skill 路径非法在保存时返回 400，而设计/T14 期望 422。

### 4.2 对话 API

| 方法/路径 | 说明 |
|---|---|
| `GET /agent-catalog` | 当前用户可用且已发布的 Agent |
| `POST /conversations` | `{agentKey, version?, title?}`，无版本时锁定默认 ACTIVE 版本 |
| `GET /conversations`、`GET /conversations/{id}` | 会话分页与快照 |
| `POST /conversations/{id}/runs` | `{content/question, idempotencyKey, priority?}`，返回 202 |
| `POST /runs/{id}/cancel` | Run 取消 |
| `GET /conversations/{id}/events?afterCursor=N` | SSE，按持久化 cursor 重放和续传 |

### 4.3 内部接口

Control 提供：

```text
GET  /internal/ai/v1/releases/{releaseId}/manifest
GET  /internal/ai/v1/mcps/{key}/versions/{version}/descriptor
GET  /internal/ai/v1/skills/{key}/versions/{version}
GET  /internal/ai/v1/skills/{key}/versions/{version}/file?path=...
POST /internal/ai/v1/runs/{runId}:claim
POST /internal/ai/v1/runs/{runId}:heartbeat
GET  /internal/ai/v1/runs/{runId}/context
POST /internal/ai/v1/runs/{runId}/events:batch
POST /internal/ai/v1/runs/{runId}:complete
POST /internal/ai/v1/credentials:resolve
POST /internal/ai/v1/runs/{runId}/tools:authorize
GET  /internal/ai/v1/runtime/desired-releases
GET  /internal/ai/v1/runtime/readiness
```

Runtime 提供：

```text
POST /internal/agent/v1/releases:prepare
POST /internal/agent/v1/agents/{agentKey}/versions/{version}/runs
GET  /internal/agent/v1/runs/{runId}?executionToken=...
POST /internal/agent/v1/runs/{runId}:cancel
GET  /actuator/health
GET  /actuator/health/readiness
```

内部请求使用服务身份 HMAC-SHA256，签名覆盖 method、path+query、body 摘要、timestamp、nonce 与 audience。Control 校验 30 秒时间窗并将 nonce 写入 `ai_internal_nonce`；Runtime 使用内存 TTL nonce 缓存。内部 HTTP 携带敏感内容时必须走 HTTPS 或受控加密链路。

## 5. 配置、发布与运行流程

### 5.1 配置与发布

1. 管理员创建模型配置和凭据。凭据密文写入 `ai_credential_secret`，接口只返回 `masked/keyId/secretRevision`。
2. 创建 MCP 草稿，测试时执行 `initialize → tools/list`，保存带配置摘要的测试报告；发布时冻结工具目录、Schema 摘要和 endpoint。
3. 创建 Skill 草稿文件，校验 `SKILL.md`、相对路径、文件数量/大小和 YAML；发布时生成不可变 bundle 与 digest。
4. Agent 保存使用 `expectedRevision` 乐观锁；编译时锁定模型、Skill/MCP 精确版本、工具白名单、alias 和摘要，生成 manifest 与 manifestDigest。
5. 发布事务写入 STAGED release、publication 和 outbox，向浏览器返回 202 + publicationId。
6. PublicationCoordinator 按 `VALIDATING → PREPARING → REGISTERING` 推进：
   - Agent：选择健康 Runtime，调用 `releases:prepare`，Runtime 拉取并校验 manifest、MCP、Skill，成功后注册精确版本服务；
   - MCP/Skill：冻结目录或 bundle 后写入 `ai_registry_projection`，并由 Control 调用 `NacosRegistryService.registerComponent` 注册 `ipe-mcp--...` / `ipe-skill--...`；每 30 秒对账并补注册缺失的 ACTIVE 版本；
   - Agent 路径在确认 Nacos 健康实例后切 ACTIVE；MCP/Skill 路径注册组件并写入投影后标记 PUBLISHED。
7. Runtime 重启后通过 `desired-releases` 重新 prepare ACTIVE 版本并补注册。

### 5.2 Run

1. 浏览器提交消息和 `idempotencyKey`；Control 在同一事务写用户消息与 QUEUED Run，并保存 `agentReleaseId + manifestDigest`。
2. RunScheduler 用条件更新认领 `QUEUED → DISPATCHING`，生成严格递增 `executionToken`，按 Nacos 发现 Runtime；默认禁止直连回退，Nacos 无健康实例时不会派发。仅当显式设置 `IPE_RUNTIME_DIRECT_FALLBACK_ENABLED=true` 时，才允许本地调试使用 `IPE_AGENT_RUNTIME_BASE_URL`。
3. Runtime 先调用 `:claim` 确认 token/owner，再返回 202。重复派发同 token 返回已有 receipt，不创建第二个执行。
4. Runtime 每 5 秒 heartbeat；Control 延长租约。上下文、工具授权和事件写入均校验 `(runId, executionToken, instanceId, authVersion)`。
5. Runtime 将模型/工具/技能事件按 `sourceSeq` 批量回写；Control 用 `(run_id, execution_token, source_seq)` 与 `event_id` 去重，提交后发布给 SSE。
6. 终态提交在同一事务更新 Run、保存 assistant 消息与 terminal event；已终结 Run 的重复 complete 不再写入。

## 6. 幂等、安全与失败处理

### 6.1 幂等与一致性

- 草稿更新、发布和回滚使用 `expectedRevision`；并发写冲突返回 409 `REVISION_CONFLICT`。
- 发布使用 `(resourceType, resourceId, idempotencyKey)` 唯一约束；重复请求返回原 release/publication。
- `ai_agent_release(agent_id, version)` 不允许覆盖已发布内容；manifest、Skill bundle、MCP 工具目录均计算稳定 SHA-256。
- `ai_outbox` 至少一次投递；publication 用 V8 租约避免多个 Control 同时推进同一任务，失败按 1/2/4/8/16 秒指数退避，最多 5 次。
- prepare 以 `(releaseId, manifestDigest, generation)` 幂等；不一致返回 `REVISION_CONFLICT`。
- Runtime dispatch 以 `(runId, executionToken)` 幂等；`executionToken` 每次重新认领严格递增，旧实例写入被拒绝。
- 事件批次最多 32 条/64 KiB；未 ACK 队列上限 256 条/1 MiB，超过后暂停模型流，持续 10 秒不可恢复则终止本地执行。

### 6.2 安全边界与已知缺口

- 身份只来自 OPS；Control 不创建第二套账号。管理 API 使用 `ai:admin`，普通对话使用 `ai:use`。
- 写请求要求 `X-AI-Client`，配合不开放 CORS 阻断普通表单 CSRF。
- 内部 HMAC 带时间窗、nonce 和 audience；内网不被视为免鉴权。Runtime nonce 存在进程内，不跨实例共享；Control 使用数据库 nonce。
- 凭据使用 AES-256-GCM，随机 nonce，AAD 为 `credentialId:secretRevision`；主密钥只从环境变量或挂载文件读取。
- 工具授权会在 Runtime 调用前请求 Control，校验 manifest alias、Run 创建时保存的 `actorAuthVersion`、角色策略和站点范围；模型和外部内容不能扩大权限。
- **T13 已补强**：`authorize` 会校验 manifest alias、Agent release 与 MCP release 的 `REVOKED` 状态、请求 authVersion 与 Run 保存值；并在每次工具调用前重新向 OPS 解析用户身份与最新 authVersion，不一致时返回 `AUTH_VERSION_CHANGED`。组件 disable/revoke API 仍未暴露，权限变更故障注入待补。
- **T06 直连回退已修正**：`RuntimeDispatchClient` 仅在 `IPE_RUNTIME_DIRECT_FALLBACK_ENABLED=true` 时才使用 `IPE_AGENT_RUNTIME_BASE_URL`，默认 false；Nacos 无健康实例时不再静默直连。Nacos 暂停超过 60 秒的缓存/503 语义与故障注入仍需验证。
- `OpsIdentityClient` 的 Spring 配置默认地址已修正为 `http://127.0.0.1:19082`；部署到其他主机或端口时必须显式设置 `IPE_OPS_BASE_URL`。
- Skill 路径拒绝绝对路径、`.`/`..`、反斜杠、盘符和控制字符，并检查大小写折叠冲突、文件数、单文件/包大小；但保存时非法路径返回 400 而不是设计要求的 422。
- MCP/模型 URI 校验协议、host、userinfo 和 fragment；完整的出网地址白名单、私网/回环阻断与 DNS 重绑定防护仍需在部署层补强。
- Control 的 `/actuator/**` 与 `/internal/ai/**` 在 Spring Security 中 `permitAll`，内部接口依赖 HMAC 过滤器；Runtime actuator 无认证且默认显示详情，生产必须限制网络访问。

### 6.3 失败处理

- 无健康 Runtime：Agent 发布保持失败/重试，不切 PUBLISHED；Run 在默认配置下不会回退直连，应返回/记录 `NO_HEALTHY_RUNTIME`。该失败路径仍需故障注入验证。
- Runtime 429：Control 释放认领并按退避重试，超过最大派发次数失败；不会泄露 inflight 配额。
- Runtime 失联：Control 在租约过期后标记 `EXECUTION_LOST`，不自动重演整轮。
- 取消：Control 先进入 `CANCEL_REQUESTED` 并通知 owner；Runtime 释放模型订阅、Future 和连接引用；超时由 Control 收敛终态。
- required MCP 不可用：prepare 失败；optional MCP 不可用：发出 warning 并整体移除该 MCP 的工具，避免残留部分工具。
- Schema/摘要漂移、Skill digest 不一致、manifest 不一致：拒绝 prepare，不进入执行。
- OPS 未注册 Nacos：Higress `tools/call` 返回 503；Control/Runtime 侧表现为工具失败或 Run 失败，不是模型凭据问题。
- 发布失败清理不完整：已注册的 Nacos Agent/MCP/Skill 实例在失败/回滚路径没有完整撤销，可能留下过期实例；这是 T16 的已知缺口。
- 事件入库仍在优化：重复投递、游标空洞、SSE 重放和终态一致性尚未完成最终验收。

## 7. 当前验证状态

### 7.1 已知端到端事实

截至 2026-11-16，实施过程已提供以下端到端验证事实：

- Nacos 3.x 实测（登录后调用 `/nacos/v3/admin/ns/instance/list`，group=`IPE_AI_COMPONENTS`、namespace=`public`，以下实例均 `healthy=true`）：
  - `ipe-mcp--ops--v1-0-0` -> `<higress-host>:8088`，path=`/ipe-mcp/ipe-ops-rest`；
  - `ipe-skill--protocol-analysis--v1-0-0` -> `<advertise-ip>:19083`，path=`/internal/ai/v1/skills/protocol-analysis/versions/1.0.0`；
  - `ipe-skill--device-offline-check--v1-0-0` -> `<advertise-ip>:19083`，path=`/internal/ai/v1/skills/device-offline-check/versions/1.0.0`；
  - `ipe-agent--protocol-expert--v1-0-0` -> `<advertise-ip>:19084`，path=`/internal/agent/v1/agents/protocol-expert/versions/1.0.0/runs`；
  - `ipe-ai-control` -> `<advertise-ip>:19083`，path=`/internal/ai/v1`。
- 最新 Run `a567cbf1-77d8-4195-b79f-e288ac114c95` 在 Control 重启并重新构建后为 `COMPLETED`，耗时约 40.8 秒；产生 6 个 `tool.completed`、3 个 `skill.loaded`、1178 个 `assistant.delta`，`message.user`/`run.queued`/`run.started`/`run.completed` 各 1，`warnings=[]`；事件 cursor 范围为 2195–3391。
- 该 Run 的工具为 `get_device_detail`、`list_documents`、`load_skill`×2、`read_document`、`read_skill_resource`，全部 `SUCCEEDED`；助手回答包含真实事实：设备 `OFFLINE`、`last_seen=2026-11-15 20:44:10`、中断 `17h13m40s`、文档 46 页且 PDF 无可提取文本层。
- 该 Run 的 `ai_tool_call` 实测 6 行，含 `component_key=ops`、`component_version=1.0.0`、`original_tool_name`、`tool_alias`，状态均为 `SUCCEEDED/ALLOW`。
- SSE 重放实测：`GET /api/ai/v1/conversations/5f675533-49a7-4e38-97ca-37676e24f843/events?afterCursor=2194` 返回 `assistant.delta=1178`、`tool.started=6`、`tool.completed=6`、`skill.loaded=3`、`message.user`/`run.queued`/`run.started`/`run.completed` 各 1、`stream.ready=1`；末尾 `run.completed` 为 `status=SUCCEEDED`、`warnings=[]`，`stream.ready` 的 cursor 为 3391。
- publication 摘要复核为 `PUBLISHED/PUBLISHED`（publication=`b6c20092-dd43-484a-befa-2e48796a9a17`，agent=`protocol-expert@1.0.0`，release=`fe073d45-31fe-4d7c-8e7f-57bfe480d44a`，releaseState=`ACTIVE`，generation=1）。
- 最终构建后冒烟：新会话 `a5636119-9f2b-4880-bd93-18fc4bc01211` 的 Run `4293aa89-44c1-4599-bb0e-19c710528eb3` 为 `COMPLETED`，7 次工具调用中 6 次成功、1 次 `read_skill_resource` 因请求了未提供的资源路径返回 `MCP_TOOL_ERROR`（工具级拒绝），`warnings=[]`；助手回答仍给出设备 `OFFLINE`、`last_seen=2026-11-15 20:44:10` 等真实事实。
- 环境瞬断记录：Run `adad1fe3-8701-45a1-b85a-c4a2f96dd48d` 因 13:56 远端 PostgreSQL（`<postgres-host>:55432`）连接被重置，Control 事件入库不可用、Runtime 心跳失败，最终 `EXECUTION_LOST`；数据库恢复后重试成功，不归因于代码缺陷。
- 早前 Run `0ab842a5-ff3b-45ac-9472-4fc010091349` 已 `COMPLETED`，产生 6 个 `tool.completed`、3 个 `skill.loaded` 和 `run.completed`；`get_device_detail`、`list_documents`、`read_document` 均成功。其故障根因是 OPS 未注册 `iot-protocol-expert-ops`，Higress 对 `tools/call` 返回 503；以 `IPE_NACOS_DISCOVERY_ENABLED=true`、`IPE_OPS_ADVERTISE_IP=<advertise-ip>` 重启 OPS 并确认注册后恢复。
- 事件入库仍在优化，重复投递、迟到写入、游标空洞和最终一致性的最终结果待完成后回填；SSE 按 cursor 重放已单独实测通过。

上述事实证明“发布 → prepare/注册 → Run → 模型/工具/Skill 事件 → SSE 重放”主链路可工作，但不代表 T01–T20 全部通过；尤其是权限变更、故障注入和并发验收仍未完成。

### 7.2 T01–T20 映射

| 编号 | 设计验收目标 | 当前实现证据 | 状态 |
|---|---|---|---|
| T01 | MCP + Skill + Agent 发布，publication PUBLISHED、三类 Naming、真实对话与技能加载 | 管理 API、发布协调、Agent/Version prepare/注册、真实 Run 已打通；Nacos 实测 `ipe-mcp--ops--v1-0-0`、两个 `ipe-skill--...`、`ipe-agent--protocol-expert--v1-0-0` 和 `ipe-ai-control` 均 healthy=true；此前 publication 查询为 `PUBLISHED`（protocol-expert 1.0.0） | **已验证（核心链路）**：三类 Naming 均存在；最新构建后的 publication 记录未逐条复核 |
| T02 | 两个 MCP 同名工具 alias 不同，审计可追溯 Provider，参数不串服务 | `ToolAliasCodec`、manifest 工具身份、Runtime `PreparedTool` 映射已存在；Run `a567cbf1-77d8-4195-b79f-e288ac114c95` 的 `ai_tool_call` 实测 6 行，含 `component_key=ops`、`component_version=1.0.0`、`original_tool_name`、`tool_alias`，状态均为 `SUCCEEDED/ALLOW` | **部分验证**：审计已具备；仍缺“两个 MCP 同名工具”专项验证 |
| T03 | Skill 新版本不影响旧会话，旧内容不可覆盖 | `ai_skill_release` 不可变 bundle、会话/发布保存 digest、新版本需重新编译 Agent | **待验证** |
| T04 | 两个编辑器同 revision 保存仅一个成功 | 模型/凭据/MCP/Skill/Agent 更新均使用 `expectedRevision`，冲突返回 409 | **待验证** |
| T05 | 未绑定工具/猜测资源路径被拒绝 | `tools:authorize` 校验 manifest alias；共享 `SkillPathRules` 校验路径；工具在调用前再次授权 | **待验证**：拒绝路径未实测 |
| T06 | Nacos 暂停超过 60 秒及冷启动，新 Run 503，无 URL 回退 | Nacos 发现、Runtime reconcile 存在；直连回退已改为显式 `IPE_RUNTIME_DIRECT_FALLBACK_ENABLED`，默认 false；Nacos 无健康实例时不再派发。60 秒缓存/503 语义与故障注入未验证 | **部分满足**：无 URL 回退已实现，故障注入待补 |
| T07 | required/optional MCP 不可用行为 | `PrepareService` 对 required 抛错、optional 整体移除并记录 warning；存在单元测试文件 | **待验证**：未做 required/optional 故障注入 |
| T08 | MCP JSON/SSE、分页、isError、429、Schema 漂移 | MCP SDK 连接、工具目录摘要、`McpToolEnvelope` 与 Schema 校验存在；真实 Run 的工具调用成功，但协议矩阵未实测 | **待验证（部分）** |
| T09 | 浏览器断线后按 cursor 重连，不重复请求模型 | `ai_run_event.cursor`、EventController 轮询重放、SSE `id`/`stream.ready` 已实现；实测 `afterCursor=2194` 重放返回 `assistant.delta=1178`、`tool.started=6`、`tool.completed=6`、`skill.loaded=3`、生命周期事件各 1、`stream.ready=1`，末尾 `run.completed` 为 `SUCCEEDED` 且 `warnings=[]` | **已验证（重放，不含断线重连专项）** |
| T10 | submit/ACK/complete 丢失与重复 | Run 幂等键、Runtime receipt、事件唯一键、终态 token/owner 校验存在；receipt 在内存，事件游标预留可能产生空洞 | **待验证（部分）** |
| T11 | 停止 Runtime 后旧实例迟到写入被拒绝 | `executionToken + runtimeInstanceId` fencing、租约过期 `EXECUTION_LOST`、事件/终态 owner 校验已实现 | **待验证**：未做停机故障注入 |
| T12 | 取消、deadline、队列满 | Runtime 有界执行/等待队列、429、取消 dispose、deadline 与 Control 收敛已实现；Control 的 per-user/queue 限额配置未被使用 | **待验证（部分）** |
| T13 | 权限变化、紧急停用 MCP 后下一次拒绝 | authorize 校验 manifest alias、Agent/MCP release `REVOKED`、authVersion、角色策略和站点范围；每次调用重新向 OPS 解析用户身份与 authVersion，不一致即拒绝。组件 disable/revoke API 未暴露 | **部分满足**：代码路径已补，权限变更故障注入待验证 |
| T14 | Skill 路径穿越、大小写冲突、超限文件/YAML | `SkillPaths`、`SkillValidator`、发布与 prepare 双层校验存在；非法路径返回 400 而非 422 | **待验证（部分）**：错误码偏差 |
| T15 | 删除被引用组件返回 409，且无孤儿快照 | Agent/MCP/Skill 删除均检查发布与绑定引用并返回 `COMPONENT_IN_USE`；没有引用位置明细，delete 未锁定组件主记录 | **待验证（部分）** |
| T16 | Control 重启、Nacos 成功但 DB 未切指针 | publication 阶段持久化、V8 租约、outbox、Runtime desired-release 对账存在；已完成正常 Control 重启后的 E2E；失败后 Nacos Agent/MCP/Skill 实例清理不完整，未做“Nacos 成功但 DB 未切指针”故障注入 | **待验证（部分）** |
| T17 | 模型密钥轮换，新 Run 用新 revision，旧 Run 按约定完成 | rotation 生成新 secretRevision，接口/日志不回显明文；manifest 冻结 `currentSecretRevision`，新 Run 不会自动用新 revision，除非重新发布 Agent | **待验证（行为偏差）** |
| T18 | Agent 停用/回滚，目录与旧会话策略一致 | rollback 检查目标版本健康并切默认指针；目录只列 ACTIVE；没有 RETIRED 生命周期转换，停用仅 `enabled=false` | **待验证（部分）** |
| T19 | 不同 model profile 产生不同实际 model 字段 | Agent manifest 冻结模型 profile/modelName；Runtime 从 manifest 构建模型，未使用全局模型默认值执行 | **待验证** |
| T20 | Runtime 不接触数据库 | Runtime POM 无 JDBC/MyBatis/Flyway/数据源依赖并排除 `sqlite-jdbc`；无数据库访问代码 | **待验证（静态符合）**：依赖树与断网 E2E 未执行 |

### 7.3 已完成的构建与测试记录

以下为 2026-11-16 实施过程提供的构建/测试结果：

- 前端 `npm run typecheck` 通过；`npm run build` 通过（Vite 6.4.6，4038 modules）。
- Spotless 已对 `ops`、`ai-contract`、`ai`、`agent-runtime` 执行 apply；修正了 `OpsIdentityController` 与 contract 8 个文件的格式违规。
- `MapperXmlBindingTest` 因新增 `insertRuntimeEvents(List<RuntimeEventInsert>)` 使用非空字符串列表样本而失败；改为 `List.of()` 后单测通过。
- 全量 `mvn -B -Pcoding-guidelines verify` 通过：9 个模块全部 `SUCCESS`，总耗时 46.904 秒；`iot-protocol-expert-ai` 23 个测试、`agent-runtime` 22 个测试均无失败，`ops` 33 个测试中 4 个按既有条件跳过。
- `python scripts/verify-coding-guidelines.py` 通过：388 个文件、0 违规。
- 最终构建后重新启动 OPS/Control/Runtime，Runtime 对账 `release reconcile finished: prepared=1 failed=0`；随后新建会话 `a5636119-9f2b-4880-bd93-18fc4bc01211` 执行 Run `4293aa89-44c1-4599-bb0e-19c710528eb3`，状态 `COMPLETED`，7 次工具调用中 6 次 `SUCCEEDED`、1 次 `read_skill_resource` 因请求未提供的资源路径返回 `MCP_TOOL_ERROR`（属工具级拒绝，非基础设施故障），`warnings=[]`。
- T06 默认关闭直连回退后再次复测：会话 `3fd2cf52-6023-400c-8bed-ab887c278fe4` 的 Run `a3d36d0c-ea41-4fad-b560-460dfba51a5e` 经 Nacos 发现正常派发，状态 `COMPLETED`，6 次工具调用全部 `SUCCEEDED`、`warnings=[]`，证明关闭 `IPE_RUNTIME_DIRECT_FALLBACK_ENABLED` 后不影响正常链路。
- T13 实时鉴权复测：会话 `053baa19-e543-4111-9918-74f8594efee9` 的 Run `f7810983-fa0c-4c63-ab4b-ed501b7dda45` 在每次工具调用前重新解析 OPS 身份并检查 MCP 版本状态，状态 `COMPLETED`，6 次工具调用全部 `SUCCEEDED`、`warnings=[]`。

未执行或未覆盖：

- `seed-ai.ps1` 已执行复用模式（`-SkipBuild`）并通过：正确识别已运行的 Control/Runtime、通过 Nacos 3.x 登录确认 `iot-protocol-expert-ops` 注册、读取到 `PUBLISHED` 发布摘要。全新环境的完整启停/bootstrap 流程尚未在干净环境重跑。
- 未执行 Nacos 长断/60 秒故障注入、MCP JSON/SSE 全协议矩阵、429、Schema 漂移、Runtime 崩溃窗口等故障注入；正常 Control 重启后的 E2E 已执行。
- 未执行 T01–T20 的完整复现实验；表内列出静态实现证据和已知端到端事实。
- 未执行 reset 脚本清理真实数据；仅做脚本语法解析检查。

## 8. 运维入口

```powershell
# 前置：OPS 已启动并注册 iot-protocol-expert-ops
.\scripts\start-ops-local.ps1

# 预启动 Control(bootstrap=false) -> 启动 Runtime 并等待 readiness -> 重启 Control 触发 bootstrap
.\scripts\seed-ai.ps1

# 强制重建并重启 Control/Runtime，再触发 bootstrap
.\scripts\seed-ai.ps1 -Restart

# 危险操作：停止 seed/start-local 管理的 Control/Runtime，再清理在线 Agent 数据
.\scripts\reset-ai.ps1 -Confirm -StopServices
```

`seed-ai.ps1` 不打印 `.env.local` 的值；它会在启动前尽力检查 OPS 19082 健康与 Nacos `iot-protocol-expert-ops` 注册状态。`logs/control.pid` 记录的 Control 已运行时会复用，不会自动重新触发 bootstrap；需要重新引导时使用 `-Restart`。`start-local.ps1` 启动的 AI 记录在 `run/ai.pid`，seed 默认拒绝重复启动，`-Restart` 会在校验 PID 与 jar 后停止并接管。

`start-ops-local.ps1` 只启动 OPS，默认开启 Nacos Discovery，读取 `.env.local` 中的 `IPE_OPS_ADVERTISE_IP` 作为注册地址。

`reset-ai.ps1` 不删除 Flyway 历史、`ipe_ai` schema、`ai_permission_policy`、知识库表、`ai_audit_log` 和旧版 `ai_agent_definition`/`ai_agent_binding`。它拒绝 `IPE_ENVIRONMENT` 或数据库 URL 含 `prod/production` 的环境，并要求显式 `-Confirm`。

## 9. 未完成与待回填

1. 事件入库优化完成后，回填 T10/T11 的重复投递、迟到写入、游标空洞与终态一致性结果；T09 的 cursor 重放已验证。
2. 补做 T01–T20 的可复现记录，尤其是 Nacos 断网、MCP 协议矩阵、并发编辑、密钥轮换、Control/Runtime 崩溃窗口。
3. 补全设计稿中的草稿预览、组件 disable/revoke、发布列表与 OpenAPI/JSON Schema。
4. 执行 Runtime `dependency:tree` 和撤销数据库网络访问的 E2E，形成 T20 证据。
5. 完成 MCP/模型出网地址白名单、私网/回环阻断、DNS 重绑定防护和生产 TLS/秘密文件部署方案。
6. 补做 MCP/Skill 注册在 Nacos 重启、网络瞬断、发布失败/回滚后的补注册与清理验证；当前 30 秒 reconcile 只补注册缺失的 ACTIVE 版本。
7. 补做 T06 故障注入：关闭 Nacos 注册或摘除 Runtime 实例，确认默认配置下新 Run 返回 `NO_HEALTHY_RUNTIME`/503 且不会直连 `IPE_AGENT_RUNTIME_BASE_URL`。
8. 补做 T13 故障注入：修改用户权限或提升 authVersion 后，验证下一次 `authorize` 返回 `AUTH_VERSION_CHANGED`；并补齐组件 disable/revoke API 以触发 MCP `REVOKED`。
9. 补齐 `ai_run.compiled_plan` 和平台管理审计；补做 T02 的两个 MCP 同名工具 alias 专项验证，以及 `ai_tool_call` 失败分支核验。
10. 为 `IPE_OPS_BASE_URL` 增加部署期校验，避免远程部署误用本机默认地址。
