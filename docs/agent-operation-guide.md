# Agent 配置、运行与配额操作说明

本说明对应 2026-11-17 至 2026-11-18 的实际验证。最新一轮真实 PostgreSQL 配额回归为 `QuotaServicePostgresTest` 20/20 通过；完整验收结果及未完成项见 [流程验收报告](agent-full-flow-report-2026-11-17.md)。

## 先从哪里进入

本地前端入口：<http://127.0.0.1:5173>。

| 要做什么 | 菜单 / 页面 | 说明 |
| --- | --- | --- |
| 配置模型、MCP、Skill、Agent | AI 能力管理 `/ai-admin` | 管理员使用；保存草稿和发布版本是两步 |
| 和已发布 Agent 对话 | AI 助手 `/assistant` | 普通用户也可使用，但需要 Agent 授权及个人模型额度 |
| 查看自己的限额和消费记录 | 模型额度 `/usage` | 普通用户查看当前额度策略、冻结/剩余单位及最近模型调用 |
| 分配用户或服务账号额度 | 模型额度 `/usage` → 额度分配与管理 | 具备 `quota:grant` 的管理员可填写主体、模式和上限并查看目标主体快照 |
| 配置用户角色和站点范围 | 用户管理 `/users` | 角色权限、Agent 策略、业务数据权限、模型额度分别生效 |

当前“Agent 编排”指模型、系统提示、Skill、MCP 工具白名单和执行预算的组合，由 ReAct Agent 执行。当前没有可视化 DAG 编排器。

## 直接体验已经准备好的样例

在 AI 助手中新建会话，选择“流程验收演示 Agent”，其 key 为 `<agent-key>`，当前可用默认版本为 `1.1.1`。

1. 输入 `基础回执`，预期回复 `FLOW_OK`。
2. 输入：`请先读取 Skill 的参考资料 references/checklist.md，再查询协议文档数量。只回答文档条数和参考文件中的验收标记。`
3. 在执行详情检查 `load_skill`、`read_skill_resource` 和 `mcp_<agent-key>__list_documents`。应逐个检查工具状态，不能只看 Run 的 COMPLETED。
4. 第二条提问需要管理员身份：协议文档工具要求 `ai:admin`，普通操作员返回 403 是业务权限生效。
5. 打开“模型额度”，检查本账号的消费记录。额度属于当前登录用户，管理员有管理权限并不意味着自动拥有个人模型额度。

旧会话固定创建时的 Agent 发布版本。发布新版本后，需新建会话体验新默认版本；旧会话不会自动切换。

## 从零配置的正确顺序

### 1. 准备可用模型和额度

模型网关要先登记供应商和逻辑模型，然后才能在 Control 的“模型配置”中选择对应逻辑 key。仅填写 `.env.local` 或在页面保存模型草稿，不会自动生成模型网关目录。供应商登记步骤见仓库 README“本地运行”。供应商密钥只放模型网关。

普通 Agent 运行消耗 `USER` 主体额度；模型测试使用 `SERVICE_ACCOUNT / ipe-evaluator` 额度。两者需要分别配置。

### 2. 发布 MCP

在“AI 能力管理 → MCP 服务”中创建服务，填写实际远程 MCP / Higress 入口和凭据引用，执行测试并审核工具风险，然后发布版本。

调用 OPS 业务工具时，“调用鉴权方式”选择“OPS 业务工具（校验用户身份）”，并绑定上下文凭据。该配置会冻结到发布版本中。普通远程 MCP 使用普通模式。

Higress 提供 REST-to-MCP 和路由；Control 管理组件、发布版本及权限。业务接口仍会核验用户权限与站点范围，选择 OPS 模式不会绕过这些检查。

### 3. 发布 Skill

在“Skill 编辑”维护 `SKILL.md`、`references/`、`scripts/` 或 `assets/` 文本文件，先校验，再发布。
`SKILL.md` 的 frontmatter name 应与 Skill key 一致；脚本资源只读、不执行。Agent 绑定的是具体 Skill 发布版本，
编辑草稿不会改写已经发布的版本。

