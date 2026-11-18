# Agent 全流程验收与配额修复报告

日期：2026-11-17 至 2026-11-18。范围：本地项目的 Agent 组件配置与发布、真实执行、评估、打分、用户模型额度及权限隔离。操作说明见 [Agent 使用说明](agent-operation-guide.md)。

> 2026-11-18 更新：Tempo `3200` 与 Loki `3100` 已从服务器回环地址改绑 Tailscale 网卡，本机不再需要 SSH 端口转发；当前配置见 [可观测性](observability.md)。本文中“仅回环/需要受控代理”的描述保留为当时验收记录。

## 结论

2026-11-18 已完成当前代码的最终复验。11 个 Maven reactor 模块在 JDK 21 下完整构建成功；协议本地全流程、真实模型 Agent、MCP/Skill 工具、规则评分、LLM Judge、人工评分和用户级额度均按实际服务执行。当前实验 `<current-experiment-id>` 为 2/2 完成、0 失败，工具调用 3/3 成功，规则指标为 1，两条结果的 `answer_quality` 均为 1.0。基础 Run 为 `<baseline-run-id>`，工具 Run 为 `<tool-run-id>`。

额度首次在未分配时被 `QUOTA_EXHAUSTED` 拦截，未产生模型消费；补足额度后，基础和工具 Run 均成功。Trace 与服务端日志也已复验：Tempo 可在页面的“加载链路”中展开当前工具题 Trace `<current-trace-id>`，共 146 个 Span；Loki 中能查到 Control、Runtime、Model Gateway 的日志流。服务器 `3200/3100` 已从回环地址改绑 Tailscale 接口，本机直连通过，不再需要 SSH 端口转发。

OCPP 在最新 JAR 上重新执行了真实闭环：远程启动创建订单，设备上报 `StartTransaction` 后绑定整数交易号，计量上报后远程停止，最终为单一订单 `SETTLED` 且只生成一张账单；`pendingOcppStops=0`。本轮修复了“远程启动超时把已充电订单误标失败后又建第二单”的状态竞态，并为交易号、设备枪号增加数据库唯一约束。

最新冷启动复验还发现 Gateway 到 OPS 的 BootNotification 内部调用只有 5 秒超时，实际耗时接近边界；默认值已提高到 15 秒。重启后首次 Boot 在约 4.9 秒完成，Simulator 直接进入 `connected=true / loggedIn=true`，无需等待重连兜底。

## 架构与组件边界

- 业务模块保留 `iot-protocol-expert-ops`、`iot-protocol-expert-agent-runtime`、`iot-protocol-expert-agent-control`、`iot-protocol-expert-protocol-gateway`、`iot-protocol-expert-simulator`，前端统一为 `iot-protocol-expert-web`。
- `iot-protocol-expert-model-gateway` 继续作为独立模型调用与用户额度计量边界保留；它不是 Agent 管理平台的一部分，但承接供应商协议、Token 计量、预占结算和对账。
- 四个 `shared/` 模块只承载设备编解码、观测、Runtime 契约和模型契约，不保存业务状态。
- Nacos 只负责服务注册与发现；MCP、Skill 的登记、审批、版本、发布和运行时快照由 Control 管理。REST-to-MCP 路由交给 Higress，应用不再把 Nacos 当作 MCP/Skill 的版本事实源。
- 验收期间经 Nacos 3 管理 API 曾观察到 7 个在线服务：OPS、Control、Runtime，以及 Agent `<agent-key>` 的 `1.0.0`、`1.0.1`、`1.1.0`、`1.1.1` 四个发布投影。旧服务列表接口返回 501，改用 Nacos 3 接口后完成可见性验证。临时服务停止后注册项已消失，不能把当时快照当作持续在线状态。

## 已完成的验证

