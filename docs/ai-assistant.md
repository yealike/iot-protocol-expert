# AI 助手与 Agent 能力平台（一期落地说明）

> 对应设计文档：`设计文档/协议专家平台-AI助手与Agent能力平台设计方案.md`（V0.1）
> 代码模块：`iot-protocol-expert-ai`（HTTP 19083）
> 状态：MVP 原型；数据库迁移、端到端、安全和多实例恢复尚未完成验收，不具备生产上线条件。

## 1. 落地范围

| 设计文档条目 | 本次实现 | 说明 |
|---|---|---|
| 会话 / 消息 / Run / 事件持久化 | ✅ `ipe_ai` schema | 事件带会话内持久递增游标 |
| SSE 流式输出与断线续传 | ✅ `GET /api/ai/conversations/{id}/events?after=` | 按游标轮询已提交事件，跨实例可见；尚需真实环境压测 |
| 排队 / 暂停 / 继续 / 取消 | ⚠️ 部分实现 | 取消请求保留执行权直到执行器退出；暂停后重新执行，不是检查点恢复 |
| 用户身份与数据范围 | ⚠️ 部分实现 | 执行前及工具调用前重新检查账号与策略；业务侧独立校验用户站点范围并与 AI 策略取交集 |
| Agent / Tool / Skill 授权 | ✅ Agent 与 Tool 授权；Skill 仅预留表结构 | 一期不加载 Skill |
| 协议知识检索（RAG） | ✅ 解析 → 分块 → 关键字索引 → 引用 | pgvector 为可选迁移，未安装自动降级 |
| 订单 / 账单 / 设备 / 遥测工具 | ✅ 经 ops 内部只读接口 | 业务侧再次校验站点范围 |
| Team 协作 | ✅ Leader + 协议 Agent + 订单 Agent | 通过受控分派工具，非自由组队 |
| MCP / Nacos / Higress | ⚠️ 测试环境已有中间件，代码未接入 | 工具按统一拦截器实现，可替换为 MCP Client |
| 可视化编排 / 写操作 | ❌ 未实现 | 属二期 |

## 2. 运行架构

```text
浏览器 /assistant
   │  fetch + cookie（JSESSIONID）
   ▼
iot-protocol-expert-web  ──/api/ai/*──▶  iot-protocol-expert-ai :19083
                                              │         │
                                   cookie 转发 │         │ X-Ops-Service-Token
                                              ▼         ▼
                                    ops /api/ops/auth/me   ops /internal/ai/* :19082
                                              │                    │
                                              └── ipe_ops（业务库）◀┘
AI 自身状态 ── ipe_ai schema（同实例，独立 schema）
```

- AI 服务**不直接读写业务表**；订单、账单、设备、遥测、协议文档都经 ops 内部只读接口获取。
- ops 侧 `InternalAiController` 用 `X-Ai-Station-Scope` 再做一次站点范围校验，不依赖 AI 侧过滤。
- AI 服务不签发身份，也不解析前端传入的角色、站点或内部用户头。

## 3. 技术选型验证结论（切片 0）

在本机实测（JDK 21.0.12 + Maven 3.8.8 + Spring Boot 4.0.9）：

| 验证项 | 结果 |
|---|---|
| `io.agentscope:agentscope-core:2.0.4` 可解析 | ✅ Maven Central 有 2.0.4（2.1.0-BETA1 为预发布，未采用） |
| 与 Spring Boot 4 共存 | ✅ AgentScope 使用 Jackson 2.21，Boot 4 使用 Jackson 3，包名不同可并存 |
| 应用上下文启动 | ✅ 最小工程可启动，`Toolkit` 注册与 `streamEvents` 可触发 |
| `HarnessAgent` 适用性 | ⚠️ 默认带 shell、文件系统、子 Agent、memory、skill 工具，超出本项目只读边界 |
| 最终选择 | `ReActAgent`（core）+ 白名单 `Toolkit`，不引入 `agentscope-harness` |

结论：一期固定 `agentscope-core` / `agentscope-extensions-model-openai` 2.0.4，不使用 SNAPSHOT 或预发布。

## 4. 数据模型（`ipe_ai`）

迁移脚本：`iot-protocol-expert-ai/src/main/resources/db/migration/`