### 4. 构建和发布 Agent

在“Agent 构建”配置模型 profile、提示词、MCP 工具白名单、Skill 发布版本和预算。保存后执行“校验”，校验通过再“发布”；到“发布与注册”确认状态变为 PUBLISHED。

本次样例预算为最大 6 轮迭代、6 次工具调用、120 秒超时。模型输出上限与用户配额是不同限制；预占还包含输入上下文，多轮工具调用会产生多次模型消费。

## 运行观测与审计证据

Agent 的 Trace、数据集、实验和评分统一在 Langfuse 中查看和管理，本系统不再提供自研评测页面或 API。

Runtime 仍会从工具调用事件中收集参数与结果，Control 将其写入 `ai_tool_call.args_summary` 和 `result_summary`，用于运行历史与排障：

- 每次工具调用的参数整体和结果整体分别最多保留 8000 字符，结构为 `{"text":"...","truncated":true|false}`。达到上限时保留前 8000 字符并标记 `truncated=true`。
- 授权决策由 Runtime 的结构化元数据显式传递：成功为 `ALLOW`，明确拒绝为 `DENIED`，授权未决或执行前中断保持 `PENDING`。`deny_reason` 单独脱敏并限制在数据库列宽 128 字符内。
- 写入前会脱敏常见凭据字段和格式，包括 password、secret、API key、access/refresh token、authorization、cookie、Bearer 值、`sk-*`、URL 内嵌凭据和私钥块。这是常见凭据防护，不保证清除所有业务敏感内容；8000 字符是审计容量边界，也不是安全边界。
- `IPE_RUNTIME_OTEL_ENABLED` 控制 AgentScope 元数据 Span 是否启用，未设置时跟随 `IPE_OTLP_ENABLED`；`IPE_RUNTIME_OTEL_CONTENT_ENABLED` 控制消息和工具正文是否进入 Trace。工具审计证据与 Trace 内容开关相互独立。

## 用户额度怎么分配

具备 `quota:grant` 的管理员可打开“模型额度”页面，在下方“额度分配与管理”表单操作：

1. 选择主体类型。普通 Agent 用户选择 `USER`；模型测试使用 `SERVICE_ACCOUNT`。
2. 填写 `subjectId`，例如用户管理中的用户 ID 或 `ipe-evaluator`。
3. 选择 `TOTAL` 或 `ON_FIRST_USE_WINDOW`。窗口模式还必须填写有效窗口秒数。
4. 填写额度上限、可选策略标识和原因，点击“保存额度策略”。表单会展示目标主体的当前上限、已用、冻结、剩余和待对账状态。

表单使用页面生成的幂等键；连续修改表单会重新生成键。需要脚本集成或排查接口时，也可调用下面的管理员 API：

```javascript
const grantIdempotencyKey = crypto.randomUUID()
await fetch('/api/control/v1/admin/quota-grants', {
  method: 'POST',
  credentials: 'same-origin',
  headers: {
    'Content-Type': 'application/json',
    'X-AI-Client': '1',
    'Idempotency-Key': grantIdempotencyKey
  },
  body: JSON.stringify({
    scopeKind: 'USER',
    subjectId: '替换为用户ID',
    mode: 'TOTAL',
    limitUnits: 100000,
    policyKey: '替换为该用户固定的总量策略标识',
    reason: '分配测试额度',
    actorId: 'admin'
  })
}).then(async response => ({ status: response.status, body: await response.json() }))
```

`/api/ai/v1`、`/api/control/v1` 与 `/api/model-gateway/v1` 的普通 JSON 响应统一为
`{ code, msg, data }`。判断成功使用 `code === 'OK'`，业务字段读取 `body.data`；OpenAI 兼容接口、SSE
事件流和 Higress REST-to-MCP 工具入口不套用该信封。

