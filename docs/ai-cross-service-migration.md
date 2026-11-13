# AI 跨服务调用

AI 的运维调用使用 MCP Streamable HTTP 2025-06-18。运维服务提供订单、账单、设备、遥测、用户身份和协议文档能力。Agent 从 `tools/list` 获取工具描述和参数，按已发布 Agent 策略、用户权限与只读白名单筛选。确定性降级和模型工具复用 `RemoteTools` 的权限检查、调用预算、审计和 MCP 传输。

## 当前部署

| 组件 | 配置 |
| --- | --- |
| Nacos 3.3.0 | 命名空间 `public`，分组 `DEFAULT_GROUP`，实例服务 `iot-protocol-expert-ops`，MCP Registry 中的 `ipe-ops-rest` 版本 `1.0.0` |
| Higress 2.2.6 | `nacos3` 服务来源 `ipe-nacos3`，REST-to-MCP 自动路由 `/ipe-mcp/ipe-ops-rest` |
| AI 客户端 | 从 Nacos MCP Registry 订阅 `ipe-ops-rest`，优先使用目录 `frontendEndpoints`；缺失时由 `IPE_AI_MCP_GATEWAY_BASE` + 服务名拼出，`IPE_AI_MCP_URL` 仅兜底 |
| 运维端 | 动态上游 `iot-protocol-expert-ops.DEFAULT-GROUP.public.nacos:19082`，JSON HTTP 接口位于 `/internal/ai/rest/{operation}` |

Nacos MCP Registry 的服务协议为 `http`，前端协议为 `mcp-streamable`。Higress 从注册目录同步工具到 HTTP 的映射，自动生成 MCP 路由。版本 1.0.0 的定义见 [工具映射](../infra/ipe-ops-rest-mcp.json)。部署使用 Higress 容器内置的 MCP WASM 2.0.3，Redis 仅接入 Compose 内部网络。

## Nacos 接入

两个服务统一使用 Spring Cloud Alibaba 官方 starter，不再手写 `NamingService`：

| 关注点 | 用法 |
| --- | --- |
| 注册与发现 | `spring-cloud-starter-alibaba-nacos-discovery`，配置统一在 `spring.cloud.nacos.*`；`IPE_NACOS_DISCOVERY_ENABLED=false` 时不建立任何连接 |
| 实例地址 | `spring.cloud.nacos.discovery.ip` 留空则由 `spring.cloud.inetutils` 自动选非回环地址，多网卡再显式指定 `IPE_OPS_ADVERTISE_IP` / `IPE_AI_ADVERTISE_IP` |
| 实例元数据 | 注册时带 `ipe-role`、`ipe-environment`、`ipe-version`、能力标签，网关与人工排查可直接在控制台区分实例 |
| 下线顺序 | `graceful-shutdown-wait-time` 在 Web 优雅停机之前先注销实例，避免网关继续转发到正在退出的实例 |
| 失败策略 | `fail-fast=true`：注册失败即启动失败，不出现「进程在但目录里没有」 |
| 健康与端点 | starter 自带 `nacosDiscovery` 健康指示器与 `/actuator/nacosdiscovery` 端点 |
| MCP 目录 | AI 用 `AiService.subscribeMcpServer` 订阅 `ipe-ops-rest`，校验版本与工具清单；订阅失败或校验未通过按 60 秒重试，期间回退到静态地址 |

AI 侧的目录客户端复用 `spring.cloud.nacos.server-addr` / `username` / `password`，由 `ipe.ai.mcp.registry.*` 控制服务名、期望版本与命名空间。解析到的入口、版本、工具数量以及漂移原因写入启动日志；需要对外查看时把 `management.endpoint.health.show-details` 设为 `always`，`/actuator/health` 会多出 `mcpCatalog`（即 `McpRegistryHealthIndicator`）节点。

多网卡机器（例如同时装了 Tailscale 与虚拟网卡）自动探测可能选错地址，运维与 AI 分别用 `IPE_OPS_ADVERTISE_IP`、`IPE_AI_ADVERTISE_IP` 指定网关可达地址；当前本机 ops 自动选中 Tailscale 地址，AI 需要显式指定。

## 启动与权限

使用 `.env.example` 中的 MCP 和 Nacos 配置。AI 与运维必须使用相同的 `IPE_AI_MCP_TOKEN`，本机的值保存在已忽略的 `.env.local`。共享环境设 `IPE_NACOS_DISCOVERY_ENABLED=true`，两个服务启动后注册临时实例，退出时先注销再停机；本地不连 Nacos 时保持关闭。填写网关可达的 `IPE_OPS_ADVERTISE_IP` / `IPE_AI_ADVERTISE_IP`（留空则自动探测）和对应的 Nacos 凭据。执行 `scripts/start-local.ps1` 可加载该配置。

AI 将身份、站点范围和登录会话 ID 放入绑定到目标操作的 AES-GCM 短时凭据。模型的工具 schema 隐藏该字段，AI 客户端统一生成凭据。Higress 将工具参数转换成 JSON HTTP 请求，运维服务解密并重新校验用户状态、会话版本和站点范围。文档接口要求 `ai:admin`，内容按块传输。运维会话仍保存在本地实例，使用 AI 校验会话不会续期。

## 验证记录

2026-11-13 在本地 19082/19083 启动新版，通过 Higress 验证 MCP 握手、工具目录、浏览器登录态、真实订单详情、过期凭据和越权站点拒绝、文档列表和分块读取。完整文档的 SHA-256 与原件一致。真实 Agent 运行记录的订单工具为 `SUCCEEDED`，随后整轮任务为 `RUN_TIMEOUT`。旧透传入口返回 404。

模型推理仍由 AgentScope 的模型客户端完成。协议知识检索访问 AI 自己的索引，Agent 委派在同一服务内执行。