| 表 | 用途 |
|---|---|
| `ai_conversation` | 会话、Agent 版本、事件游标、消息序号 |
| `ai_message` | 原始消息与引用，`seq` 会话内递增 |
| `ai_run` | 一次提问的执行记录、状态、优先级、租约、预算与用量 |
| `ai_run_event` | 公开事件流，`cursor` 会话内唯一 |
| `ai_tool_call` | 工具调用授权决策、参数摘要、状态、耗时 |
| `ai_checkpoint` | 可恢复元信息（当前实现以消息历史恢复） |
| `ai_context_summary` | 摘要覆盖范围与版本（预留） |
| `ai_agent_definition` / `ai_agent_binding` | Agent 版本与工具绑定 |
| `ai_permission_policy` | 角色 → Agent / Tool / 站点范围 |
| `ai_document` / `ai_chunk` / `ai_index_version` | 知识源、片段与索引版本 |
| `ai_audit_log` | AI 侧操作审计 |

`V2__ai_pgvector_optional.sql` 用 PL/pgSQL 异常处理尝试安装 `vector` 扩展并添加 `vector(1024)` 与 HNSW 索引；失败只告警，不阻塞迁移。检索实现当前以关键字为准（见第 7 节）。

## 5. HTTP 契约

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ai/agents` | 当前用户可用的 Agent 及其可见工具 |
| POST | `/api/ai/conversations` | 创建会话 `{agentKey,title?}` |
| GET | `/api/ai/conversations?page&size` | 我的会话分页 |
| GET | `/api/ai/conversations/{id}` | 会话 + 消息 + 当前 Run |
| POST | `/api/ai/conversations/{id}/runs` | 提交问题 `{question,idempotencyKey,priority?}` |
| GET | `/api/ai/runs/{id}` | Run 详情与工具调用 |
| POST | `/api/ai/runs/{id}/pause` | 请求在安全点暂停 |
| POST | `/api/ai/runs/{id}/resume` | 重新排队恢复 |
| POST | `/api/ai/runs/{id}/cancel` | 请求停止，返回实际状态 |
| GET | `/api/ai/conversations/{id}/events?after=N` | SSE，`id` 为事件游标 |
| GET | `/api/ai/knowledge/stats` | 索引统计 |
| GET | `/api/ai/knowledge/documents` | 索引文档列表 |
| POST | `/api/ai/knowledge/index` | 管理员发起协议文档索引 `{publish}` |
| POST | `/api/ai/knowledge/documents/{id}/publish` | 发布索引版本 |
| POST | `/api/ai/knowledge/documents/{id}/archive` | 下线索引 |
| GET | `/api/ai/admin/policies` | 查看授权策略（ADMIN） |
| POST | `/api/ai/admin/policies/reload` | 重载策略（ADMIN） |

写请求必须带 `X-AI-Client` 头（前端固定发送）。服务不开放 CORS，配合该头阻断普通表单型 CSRF。

### 事件类型

`message.user`、`run.queued`、`run.started`、`run.pause_requested`、`run.paused`、`run.cancel_requested`、`run.cancelled`、`tool.started`、`tool.finished`、`text.delta`、`run.warning`、`run.completed`、`run.failed`、`stream.ready`。

不向用户暴露模型隐藏推理；只展示执行进度、已授权工具名与最终结果。

## 6. Agent 与工具

| Agent | 可用工具 | 说明 |
|---|---|---|
| `team-leader` | `ask_protocol_agent`、`ask_order_agent` | 分派给成员，自己无业务数据权限 |
| `protocol-expert` | `search_protocol_knowledge` | 只检索已发布片段，缺页码时明确写「页码不可用」 |
| `order-service` | `get_order_detail`、`get_order_bills`、`get_device_detail`、`get_order_telemetry_summary` | 只读；结果带 `data_time/source/partial/warnings` |

工具白名单 = Agent 定义 ∩ 用户策略；构建 Toolkit 后对未授权工具执行 `removeTool`，调用前再逐次校验（用户、Agent、Tool、策略版本、预算）。

工具返回示例：

```json
{
  "data": { "...": "..." },
  "data_time": "2026-11-13T10:00:00",
  "source": "OPS",
  "partial": false,
  "warnings": []
}
```

工具结果、协议正文与用户输入都作为不可信数据处理：其中的指令不能改变权限或安全规则。

## 7. 协议知识库

```text
ops 协议文档（PDF/MD/TXT）
  → AI 拉取文件与元数据
  → PDFBox 按页抽取文本 / 纯文本直接解码
  → 章节识别 + 分块（默认 800 字，重叠 100）
  → 归一化 + 关键词（0x 命令字、英文标识符、数字、中文二元组）
  → 写入 ai_chunk（状态 READY_FOR_REVIEW 或 PUBLISHED）
  → 检索：协议/版本/发布状态过滤 → 关键字 like 召回 → 加权排序 → 引用