- `TOTAL` 的 `limitUnits` 是额度上限，调整同一策略的上限应复用 `policyKey`；不是“增加这些 Token”。
- 首次使用窗口使用 `mode: 'ON_FIRST_USE_WINDOW'` 并提供 `durationSeconds`，如 3600。第一次实际调用激活窗口，后续受窗口内额度约束；到期后由后续请求开启新窗口。
- 同时存在总量和窗口策略时，两项都约束同一笔消费，不能把两个余额相加。
- 重试同一个授权请求应复用原 `Idempotency-Key`，且主体、模式、上限、窗口时长和原因必须完全一致；同一 key 绑定不同内容会返回冲突。示例先把 key 保存在 `grantIdempotencyKey`，请求失败后重试时不要重新执行生成 key 的语句。新调整使用新 key，不要在不同用户或不同请求之间复用同一幂等 key。
- 给模型测试分配额度时使用 `scopeKind: 'SERVICE_ACCOUNT'`、`subjectId: 'ipe-evaluator'`，并使用独立策略标识。
- `heldUnits` 是预占，正常结束后结算并释放剩余预占；未知上游结果需要对账，不能直接当作未消费。
- 已派发调用超过预留保留期仍未结算时，系统会将其转为 `UNCERTAIN` 并保留冻结额度，同时增加 `pendingReconciliation`；管理员核实供应商确实没有用量后，调用 `POST /api/control/v1/admin/calls/{reservationId}/reconcile-release`。仅 `UNCERTAIN` 预约可走该释放对账。
- 调整 `TOTAL` 额度会在同一事务内同步当前额度桶的上限和策略版本，补额请求提交后紧接着查询即可看到新的剩余额度。
- 在同一策略上把 `TOTAL` 切换为 `ON_FIRST_USE_WINDOW` 或反向切换时，系统会清空当前桶引用；下一次请求创建新模式的新桶。旧桶保留为历史账本，不会被新模式复用。
- 并发调整同一策略时，网关用 advisory lock 串行分配 revision。操作者无需自行做版本比较，但必须保证幂等键与请求内容的绑定关系稳定。
- 每次模型调用都关联逻辑调用 ID、attempt 和 Run ID；Runtime 使用 `X-PEP-Logical-Call-Id`、`X-PEP-Attempt-No`、`X-PEP-Run-Id`，Control 的模型测试调用发送前两项头，并由短期运行凭据携带 Run ID。网关从运行凭据读取记账 Run ID。相同逻辑调用与 attempt 的重放会被网关拒绝；预占在派发前已失效时，网关也会阻止请求到达供应商。

## 常见卡点

| 现象 | 优先检查 |
| --- | --- |
| 服务健康但不能对话 | 模型网关目录、共享服务令牌、个人额度、Agent 发布状态 |
| Agent 列表为空或调用被拒 | 当前用户的 Agent 使用策略与角色名称 |
| 工具 401 | MCP 的上下文模式、凭据引用及 OPS 共享上下文配置 |
| 工具 403 | 业务权限和站点范围；文档工具要求 `ai:admin` |
| 额度不足但尚有少量余额 | 可用额度是否足以覆盖输入上下文及输出预算的预占 |
| 新提示词没有生效 | 是否只保存了草稿、是否发布成功、是否仍使用旧会话 |
| Langfuse 中没有 Agent Trace | 检查 Langfuse OTLP 配置、Runtime AgentScope 埋点开关、网络可达性和 Trace 保留期 |

Trace 正文默认不进入 Tempo 或 Langfuse，需要排障时单独开启并评估数据治理；工具审计证据本身受“每次工具调用的参数整体、结果整体各 8000 字符”和常见凭据脱敏边界约束。本轮没有验证长时间窗口的自然续窗、供应商断流/进程崩溃的完整恢复矩阵，也没有执行设备协议和模拟器业务的全量回归。额度页返回的是跨多次查询的当前快照，不是数据库可重复读快照；并发结算期间应结合用量记录判断，不能把单次快照当作时点账本。