| 功能 | 实际动作 | 结果 |
| --- | --- | --- |
| 服务启动 | 启动 OPS、Control、模型网关、Runtime、协议网关和模拟器，复用前端 | 已就绪；修复过程中重建并重启相关服务 |
| 模型接入 | 登记独立供应商与逻辑模型，配置 Control profile，发起真实模型测试 | 通过；供应商秘密仅通过现有本地配置读取 |
| MCP 治理 | 远程工具发现、风险审核、凭据引用、发布 `1.0.1` | 通过；使用只读 `list_documents` |
| Skill 治理 | 创建 `SKILL.md` 及 `references/checklist.md`，校验并发布 | 通过；运行中实际读取参考文件 |
| Agent 编排 | 绑定模型、提示词、MCP、Skill 和预算，校验并发布 | 通过；发布版本及依赖保持不可变 |
| 真实工具链 | 管理员提问后实际调用 Skill 加载、文件读取和 MCP 文档查询 | 三类工具全部成功，返回验收标记及文档数量 |
| 普通用户业务权限 | 操作员请求文档工具 | 正确返回 403；未放宽业务权限 |
| 运行幂等 | 重复提交同一会话的相同幂等请求 | 返回同一 Run |
| 用户总量额度 | 仅分配 1 单位额度后提交请求 | 被拦截；未新增已结算模型消费 |
| 补额恢复 | 调整同一用户策略后再运行 | 通过；产生实际用量记录 |
| 配额错误提示 | 修复后重新制造低额度场景 | 返回 `QUOTA_TOTAL_EXHAUSTED` 和明确补额提示 |
| 首次使用窗口 | 单独配置 3600 秒窗口，检查调用前后状态 | NOT_ACTIVATED → ACTIVE，窗口起止相差 3600 秒 |
| 预占结算 | 检查测试用户 B 的窗口调用和消费记录 | 本次消费 792 单位；总量及窗口分别计量，held 均为 0 |
| 主体隔离 | B 查询 A 的会话、伪造额度查询 subjectId | 会话 404；额度主体仍强制为当前登录用户 |
| 管理权限 | 操作员访问评估管理和额度授权 API | 均返回 403 |
| 离线能力评估 | 实验 `<earlier-experiment-id>`：固定回执、Skill + MCP 问答 | 2/2 完成，断言和工具执行均通过，两条 `answer_quality` 均为 1.0 |
| Trace / Span | 查看工具题结果 `<tool-result-id>` 的 Trace `<current-trace-id>` | 146 个 Span；链路为 `agent.run -> invoke_agent -> chat/execute_tool` |
| Judge 证据 | 对上述工具题执行真实 LLM Judge | 评分 1.0；证据包含 `data=[]`、`partial=false`、`warnings=[]` 和工具结果中的 `FLOW_OK` |
| 人工评分 | 通过 API 将同一指标先打 0.8，再改为 0.9；尝试 1.2 | 0.9 为当时有效分；0.8 保留为 SUPERSEDED；1.2 返回 400 |
| 版本回归 | 发布故意改变回执的 `1.1.0`，对同一数据集评估 | 正确发现断言和精确匹配退化 |
| 会话版本固定 | 发布新默认版后继续原会话 | 原会话仍运行 `1.0.1` |
| 可用版本恢复 | 发布恢复正常回执的 `1.1.1` | 已成为测试 Agent 的当前默认版本 |
| 取消实验 | 启动实验并请求取消 | 最终 CANCELLED |
| 浏览器核验 | 登录管理员，查看实验、结果、146 Span 链路与评分抽屉，并为 `execute_tool/read_skill_resource`（Span `<earlier-skill-span-id>`）提交人工评分 | 页面显示实际输入输出、Token、规则指标、Judge 证据及 Span；人工评分 `0.92` 保存成功，重新加载仍可见 |
| 重启后在线冒烟 | 六个后端服务全部重新启动后，重新授权测试用户并执行固定回执 Agent | Run 最终 `COMPLETED`；用户额度实际消费 792，新增 1 条模型消费记录 |
| 当前 Agent 基线 | 使用 `<agent-key> / 1.1.1` 执行基础回执 | Run `<baseline-run-id>` 完成，回答 `FLOW_OK` |
| 当前工具链 | 实际调用 `load_skill`、Higress MCP `list_documents`、`read_skill_resource` | Run `<tool-run-id>` 完成，3/3 工具成功，参数与结果审计证据可见 |
| 当前额度拦截 | 在无可用额度时先发起请求，再补足 80,000 | Run `<quota-blocked-run-id>` 返回 `QUOTA_EXHAUSTED` 且无模型消费；补额后恢复 |
| 当前离线评估 | 数据集 `<current-dataset-id>`、实验 `<current-experiment-id>` | 2/2 完成、0 失败；工具调用 3/3，授权合规 2/2，两条 Judge 分数均为 1.0 |
| OCPP 真实闭环 | 模拟器插枪、平台远程启动、`StartTransaction`、`MeterValues`、远程停止、`StopTransaction` | 单一订单最终 `SETTLED`，有交易号和账单，`pendingOcppStops=0`，无重复活动订单 |
| 协议本地全流程 | `python scripts/verify-spring-local.py` | 登录、费率同步、跨时段计费、离线补传、并充分别出账、请求/ACK 父子 Span 均通过 |
| 协议 Trace / OTLP | 检查 OPS、Gateway、Simulator、JDBC Span 与本地 OTLP 接收器 | OPS 36 Span、Gateway 7 Span、Simulator 2 Span、JDBC 21 Span；收到 traces 316、metrics 192、logs 87 批次 |
| Prometheus 指标 | 检查 HTTP、TCP、JVM 指标 | 三类指标均存在；Simulator 已补齐 Prometheus registry 依赖 |
| Tempo / Loki | 通过 Tailscale 直连查询健康状态和实际数据 | 两者 ready；Tempo 当前 Trace 返回 146 Span；Loki 返回 Control、Runtime、Model Gateway 日志流 |
| Nacos 服务可见性 | 使用 Nacos 3 登录与服务管理 API | 7 个注册服务均可见；认证成功，旧接口 501 不再作为验收入口 |
| 服务器资源复查 | 按最新资源清单只读检查 SSH、Nacos、Higress、数据库端口和可观测性入口 | 所列 TCP 端口均可达；Grafana、Prometheus 健康，Tempo / Loki ready；端口改绑 Tailscale 后，本机直连和实际数据查询通过 |
| 浏览器最终复核 | 在评估结果抽屉点击“加载链路”并展开 Span | 实际显示评分、Trace ID、Span 树和属性，不再出现“服务暂时不可用” |