```

- 页码不可靠时返回空，界面显示「页码不可用」，不伪造。
- 没有足够证据时返回空结果，并在 `warnings` 里说明，由 Agent 拒答。
- `embedding` 配置存在但当前检索未使用向量；接入时按 `ai_index_version` 重建，禁止不同模型/维度混入同一索引。
- 索引由管理员触发；`publish=true` 表示管理员在本次操作中完成审核发布，操作记入审计。

## 8. 运行控制语义

| 操作 | 实现 | 限制 |
|---|---|---|
| 排队 | `ai_run` 持久化，按 `priority desc, queue_seq asc` 认领 | 同一会话只允许一个 RUNNING（部分唯一索引） |
| 优先处理 | 新 Run 提升优先级并请求暂停当前 Run | 当前 Run 在安全点暂停后释放执行权；之后由用户手动继续 |
| 暂停 | 事件循环每 700ms 检查状态，安全点（文本块结束 / 工具结果结束 / 模型调用结束）后释放执行权 | 模型流中途不强行恢复；外部请求已发出时不保证远端停止 |
| 继续 | `PAUSED → QUEUED`，保留原 `queue_seq` 以优先执行 | 重新执行原问题，尚无 AgentScope 检查点恢复 |
| 取消 | 执行中的任务进入 `CANCEL_REQUESTED`，执行器退出后才释放执行权 | 已发出的外部请求可能仍在远端运行 |
| 多实例 | 认领用 `status='QUEUED'` 条件更新；SSE 读取数据库已提交事件 | fencing 写入校验和故障接管尚未实现 |

### 模型未配置时

`IPE_MODEL_ENABLED=false`（默认）时不会调用大模型，Run 走确定性降级：

1. 从问题中提取订单号或 `0x` 命令字；
2. 直接调用对应只读工具；
3. 以 Markdown 返回工具事实与引用，并明确标注「未启用模型，以下为确定性工具结果」；
4. 提取不到参数时，引导用户提供订单号 / 协议编码与版本，不猜测。

这与既有排障链路的「确定性降级」口径一致。

## 9. 授权与安全

- 身份：请求 cookie 转发到 `ops /api/ops/auth/me` 校验，每个 `/api/ai/**` 请求都校验；不缓存身份校验结果。
- 角色策略：`ai_permission_policy`，多角色取并集；没有匹配策略 → 拒绝。
- 站点范围：`station_scope` 中 `*` 表示不限；空数组表示不能访问任何业务数据。默认种子给 `OPS` 配了 `*`，**生产环境必须改为具体站点 ID**：
  ```sql
  update ipe_ai.ai_permission_policy
  set station_scope = '[1,2]'::jsonb, policy_version = 'v2', updated_at = now()
  where role = 'OPS';
  ```
- 工具只读：不提供 shell、代码执行、任意 HTTP、写 SQL、设备控制；Agent 侧不启用文件系统与 Web 工具。
- 密钥只走环境变量；日志不记录 cookie、模型密钥与完整业务数据。

## 10. 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `IPE_AI_DB_URL` / `IPE_AI_DB_USER` / `IPE_AI_DB_PASSWORD` | 复用 `IPE_OPS_DB_*` | AI 库连接，schema 固定 `ipe_ai` |
| `IPE_OPS_BASE_URL` | `http://127.0.0.1:19082` | ops 地址 |
| `IPE_OPS_SERVICE_TOKEN` | `local-service-token` | 服务间令牌，必须与 ops 一致 |
| `IPE_MODEL_ENABLED` | `false` | 是否启用大模型 |
| `IPE_MODEL_BASE_URL` / `IPE_MODEL_API_KEY` / `IPE_MODEL_NAME` | 空 / 空 / `deepseek-chat` | OpenAI 兼容模型 |
| `IPE_MODEL_TIMEOUT_MS` | `60000` | 模型超时 |
| `IPE_AI_RUN_MAX_CONCURRENT` | `4` | 并发 Run 上限 |
| `IPE_AI_RUN_MAX_ITERS` | `6` | 单 Run 推理轮次上限 |
| `IPE_AI_RUN_MAX_TOOL_CALLS` | `12` | 单 Run 工具调用上限 |
| `IPE_AI_RUN_TIMEOUT_MS` | `120000` | 单 Run 超时 |
| `IPE_AI_RUN_MAX_CONTEXT_MESSAGES` | `20` | 传给模型的历史消息条数 |
| `IPE_AI_CHUNK_SIZE` / `IPE_AI_CHUNK_OVERLAP` | `800` / `100` | 分块参数 |
| `IPE_AI_TOP_K` / `IPE_AI_MIN_SCORE` | `6` / `1` | 检索片段数与最低分 |
| `IPE_EMBEDDING_*` | 关闭 | 预留，当前检索未使用 |

## 11. 本地验证步骤

前置：PostgreSQL 可用，账号有创建 `ipe_ai` schema 的权限；ops 已启动。

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\develop\apache-maven-3.8.8\bin\mvn.cmd' -B package
.\scripts\start-local.ps1
```

前端：`cd iot-protocol-expert-web; npm ci; npm run dev`，登录后进入「AI 助手」。

最小验收：

1. 用 `admin` 打开 `/assistant`，新建会话，提问「订单 202411130001 为什么停止？结束 SOC 多少？」——未配置模型时应返回订单/账单/遥测工具事实，且明确标注未启用模型。
2. 提问「0x3B 的停止原因定义」——应先执行 `POST /api/ai/knowledge/index` 建立协议索引，再返回片段与页码。
3. 用 `viewer` 登录：只能看到协议 Agent；直接请求 `/api/ai/conversations/{id}/runs` 调用订单工具应返回 403。
4. 把 `OPS` 角色的 `station_scope` 改为不含某订单所属站点，再用 `ops` 查询该订单：ops 内部接口应返回 403，工具返回「无权访问该订单或设备所属站点」。
5. 运行中点击「暂停」：应在安全点进入 `PAUSED`；「继续」后重新排队并完成。
6. 断网/刷新页面：SSE 重连携带 `cursor`，已产生事件不丢失、不重复。

> 注意：本机未连接真实 PostgreSQL，`ipe_ai` 迁移与端到端流程需在具备数据库的环境执行一次；单元测试已覆盖分块与确定性降级。

## 12. 可观测

- 日志：Boot 原生 Logstash JSON，字段含 `service.name`、`environment`、`trace_id/span_id`；不记录 cookie、模型密钥与完整业务数据。
- 指标（`/actuator/prometheus`，标签均为低基数）：
  - `ai_runs_created_total{agent}`：提交量
  - `ai_runs_finished_total{status}`：完成/失败/暂停/取消量
  - `ai_tool_calls_total{tool,decision}`：工具调用与拒绝量
- 审计：`ai_audit_log` 表结构已就绪，工具调用明细在 `ai_tool_call`（含授权决策、耗时、状态）。

## 13. 已知限制

1. 未接入 Nacos Skill Registry、Higress 与独立 MCP Server；工具为进程内实现，接口已按拦截器/白名单方式组织，后续可替换为 MCP Client。
   测试环境使用 Nacos 3.3.0 + Higress 2.2.6；已有测试记录显示 Higress 的 `nacos2` 动态发现未生效，使用 `static` 服务来源可转发。后续接入应在目标环境重新验证版本、发现、路由与认证。
2. SSE 按数据库游标轮询以支持跨实例事件，尚需连接数、延迟和数据库负载压测；跨实例 fencing 写入校验尚未实现。
3. 上下文压缩（`ai_context_summary`）只建表，当前按最近 N 条消息截断。
4. 检索为关键字方案；pgvector 迁移已就绪但未参与排序，需在有扩展的环境做评测后启用。
5. PDF 表格、图纸与扫描件抽取质量有限，低质量文档应人工校对后再发布。
6. 未实现 Skill 加载、可视化编排、写操作与设备控制（属二期）。
7. 未实现缓存与事件保留清理：一期默认不缓存业务结果与最终答案，`ai_run_event` 需按保留期另行清理。
8. 未做模型侧 token/费用预算统计（`usage_json` 目前记录工具次数与耗时）。
9. 已排队任务执行前及每次工具调用前重新校验用户授权；业务接口会重新读取用户站点范围，并与 AI 策略范围取交集。运营端的受限站点账号目前不能访问运营数据接口。
10. 暂停并非框架检查点恢复，恢复时会重新执行。取消时执行权保留至执行器退出，但进程异常退出后的租约过期接管和 fencing 尚未实现。上述能力不能作为可靠的生产调度保证。
11. SSE 跨实例重放尚需完整验证；知识检索目前没有文档级权限过滤或真实向量检索。
12. 知识管理入口位于运营 Web；当前索引结构不支持新旧版本并存，已发布文档变化时不会自动覆盖旧索引，需要先下线并重建。

## 14. 后续接入顺序建议

1. 在目标环境执行 `ipe_ai` 迁移，跑通第 11 节验收。
2. 用真实协议文档评测检索召回与引用正确性，确定 chunk 参数与最低分。
3. 接入 Nacos（Skill 版本与受控读取）与 Higress（MCP 路由），将 `ToolGuard` 前置到 MCP Client。
4. 补 fencing token 和检查点恢复，做多实例抢占与恢复演练。
