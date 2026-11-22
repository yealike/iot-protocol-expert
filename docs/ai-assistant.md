# AI 助手与在线 Agent 平台

> 代码模块：`iot-protocol-expert-agent-control`（Control，HTTP 19083）、`iot-protocol-expert-agent-runtime`（Runtime，HTTP 19084）
> 设计基线：`设计文档/Agent在线配置、发布与运行方案.md`
> 实施记录与 T01–T20 状态：`docs/online-agent/implementation-log.md`
> 当前状态：模块已恢复命名并拆分 OPS/Control，MCP/Skill 版本与发布由平台管理，Higress 承担 REST-to-MCP；本轮未做外部环境全链路验收。详见 [模块与组件治理](module-architecture.md)。

## 1. 进程与职责

```text
浏览器 / Web
  ├─ /api/ai/v1 ─────────────> Control :19083
  └─ /api/ai/v1/.../events ──> Control SSE

Control
  ├─ PostgreSQL pep_platform / pep_domain / pep_agent
  ├─ Nacos：Control 与 Runtime 的进程级服务（可选）
  │    ai_registry_projection 仅保存期望/观测投影
  └─ 内部签名 HTTP ────────> Runtime :19084

Runtime
  ├─ 拉取 manifest/Skill/MCP 目录
  ├─ AgentScope ReAct + 模型
  ├─ Higress MCP ───────────> OPS :19082
  └─ claim/heartbeat/events/complete ──> Control
```

- Control 是唯一持久状态源：保存配置、发布、会话、Run、事件游标和聊天历史。
- Runtime 不访问数据库，不扫描 `ai_run`；只接收 Control 派发，使用内存执行并通过内部 HTTP 回写。
- 管理端不在 OPS 后端实现，OPS 只提供身份内部接口和业务数据工具。
- 两进程都不直接读取运营业务表；工具数据经 Higress MCP 访问 OPS。
- Higress 后端可以使用静态服务或 Nacos Naming；只有选择 Naming 来源时，OPS 才需注册 `iot-protocol-expert-ops`。

## 2. 前端入口

| 页面 | 路由 | 说明 |
|---|---|---|
| AI 能力管理 | `/ai-admin` | 仅 `ai:admin` 可管理 |
| 模型配置 | `/ai-admin/models` | 模型、凭据、连通测试 |
| MCP 服务 | `/ai-admin/mcps` | MCP 草稿、测试、工具目录和发布 |
| Skill 编辑 | `/ai-admin/skills` | 文本文件、校验、预览、发布 |
| Agents | `/ai-admin/agents` | 模型/提示词/预算/Skill/MCP/工具绑定、编译和发布 |
| 发布与注册 | `/ai-admin/publications` | 发布状态、重试和组件版本诊断 |
| AI 助手 | `/assistant` | 选择可用 Agent、创建会话、SSE 对话 |
| 知识索引 | `/ai-knowledge` | 既有协议文档索引，与 Skill 不是同一概念 |

浏览器发往 `/api/ai/**` 的写请求必须带 `X-AI-Client`。管理接口还要求 OPS 角色权限映射出 `ai:admin`；普通对话要求 `ai:use`。

## 3. 数据与版本

Agent 在线数据位于 `pep_agent`，由 OPS 的 Flyway 管理；迁移目录为
`iot-protocol-expert-ops/src/main/resources/db/migration/`。Control 关闭重复 Flyway。

| 范围 | 主要表 |
|---|---|
| 配置 | `ai_model_profile`、`ai_credential`、`ai_credential_secret`、`ai_mcp_*`、`ai_skill_*`、`ai_agent_component` |
| 发布 | `ai_agent_mcp_binding`、`ai_agent_skill_binding`、`ai_agent_release`、`ai_publication`、`ai_registry_projection`、`ai_outbox` |
| 运行 | `ai_conversation`、`ai_message`、`ai_run`、`ai_run_event`、`ai_tool_call`、`ai_checkpoint` |
| 权限/知识 | `ai_permission_policy`、`ai_document`、`ai_chunk`、`ai_index_version`、`ai_audit_log` |
| 内部安全 | `ai_internal_nonce` |

关键约定：