## 版本对比实测

同一数据集、各 2 条用例。下表保留确定性版本回归结果；Judge 质量分在更新工具证据后另行复验。

| 指标 | 基线 `1.0.1` | 故意退化版 `1.1.0` |
| --- | --- | --- |
| 执行成功率 | 100% | 100% |
| 断言通过率 | 100% | 50% |
| 固定回执精确匹配 | 100% | 0% |
| 工具执行成功率 | 100% | 100% |

最终 Judge 复验使用实验 `<current-experiment-id>`，2/2 结果均为 `answer_quality=1.0`。工具题 Judge 能从持久化审计证据中读到参数、结果和 `FLOW_OK`，不再只依赖工具成功状态。该结果说明当前固定版本上的语义判断可通过，不代表更换模型、Prompt 或工具后仍能稳定得到相同分数。

恢复版 `1.1.1` 用于确认发布链恢复正常，并已成为测试 Agent 的当前默认版本；已有会话仍固定其创建时的 `1.0.1`。本表记录的是基线 `1.0.1` 与故意退化版 `1.1.0` 的同数据集对比，不把恢复版的发布成功描述成另一组性能分数。

## 已修复的实际阻塞

| 修改位置 | 问题和修复 |
| --- | --- |
| Control `PolicyService` | IAM 当前角色与历史 ADMIN/OPS 策略不匹配；兼容 PLATFORM_ADMIN/OPERATOR，保持原工具及站点范围 |
| 本地启动公共脚本 | 单服务重启仅监听回环地址，但 Nacos 发布主机地址；根据现有发现配置选择监听地址 |
| Runtime `ControlClient` | Skill 读取仍尝试通过旧虚拟服务名发现；改为从签名 Control API 读取 |
| 模型网关 `ChatCompletionsController` | 通配返回类型使 MVC 将 StreamingResponseBody 当作对象序列化，真实流式运行报错；改为明确的流式响应类型，保留非流式 JSON |
| Control `McpService` 和 Web MCP 表单 | 自定义 OPS MCP 的上下文模式没有冻结发布；补齐配置校验、摘要、发布元数据和页面选择 |
| Control `EvaluationScorer` / `ModelProfileService` | Judge 证据可能为文字导致解析失败，输出格式也不稳定；明确紧凑 JSON 结构并兼容证据类型，非法分数仍明确失败 |
| Runtime `AgentExecutionService` / `ToolInvocationEvidence` | 工具参数和结果此前未形成可供 Judge 使用的持久证据；现从 AgentScope 增量事件收集，每次工具调用的参数整体与结果整体分别最多保留 8000 字符，写入前脱敏，并通过 `tool.arguments`、`tool.completed` / `tool.failed` 落到 `ai_tool_call.args_summary/result_summary` |
| Runtime `AgentExecutionService` | 配额 429 被折叠为通用执行失败；只透传已知配额错误码和固定中文提示，不暴露上游原始内容 |
| 模型网关 `QuotaRepository` / `QuotaService` | `DISPATCHED` 预占超过保留期后永不收敛；按创建时间和 `reservation-ttl-seconds` 扫描，将预约、调用和 charge 转为 `UNCERTAIN`，保留冻结额度并增加 `pendingReconciliation`。管理员核实供应商没有用量后调用 `POST /api/control/v1/admin/calls/{reservationId}/reconcile-release` 释放 |
| 模型网关 `QuotaService.grant()` | `TOTAL` 补额只更新策略版本、额度桶仍按旧上限计算；现于同一事务内锁定账户，同步当前桶的 `limit_units` 和 `policy_revision`，提交后紧接着查询快照即使用新上限。并发授予用 advisory lock 串行分配策略 revision；同一 `Idempotency-Key` 只允许完全相同的请求内容重放，内容变化返回冲突 |
| 模型网关配额锁与结算 | 所有同时涉及账户和 bucket 的路径保持“账户 → bucket”的先后关系；结算、释放等路径先锁 reservation/charge，再按 PostgreSQL UUID 无符号顺序锁定账户，最后处理 bucket。Java 端用规范的 UUID 字符串顺序，避免与 `UUID.compareTo` 的有符号顺序不一致。`WINDOW` / `TOTAL` 切换时先清空 `current_bucket_id`，下一次请求创建与新模式匹配的新桶，旧桶不会被复用。预约释放后才到达的结算仍写 `model_usage` 和 `LATE_SETTLED` 账本，不恢复已释放的 hold |
| Runtime、Control 与模型网关的调用身份 | Runtime 中间件为每次模型调用写入 `X-PEP-Logical-Call-Id`、`X-PEP-Attempt-No` 和 `X-PEP-Run-Id`；Control 的模型测试调用写入逻辑调用 ID 与 attempt，并由短期运行凭据携带 Run ID，网关记账使用该凭据中的 Run ID。网关按逻辑调用 ID 与 attempt 查找记录，并用请求 SHA-256 拒绝不同内容的重放；`markDispatched=false` 时在供应商请求前返回冲突，阻止重复派发 |
| 模型网关 `V2__quota_sweep_indexes.sql` | 为按 `dispatch_deadline` 扫描 `HELD` 预约、按 `created_at` 扫描 `DISPATCHED` 预约增加两条 partial index，减少对账扫描范围 |
| OCPP 订单状态竞态 | `remoteStart` 仅在设备明确 `Rejected` 时失败订单；`Accepted`、超时、断连和未知结果保持 `PENDING`。Gateway 命令结果增加结构化 `outcome`。订单绑定、启动、计量、停止、失败、结算和过期转换统一使用数据库行锁，已结算订单不会被迟到计量覆盖 |
| OCPP Boot 冷启动超时 | Gateway 调用 OPS 的默认超时由 5000 ms 调整为 15000 ms。最新冷启动首次 Boot 约 4.9 秒即通过，避免正常建连被 5 秒边界误判为 `CALLERROR` |
| OCPP 唯一性与过期扫描 | 新增 `V4__ocpp_transaction_uniqueness.sql`、`V5__active_charge_order_uniqueness.sql`，分别约束设备内交易号唯一和每把枪最多一张 `PENDING/CHARGING` 订单；启动时迁移已在 `pep_platform` 成功应用。过期扫描改为直接查询超时的 `PENDING` 订单，不再只检查最新 200 张订单 |
| OCPP 报文留存与上下文 | 帧留存改用 core 2 / max 4 / queue 256 的专用执行器，队列拒绝不会逃到协议线程；补上直接 Tracer Span、Observation 和 `traceId/spanId` 的跨线程传播，OCPP 帧流水会记录当前 trace ID |
| 模型目录与管理页面 | Control 增加模型供应商和逻辑模型查询/登记代理，Web 增加供应商登记与 `/usage` 管理员额度表单；模型 key 在网关与 Control 统一为 2–40 位小写字母、数字或短横线 |

## 仍未完成、需要继续处理

| 优先级 | 项目 | 当前证据 / 下一步 |
| --- | --- | --- |
| 低 | 历史版本评估和高级断言的页面入口 | 页面只选择当前发布版本；历史版本及 requiredTools 可经 API 使用。本轮通过 API 验证，尚未扩展表单。 |
| 中 | Judge 上下文上限 | 单次工具参数/结果已分别限制为 8000 字符，但整个 Judge 输入没有统一的全局 Token/字符预算；大量工具调用时仍可能超过模型上下文。 |
| 低 | 审计证据边界 | 每次工具调用的参数整体、结果整体分别最多保留 8000 字符并做常见凭据脱敏；超限时标记 `truncated=true`。脱敏覆盖常见密钥字段和格式，不保证删除所有业务敏感内容，也不能代替 Trace 存储治理。 |
| 低 | 中断工具审计 | 超时、取消或事件流异常时，Runtime 会为尚未收到结果的事件补发 `tool.failed`，Control 对无法确定授权的调用保留 `PENDING`；已有单元测试覆盖终态映射，但尚未做真实模型的长时间中断矩阵。 |
| 低 | 额度快照一致性 | `/me/quota` 由多次普通查询组成，不是数据库可重复读快照；并发结算或补额期间可能读到跨提交的字段组合。它适合诊断当前额度，不应被当作某一时点的账本副本。 |
| 后续 | 服务器部署 | 本轮只操作本机完整栈和现有中间件做只读核验，没有把项目应用部署到服务器，也没有变更服务器认证、数据源或防火墙配置。 |
| 后续 | 凭据治理 | `基本信息/服务器资源.md` 含明文账号与内网连接信息，不得提交或外发；用于排障的开发环境凭据应按实际暴露范围轮换，并迁入受控密码库。 |
| 中 | OCPP 多实例并发矩阵 | 单桩真实闭环和状态转换单测已通过；尚未用多实例、多枪、长时间压力验证 `Start/Stop/Meter/StatusNotification` 的所有交叉提交顺序。迟到 `Available` 与活动订单之间的表示仍可能短暂不一致。 |
| 后续 | 扩展场景 | 未做长时间窗口自然续窗、多用户并发压测、供应商断流与进程崩溃的完整恢复矩阵，也未对设备协议和模拟器业务执行全量回归。 |