- 原 V7/V8 的在线 Agent 配置、发布与 publication 多 Control 协调租约已合入 OPS V1 基线。
- 不新增物理外键/级联；引用检查和删除保护在应用事务内完成。
- Agent 草稿更新使用 `revision` 乐观锁；发布产物不可覆盖。
- 会话固定 `agentReleaseId + manifestDigest`，不会因新版本发布而静默切换。
- `ai_credential_secret` 只保存 AES-256-GCM 密文、nonce、keyId 和 secretRevision；主密钥不落库。

## 4. 在线配置与发布

### 4.1 模型与凭据

1. 在 `/ai-admin/credentials` 新建凭据或轮换密钥。接口只返回 `masked/keyId/currentSecretRevision`，不会返回明文。
2. 在 `/ai-admin/models` 创建 OpenAI 兼容模型配置，绑定凭据并设置 `baseUrl/modelName/options`。
3. 模型测试会真实请求供应商，可能产生 token 消耗。
4. 修改 `IPE_CREDENTIAL_MASTER_KEY` 前必须评估已有密文：主密钥变化后旧密文无法解密。生产应使用稳定的秘密文件。

### 4.2 MCP

1. 新建 MCP 草稿，来源使用远程 Streamable HTTP，填写 Higress 或其他远程 MCP endpoint；历史 Nacos 草稿需补充 endpoint。
2. `POST /api/ai/v1/mcps/{id}/test` 执行握手和 `tools/list`，保存测试报告；测试不会调用真实业务工具。
3. 发布时冻结工具目录、输入/输出 Schema、catalog digest 和 endpoint；后续工具变化必须通过新版本发布。
4. Agent 只能绑定已发布 MCP 的明确工具。Runtime prepare 会再次校验 catalog、工具 Schema digest 和连接。
5. `required=true` 的 MCP 不可用时 prepare 失败；`required=false` 时整个 MCP 从工具集合移除并产生 warning。

### 4.3 Skill