过期预占收敛复测中，3 笔故障调用在网关重启后被调度器自然转为 `UNCERTAIN`，`pendingReconciliation=3`。逐笔核对确认没有 `model_usage` 后，通过管理员对账接口释放；用户 A 的两条策略 `heldUnits` 均从 11,478 降为 0，待对账数归零。没有直接修改账本表。

补额快照复测中，用户 B 的总额度从 80,000 调到 81,000，补额请求提交后紧接着查询立即返回 `usedUnits=2,373`、`remainingUnits=78,627`；恢复到 80,000 后立即返回 `remainingUnits=77,627`，不再等待下一次预占。

## 验证与交付状态

- `python scripts/verify-coding-guidelines.py`：462 个文件，0 项违规。
- JDK 21 下最终 `-Pcoding-guidelines clean verify`：11 个 reactor 模块全部 `SUCCESS`，总耗时约 2 分钟；279 项测试、0 失败、0 错误、30 项按环境条件跳过。编码规范扫描 462 个文件、0 项违规。
- 模块级结果：Device Codec 19、Runtime Contract 12、OPS 26（3 项跳过）、Control 92（3 项跳过）、Protocol Gateway 17、Simulator 28（3 项跳过）、Model Gateway 29（21 项跳过）、Runtime 56。
- 模型网关常规测试包含 MVC 异步 SSE/JSON 回归、逻辑调用重放拒绝、失效预占阻断派发和 UUID 无符号排序边界。
- 真实 PostgreSQL 配额测试：在临时数据库执行 `QuotaServicePostgresTest`，最新一轮 20/20 通过。覆盖首次使用窗口、并发预占、总量与窗口原子回滚、重复结算、释放、跨窗迟到结算、100 并发建窗、授予重放与内容冲突、WINDOW/TOTAL 切换、TOTAL 补额同步、过期 `DISPATCHED` 转 `UNCERTAIN`、管理员对账释放、释放后迟到结算、并发授予 revision 分配和同一幂等键并发重放。
- Runtime OTel 元数据埋点由 `IPE_RUNTIME_OTEL_ENABLED` 控制；未显式设置时跟随 `IPE_OTLP_ENABLED`（两者都未设置时默认开启）。本地公共脚本不再强制写入 `false`，显式环境变量仍可单独关闭 Runtime AgentScope Span。
- Trace 内容属性与评估审计证据使用两条独立策略：前者仅在 `IPE_RUNTIME_OTEL_CONTENT_ENABLED=true` 时写入 Tempo；后者由 Runtime 工具事件持久化到 Control 数据库，参数整体和结果整体各上限 8000 字符并脱敏，供 Judge 与人工复核读取。
- `V2__quota_sweep_indexes.sql` 的 partial index 已做迁移文件静态核对；配额测试不直接校验线上索引存在性。
- Web：`npm run build` 通过。
- 数据库配额测试需显式设置 `PEP_METERING_TEST=true`，且会清空 `pep_metering` 的测试数据，因此只在本机合成验收库执行；执行前后均确认没有连接业务库。
- 原有管理员账号及额度未修改；新增测试用户、组件和实验保留供复现。
- 真实端点、密钥、测试账号密码、Cookie、原始日志及私有状态保留在 Git 忽略目录；本报告不包含这些内容。
- 本轮文档与代码作为同一次修复提交交付；临时脚本、截图、原始 Trace、Cookie 和本地配置保留在 GitHub 忽略目录。

## 可追溯的合成验收对象

以下均为本轮新建的测试对象标识，不包含业务文档正文。

| 对象 | 标识 |
| --- | --- |
| Agent / MCP / Skill key | `<agent-key>` |
| 数据集 | `<baseline-dataset-id>` |
| 修复后基线实验 | `<baseline-experiment-id>` |
| 退化版本对比实验 | `<regressed-experiment-id>` |
| 人工评分结果 | `<human-score-result-id>` |
| 取消实验 | `<cancellation-experiment-id>` |
| 固定旧版本的续跑 | `<pinned-version-run-id>` |
| 恢复默认版本发布记录 | `<default-release-id>` |
| 较早 Trace/Judge 专项复验实验 | `<earlier-experiment-id>` |
| 较早工具题结果 | `<earlier-tool-result-id>` |
| 较早工具题 Trace | `<earlier-trace-id>`（146 Span） |
| `execute_tool/read_skill_resource` Span | `<earlier-skill-span-id>`（人工评分 0.92） |
| 较早 2026-11-18 数据集 / 实验 | `<earlier-dataset-id>` / `<previous-experiment-id>` |
| 当前数据集 | `<current-dataset-id>` |
| 当前实验 | `<current-experiment-id>`（2/2，0 失败） |
| 当前基础 Run | `<baseline-run-id>` |
| 当前工具 Run | `<tool-run-id>`（3/3 工具成功） |
| 当前工具结果 | `<tool-result-id>` |
| 当前工具 Trace | `<current-trace-id>`（146 Span） |
| OCPP 当前闭环订单 | `<synthetic-order-id>` / 交易号 `5`（SETTLED，1 张账单） |

本机截图：`tmp/full-flow/evaluation-scores.png`。私有复现状态与脚本位于 `tmp/full-flow/`，未纳入 Git；其中包含凭据，禁止直接打包发布。