1. Skill 是 UTF-8 文本包，入口必须为 `SKILL.md`；frontmatter 对齐
   [Agent Skills](https://agentskills.io/specification) 的 `name`、`description`、`license`、
   `compatibility`、`metadata` 和实验性 `allowed-tools` 字段。
2. `name` 必须与 Skill key 一致，为 2–40 位小写字母/数字，并以单连字符分段（Agent Skills 规范允许 1–64，
   平台当前受 `component_key` 列宽限制为 40）；扩展字段放入 `metadata`。
3. 允许 `SKILL.md`、`LICENSE.md|txt`，以及 `references/`、`scripts/`、`assets/` 下的安全文本文件。禁止绝对路径、
   `.`/`..`、反斜杠、盘符和控制字符。二进制资源不能进入 JSONB bundle，暂不支持。
4. `read_skill_resource` 只读取 `references/`、`scripts/`、`assets/` 下的文件，`SKILL.md` 统一由 `load_skill`
   加载；`scripts/` 会触发只读提示，平台不会执行脚本。实际工具权限始终由 Agent 绑定和 `allowed-tools`
   之外的授权策略决定。
5. 默认限制：50 个文件、单文件 256 KiB、包 2 MiB；大小写折叠后路径不能冲突；拒绝 NUL 和不成对的
   Unicode 代理项；`SKILL.md` 超过 500 行或 12000 字符会产生渐进式加载警告。
6. 草稿可以不完整；保存不完整草稿后不能发布。
7. Runtime 提供 `load_skill` / `read_skill_resource` 两个受控工具，仅允许当前 manifest 绑定的 skill/version/path。
8. `AUTO` 只向模型暴露名称和描述；`ALWAYS` 在每轮注入正文，最多 2 个，并按 `max-read-chars`
   （默认 12000）截断，避免超长 Skill 撑爆上下文。

### 4.4 Agent 编译与发布

发布前编译会校验：

- 模型绑定和凭据可用；
- System Prompt 非空且不超过 20,000 字符；
- Skill/MCP 精确版本均处于可用状态；
- 工具 alias 唯一且不与内置 Skill 工具冲突；
- 一个 Agent 最多 8 个 MCP、16 个 Skill、32 个模型可见工具；
- 预算在硬上限内：最多 12 轮、32 次工具调用、300 秒执行时间。

发布返回 `202 + releaseId + publicationId + manifestDigest`。状态机：

```text
VALIDATING -> PREPARING -> REGISTERING -> PUBLISHED
        \         \             \
         +---------+-------------+-> FAILED（可显式 retry）
```

Agent 发布时，Control 向配置的 Runtime 地址调用 `releases:prepare`。Runtime 装配清单、Skill bundle 和 MCP 工具并校验摘要，返回 `READY` 后 Control 将该版本设为 ACTIVE。升级前停在 `REGISTERING` 的 Agent 发布任务也会重新确认 Runtime 就绪。发布超时为 60 秒，失败按指数退避重试，最多 5 次。

MCP 与 Skill 发布冻结目录/bundle 和入口，写入平台发布事务与 `ai_registry_projection`；不再注册 Nacos MCP/Skill Naming 实例，也不依赖这些实例的健康状态。

## 5. Runtime 执行

### 5.1 启动与就绪

Runtime 启动日志应包含：

- `Started AgentRuntimeApplication`
- Nacos 启用时：`runtime base service registered: service=iot-protocol-expert-agent-runtime`
- 完成 prepare 后：`release prepared: release=... agent=... version=... digest=...`

健康检查：

```powershell
Invoke-RestMethod http://127.0.0.1:19083/actuator/health
Invoke-RestMethod http://127.0.0.1:19084/actuator/health/readiness
```

`/actuator/health/readiness` 使用 `runtimeReadiness`。Nacos 关闭时，只要 Control 地址和内部服务密钥可用即可接单；Nacos 开启时必须基础服务注册成功，`readiness` 才为 UP。

Runtime 重启后通过 `/internal/ai/v1/runtime/desired-releases` 对账 ACTIVE 版本并重新 prepare；不依赖管理员再次点击发布。

### 5.2 Run 状态

```text
QUEUED -> DISPATCHING -> RUNNING -> SUCCEEDED
                  |          |----> FAILED
                  |          |----> CANCELLED
                  |          \----> TIMED_OUT
                  \-> CANCEL_REQUESTED -> CANCELLED
```

- Control 认领 Run 时生成严格递增 `executionToken`；Runtime 以 `(runId, token, instanceId)` fencing。
- Runtime 先向 Control `:claim`，成功后才返回 202；重复提交同 token 返回已有 receipt。
- 心跳间隔 5 秒，Control 租约至少 30 秒；运行时失去所有权后停止本地执行。
- 租约过期由 Control 标记 `EXECUTION_LOST`，不会自动重演整个 Run。
- 取消是协作式取消；已经发到外部 Provider 的请求不保证物理未执行，工具结果可能是 `UNKNOWN`。

### 5.3 事件与 SSE

- Runtime 事件 `sourceSeq` 在单 Run 内从 1 单调递增，事件批次最多 32 条/64 KiB。
- Control 按 `(runId, executionToken, sourceSeq)` 和 `eventId` 去重，再分配会话内 cursor。
- 事件先入库后 ACK；Runtime 未 ACK 队列上限 256 条/1 MiB，超过后暂停模型流，持续 10 秒失败则终止执行。
- SSE 使用 `GET /api/ai/v1/conversations/{id}/events?afterCursor=N`，`id` 为 cursor，支持断线续传。
- 每实例最多 256 个 SSE 连接，单连接最长 30 分钟；超限返回 429。

常见事件：`message.user`、`run.queued`、`run.started`、`assistant.delta`、`tool.started`、`tool.completed`、`tool.failed`、`skill.loaded`、`run.warning`、`run.completed`、`run.failed`、`run.cancelled`、`stream.ready`。

## 6. 内部接口

所有 `/internal/**` 接口都要求服务身份 HMAC。请求头由 `InternalSignature` 统一生成/校验，覆盖 method、path+query、body、timestamp、nonce 和 audience。

Control：

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

Runtime：

```text
POST /internal/agent/v1/releases:prepare
POST /internal/agent/v1/agents/{agentKey}/versions/{version}/runs
GET  /internal/agent/v1/runs/{runId}?executionToken=...
POST /internal/agent/v1/runs/{runId}:cancel
```

Control 必须拒绝过期 token、错误 instanceId、非 ACTIVE release、错误 manifestDigest 和已终结 Run 的迟到写入。Runtime 不得向浏览器暴露 19084。

## 7. 环境变量

### 7.1 Control

| 变量 | 说明 |
|---|---|
| `PEP_CONTROL_DB_URL/USER/PASSWORD` | 与 OPS 共用现有数据库；历史迁移由 OPS 执行，schema 使用 `pep_platform` / `pep_agent` |
| `IPE_AI_REGISTRY_ENABLED` | 在线 Agent 平台的 Nacos Naming 注册开关；本地无 Nacos 时为 `false` |
| `IPE_NACOS_SERVER_ADDR/USERNAME/PASSWORD` | Nacos 服务与认证 |
| `IPE_NACOS_NAMESPACE` / `IPE_AI_COMPONENT_GROUP` | Nacos namespace/group，默认 `public` / `IPE_AI_COMPONENTS` |
| `IPE_AI_ADVERTISE_IP` / `IPE_CONTROL_SERVICE_NAME` | 可被 Runtime 访问的 Control 地址/服务名 |
| `IPE_AI_MCP_GATEWAY_BASE` | 显式拼接既有 Higress `/ipe-mcp/{serverName}` 入口 |
| `IPE_AI_MCP_REGISTRY_ENABLED` | 旧兼容变量，不再触发 MCP 目录订阅 |
| `IPE_AI_MCP_TOKEN` | OPS MCP 服务校验的共享令牌，在平台登记对应调用凭据 |
| `IPE_CREDENTIAL_MASTER_KEY` / `_FILE` | 凭据加密主密钥；生产必须稳定配置 |
| `IPE_INTERNAL_SERVICE_KEY` / `_FILE` | Control/Runtime 共享 HMAC 密钥，两端必须一致；Control 当前只读取环境变量，Runtime 还支持 `_FILE` |
| `IPE_INTERNAL_SERVICE_NAME` / `IPE_RUNTIME_SERVICE_NAME` | 内部调用身份名，本地脚本默认 `control` / `runtime` |
| `IPE_AGENT_RUNTIME_BASE_URL` | Control 派发到单个 Runtime 的地址。默认 `http://127.0.0.1:19084`，Compose 为 `http://ipe-runtime:19084` |
| `IPE_OPS_BASE_URL` | OPS 身份接口地址；默认 `http://127.0.0.1:19082`，OPS 不在本机或端口不同时必须显式设置 |
| `IPE_RUNTIME_MAX_DISPATCH_ATTEMPTS` | Control 最大派发次数，默认 3 |
| `IPE_RUNTIME_LEASE_SECONDS` | Run 租约，默认至少 30 秒 |
| `IPE_RUNTIME_QUEUE_LIMIT` / `IPE_RUNTIME_PER_USER_QUEUED` / `IPE_RUNTIME_PER_USER_IN_FLIGHT` | 队列和用户限额 |

注意：

- `IPE_NACOS_DISCOVERY_ENABLED` 控制 Spring Cloud 服务注册；`IPE_AI_REGISTRY_ENABLED` 控制在线 Agent 平台的 Naming 契约，两者不能混为一谈。
- `IPE_AI_MCP_REGISTRY_ENABLED` 为旧兼容变量；系统工具入口不再订阅 Nacos AI 目录。
- 本地脚本默认关闭 Nacos，Control 使用配置的单个 Runtime 地址。
- Agent、MCP 和 Skill 的版本由平台管理。Nacos 可用于进程级发现。
- 协议专家只绑定 `resolve_device`、`list_device_frames`、`decode_device_frame`、`search_protocol_knowledge`。第三方设备号先由 `resolve_device` 映射为平台 8 位 SN，报文与文档查询再使用平台身份。
- `IPE_CONTROL_SERVICE_NAME` 同时影响 Control 的平台 Nacos 服务名和 Runtime 的发现目标，默认统一为 `iot-protocol-expert-agent-control`；如需覆盖，Control 与 Runtime 必须使用同一值。

### 7.2 Runtime

| 变量 | 说明 |
|---|---|
| `IPE_RUNTIME_PORT` | Runtime HTTP 端口，默认 19084 |
| `IPE_CONTROL_BASE_URL` | Control 内部地址，默认 `http://127.0.0.1:19083` |
| `IPE_CONTROL_SERVICE_NAME` / `IPE_RUNTIME_SERVICE_NAME` | Control 发现名 / Runtime HMAC 身份，默认 `iot-protocol-expert-agent-control` / `runtime` |
| `IPE_RUNTIME_KEY_ID` | 服务密钥标识，默认 `runtime` |
| `IPE_INTERNAL_SERVICE_KEY` / `_FILE` | 必须与 Control 相同 |
| `IPE_AI_REGISTRY_ENABLED` | 是否注册 Runtime 基础服务 `iot-protocol-expert-agent-runtime` 并启用 Control 的服务发现 |
| `IPE_NACOS_*` / `IPE_AI_COMPONENT_GROUP` | Nacos 地址、认证、namespace、group |
| `IPE_RUNTIME_ADVERTISE_IP` | Runtime 的可达广播 IP；远程调用时不能注册 loopback |
| `IPE_RUNTIME_MAX_ACTIVE` / `IPE_RUNTIME_QUEUE_CAPACITY` | 同时执行 4、等待 8（默认） |

Runtime 的资金/上下文预算硬上限为 12 轮、32 次工具调用、300 秒；Control manifest 中的预算只能收紧，不能超过硬上限。

### 7.3 OPS 注册与身份

| 变量 | 说明 |
|---|---|
| `IPE_NACOS_DISCOVERY_ENABLED` | Higress 使用 Nacos Naming 来源时为 `true`，注册名为 `iot-protocol-expert-ops` |
| `IPE_OPS_ADVERTISE_IP` | OPS 的 Nacos 广播地址，必须能被 Higress 访问 |
| `IPE_NACOS_SERVER_ADDR` / `IPE_NACOS_NAMESPACE` / `IPE_NACOS_GROUP` | Nacos 地址、namespace、group；OPS 默认 `DEFAULT_GROUP` |
| `IPE_OPS_BASE_URL` | Control 调用 OPS 身份接口的地址，必须是 `http://127.0.0.1:19082`（或实际 OPS 地址） |
| `IPE_OPS_SERVICE_TOKEN` | Control→OPS 内部接口令牌，需与 OPS 配置一致 |

Higress 使用 Nacos Naming 来源时，OPS 未注册会使调用返回 503；使用静态后端则不依赖注册。

### 7.4 模型与知识库

| 变量 | 说明 |
|---|---|
| `IPE_MODEL_ENABLED` | 旧助手确定性降级开关；在线 Agent 实际模型来自 manifest |
| `IPE_MODEL_BASE_URL/API_KEY/NAME` | 模型参数；密钥只放本机环境变量或凭据表 |
| `IPE_MODEL_TIMEOUT_MS/MAX_TOKENS/TEMPERATURE` | 模型超时、输出上限、温度 |
| `IPE_AI_RUN_MAX_*` | 并发、轮次、工具调用、超时、上下文消息数 |
| `IPE_AI_CHUNK_SIZE/OVERLAP/TOP_K/MIN_SCORE` | 知识库分块与检索参数 |
| `IPE_RUNTIME_OTEL_*` | Runtime AgentScope Trace 与内容导出；生产默认关闭正文 |

## 8. 本地启动与验证

### 8.1 前置

- JDK 21、Maven、PostgreSQL、Nacos、Higress 按实际环境可用。
- `.env.local` 从 `.env.example` 复制并填写真实值；不要提交 `.env.local`。
- 需要服务发现时显式设置 `IPE_AI_REGISTRY_ENABLED=true`，MCP 使用实际的 Higress endpoint。
- OPS 必须先启动，Higress 后端须可达；采用 Naming 来源时再启用 `IPE_NACOS_DISCOVERY_ENABLED=true` 并正确配置广播 IP。
- `start-local.ps1` 启动完整本地服务。只需 AI 相关服务时可运行 `seed-ai.ps1`。
- 本机默认使用 `IPE_OPS_BASE_URL=http://127.0.0.1:19082`；OPS 地址不同时必须在 `.env.local` 或进程环境中显式覆盖。

### 8.2 启动

```powershell
# 1. 先启动 OPS，并确认 Nacos 中有 iot-protocol-expert-ops
# 2. 构建并启动服务，等待健康检查通过
.\scripts\seed-ai.ps1

# 强制重建并重启已由本项目脚本启动的进程
.\scripts\seed-ai.ps1 -Restart

# 已构建且只需启动服务时跳过 Maven
.\scripts\seed-ai.ps1 -SkipBuild

# 只启动单个进程（调试时）
.\scripts\start-control-local.ps1
.\scripts\start-runtime-local.ps1
```

`seed-ai.ps1` 启动 OPS、Model Gateway、Control 和 Runtime。首次使用时，在平台配置模型、凭据、MCP 地址和工具策略，按需配置 Skill 与导入知识文档，再为 Agent 配置提示词和工具绑定并发布。

### 8.3 最小验证

1. Control `/actuator/health` 为 UP。
2. Runtime `/actuator/health/readiness` 为 UP。
3. Runtime 日志出现 `release prepared`。
4. `/ai-admin/publications` 中 publication 为 PUBLISHED。
5. `/assistant` 新建会话，发送问题，确认 `run.started → assistant.delta → run.completed`。
6. 用同一 `afterCursor` 重放，确认事件完整且客户端不重复请求模型；浏览器真实断线重连仍待专项验证。

2026-11-18 最新完整链路验证：实验 `<current-experiment-id>`；基础 Run `<baseline-run-id>`；工具 Run `<tool-run-id>`；工具结果 `<tool-result-id>`；Trace `<current-trace-id>`，共 146 个 Span。完整步骤和未完成项见 [Agent 全流程验收报告](agent-full-flow-report-2026-11-17.md)。

早前 Run `0ab842a5-ff3b-45ac-9472-4fc010091349` 保留为历史记录；其故障根因是 OPS 未注册 `iot-protocol-expert-ops`，Higress 返回 503，修复后恢复。当前证据以上述 2026-11-18 全流程为准；T01–T20 的逐项状态仍以实施记录为准。

### 8.4 重置

```powershell
# 先停止 seed/start-local 管理的 Control/Runtime，再清理
.\scripts\reset-ai.ps1 -Confirm -StopServices
# 仅查看 DELETE 清单，不连接数据库
.\scripts\reset-ai.ps1 -Confirm -WhatIf
```

该脚本必须先停止 Control/Runtime，仅删除 `pep_agent` 中在线 Agent 的运行、发布和配置数据：

- 删除：`ai_run_event`、`ai_tool_call`、`ai_checkpoint`、`ai_context_summary`、`ai_run`、`ai_message`、`ai_conversation`、`ai_outbox`、`ai_registry_projection`、`ai_publication`、`ai_agent_skill_binding`、`ai_agent_mcp_binding`、`ai_agent_release`、`ai_agent_component`、`ai_skill_draft_file`、`ai_skill_release`、`ai_skill_component`、`ai_mcp_test_report`、`ai_mcp_release`、`ai_mcp_component`、`ai_model_profile`、`ai_credential_secret`、`ai_credential`、`ai_internal_nonce`。
- 保留 `pep_agent` 中的历史调查记录、知识库、`ai_permission_policy`、`ai_audit_log` 和 Flyway 历史。
- 环境变量或数据库 URL 含 `prod/production` 时拒绝执行。

## 9. 既有知识库与助手

- 协议问答仍支持解析 PDF/MD/TXT、分块、关键词索引、发布和引用。
- 管理接口：`GET /api/ai/v1/knowledge/documents`、`POST /api/ai/v1/knowledge/index`、`POST /api/ai/v1/knowledge/documents/{id}/publish|archive`。
- `POST /api/ai/v1/knowledge/index` 需要 `ai:admin`；`publish=true` 表示本次操作完成审核发布并写审计。
- 页码不可靠时返回空页码，不伪造；证据不足时返回 warning，由 Agent 明确缺失。
- pgvector 迁移是可选能力，当前关键词检索仍是主路径。

## 10. 故障排查

| 现象 | 优先检查 |
|---|---|
| 401 | OPS cookie/登录是否有效；Control 是否能访问 OPS 身份接口 |
| 403 `CLIENT_HEADER_REQUIRED` | 写请求是否带 `X-AI-Client` |
| 403/无管理菜单 | OPS 角色是否映射 `ai:admin`；普通对话需要 `ai:use` |
| 503 `INTERNAL_SIGNATURE_INVALID` | Control/Runtime 的 `IPE_INTERNAL_SERVICE_KEY` 是否一致；机器时钟是否偏差超过 30 秒；服务名/audience 是否匹配 |
| 409 `REVISION_CONFLICT` | 草稿已被其他请求修改；刷新页面后重试，不要覆盖 |
| 422 `AGENT_COMPILE_FAILED` | 模型/凭据/依赖版本/alias/预算/Prompt 校验失败；查看 errors |
| publication 停在 PREPARING | Runtime 是否健康；所选 MCP/Skill 发布版本和固定 endpoint 是否可用；`/ai-admin/publications/{id}` 的 errorCode；Runtime `release prepared` 日志 |
| publication FAILED 后重试 | 先修复依赖，再调用 `POST /api/ai/v1/publications/{id}/retry`；不要重复发布同版本 |
| Runtime readiness DOWN | `IPE_INTERNAL_SERVICE_KEY`、Control 地址、Nacos 地址/namespace/group/广播 IP |
| MCP `tools/call` 503 | OPS 是否以 `iot-protocol-expert-ops` 注册 Nacos；`IPE_NACOS_DISCOVERY_ENABLED` 与 `IPE_OPS_ADVERTISE_IP` 是否正确；Higress 能否访问该地址 |
| 新 Run 一直排队或 503 `NO_HEALTHY_RUNTIME` | 检查 `IPE_AGENT_RUNTIME_BASE_URL`、Runtime 健康状态和发布准备日志 |
| 429 `RUN_BUSY` | Runtime 并发/等待队列已满；降低并发或扩容；不要读取内部 inflight 配额 |
| Run `EXECUTION_LOST` | Runtime 停止、网络分区或心跳中断；Control 不会自动重演，需重新提问生成新 Run |
| 凭据解密失败 | `IPE_CREDENTIAL_MASTER_KEY` 是否被更换或丢失；旧密文无法用新主密钥恢复 |
| SSE 重复/缺失 | 客户端是否持久化 cursor；检查 `ai_run_event` 的 cursor/sourceSeq/eventId；当前事件入库仍在优化 |
| 模型无输出 | Runtime 是否能拿到模型凭据；模型 baseUrl/modelName/超时；Provider 日志；不要打印密钥 |
| 工具调用已发出但 `ai_tool_call` 无记录 | 新 Runtime 在 `tool.started/completed/failed` 且事件带 `toolCallId` 时写表；检查事件是否成功入库、是否使用旧版执行路径，不能仅凭缺少审计行认定工具未执行 |

## 11. 安全与已知限制

- 不开放 CORS；Cookie 只在 Control→OPS 身份链路使用，不进入 manifest、Nacos、Runtime 或外部 MCP。
- 模型可见工具 = Agent 绑定白名单 ∩ 用户策略 ∩ 当前可用 MCP 工具 ∩ 紧急停用规则。
- 工具正文、Skill 正文和用户输入均按不可信数据处理，不能改变授权规则。
- `authorize` 会校验 manifest alias、Agent/MCP release 的 `REVOKED`、Run 创建时保存的 `authVersion`、角色策略和站点范围；每次工具调用都会重新向 OPS 解析用户身份与最新 `authVersion`，变化时返回 `AUTH_VERSION_CHANGED`。组件 disable/revoke API 与权限变更故障注入仍待补齐。
- Control 的发布准备、派发、查询和取消都走配置的单个 Runtime 地址。Runtime 启动后会从 Control 恢复已发布版本。
- Runtime 使用已发布 MCP 快照中的固定 endpoint。组件诊断读取平台发布状态。
- `ai_tool_call` 已由新 Runtime 事件入库路径写入，6 行 E2E 记录含组件版本、原始工具名和 alias；两个 MCP 同名工具、参数摘要和失败分支仍需专项核验。`ai_run.compiled_plan` 未填充，平台管理操作也尚未接入 `ai_audit_log`。
- 凭据轮换会生成新 `secretRevision`，但 Agent manifest 冻结旧 revision；新 Run 不会自动使用新密钥，必须重新发布 Agent。
- `OpsIdentityClient` 的 Spring 配置默认值已修正为 `http://127.0.0.1:19082`；部署到其他主机或使用其他端口时必须显式设置 `IPE_OPS_BASE_URL`。
- Runtime 的 `dependency:tree` 与断网 E2E 尚未形成 T20 证据；当前只确认 POM 无 JDBC/MyBatis/Flyway 依赖。
- 草稿预览、组件 disable/revoke、发布列表分页、完整 OpenAPI/JSON Schema 尚未实现。
- 事件入库最终一致性、Nacos 长断、MCP 协议矩阵、Control/Runtime 崩溃窗口和模型密钥轮换仍需故障注入验证。

实现过程、代码证据和逐项状态见 `docs/online-agent/implementation-log.md`。
