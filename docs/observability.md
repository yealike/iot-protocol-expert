# OpenTelemetry 与 Grafana 可观测性

当前方案：应用通过 **OTLP HTTP** 把 trace、log、metric 发送到服务器上的 **OpenTelemetry Collector**，Collector 再分发：

```text
Protocol/Ops/Simulator/AI --OTLP HTTP--> Collector :4318
                                         ├── traces  --> Tempo :3200  --> Grafana
                                         ├── logs    --> Loki :3100   --> Grafana
                                         └── metrics --> Prometheus (scrape :8889) --> Grafana

Ops --JDBC--> PostgreSQL (设备/订单/账单/报文/命令/排障)
AI  --JDBC--> PostgreSQL (会话/Run/事件/知识库)
```

集中式可观测性组件部署在独立服务器上，应用仍可在本机运行。真实服务器地址、账号和部署路径仅保存在本机配置中。

## 本地启动

```powershell
# 将 JAVA_HOME 指向本机 JDK 21，或设置 IPE_JAVA_HOME
mvn -B package
.\scripts\start-local.ps1
```

启动脚本读取 `IPE_OTLP_ENDPOINT`（未设置时为 `http://127.0.0.1:4318`）；`IPE_OTLP_ENABLED=true` 时自动开启三类 OTLP 导出。

Runtime 的 AgentScope 元数据埋点另有服务级开关 `IPE_RUNTIME_OTEL_ENABLED`。未显式设置时，它跟随 `IPE_OTLP_ENABLED`；两者都未设置时默认为 `true`。本地公共脚本不再写入 `IPE_RUNTIME_OTEL_ENABLED=false`，因此 `start-local.ps1 -EnableOtlp` 或环境中的 `IPE_OTLP_ENABLED=true` 会同时启用 Runtime Trace。需要单独关闭或强制开启时，仍可显式设置该变量。

| 入口 | 协议通道 | 运维平台 | 模拟器 |
|---|---|---|---|
| 业务控制台 | — | <http://localhost:5173>（前端）；<http://localhost:19082>（API） | <http://localhost:19081> |
| 健康检查 | <http://localhost:19080/actuator/health> | <http://localhost:19082/actuator/health> | <http://localhost:19081/actuator/health> |
| 最近链路（本地缓存） | <http://localhost:19080/actuator/telemetry> | <http://localhost:19082/actuator/telemetry> | <http://localhost:19081/actuator/telemetry> |
| Prometheus 文本指标 | <http://localhost:19080/actuator/prometheus> | <http://localhost:19082/actuator/prometheus> | <http://localhost:19081/actuator/prometheus> |

`/actuator/telemetry` 仅 `local` profile 启用，缓存最近 1000 个 Span，供没有 Grafana 时快速排查；生产链路以 Tempo 为准。

AI 助手（`19083`）的 `health` / `prometheus` 入口遵循同一规则；AI Trace 不单独建 exporter，直接复用本节的 OTLP 管道。

## IDEA 启动

运维平台、协议通道和模拟器的 Run Configuration 挂载同一份 `.env.local` 即可：

```text
IPE_OTLP_ENABLED=true
IPE_OTLP_ENDPOINT=http://127.0.0.1:4318
IPE_ENVIRONMENT=local
# 可选：不设置时跟随 IPE_OTLP_ENABLED
# IPE_RUNTIME_OTEL_ENABLED=true
```

服务名由 `spring.application.name` 自动区分（`iot-protocol-expert-ops` / `iot-protocol-expert-protocol-gateway` /
`iot-protocol-expert-simulator`），不需要 Agent 参数或单独的服务名变量。`.env.local` 已被 `.gitignore` 忽略。

## Grafana

- 本机 Grafana 地址：<http://127.0.0.1:3000>
- 数据源：`IPE Tempo`、`IPE Loki`、`IPE Prometheus`
- 常用入口：
  - Explore → `IPE Tempo` → Search，按 service / span 名称查询
  - Explore → `IPE Loki` → 按 `service_name`、`trace_id` 查询日志
  - Explore → `IPE Prometheus` → 查询 `http_server_requests_seconds_*`、`ipe_protocol_*`、`traces_span_metrics_*`

Tempo 数据源自带 Trace → Logs 跳转；Loki 的 `trace_id` 派生字段可反向跳回 trace；Prometheus 的 exemplar 关联 `trace_id`。

## 服务端组件

| 组件 | 地址 | 说明 |
|---|---|---|
| OTel Collector | `127.0.0.1:4317`（gRPC）/ `4318`（HTTP，本机示例） | 应用唯一入口；batch、脱敏、队列重试 |
| Tempo | `127.0.0.1:3200`（仅本机/Grafana） | 本地文件存储，trace 保留 7 天 |
| Loki | `127.0.0.1:3100` | 单机 filesystem，日志保留 7 天 |
| Prometheus | `9090` | 抓取 Collector `:8889` 暴露的应用指标与 spanmetrics |
| Grafana | `3000` | 统一查询与展示 |

Collector 内置脱敏：删除 password / authorization / cookie / body / payload / VIN / card 等属性，以及
access_token / api_key / secret 等凭据键；移除 `db.statement`、`db.query.text`、`url.query`。注意不能用宽泛的
`.*token.*` 规则，否则会误删 `gen_ai.usage.input_tokens` / `output_tokens`。指标资源属性仅保留低基数标签。

## AI 对话链路

当 `IPE_RUNTIME_OTEL_ENABLED` 解析为 `true` 时，Runtime 服务注册 AgentScope 官方 `TelemetryTracer`，Span 使用 GenAI 语义约定，并复用 Spring Boot 的 OTLP 管道：

> AgentScope 2.0.4 中带内容的 `TelemetryTracer` 仍通过已标记待删除的 `TracerRegistry` 挂载；升级 2.1+
> 时需复核官方 middleware 是否已支持内容属性，再调整 `AgentRuntimeTelemetry`。

应用级 Span（HTTP、JDBC、`ai run execute`）由 Spring Boot Micrometer Tracing 产生，Agent 级 Span 由
AgentScope 官方 `TelemetryTracer` 产生；两者注入同一个 `OpenTelemetry` Bean，共用 `SdkTracerProvider`、
Resource、Sampler 与 OTLP exporter，因此 trace_id 和父子关系可以连续。

```text
ai run execute                     # Run 队列恢复的父 Span
└── invoke_agent team-leader       # gen_ai.operation.name=invoke_agent
    ├── chat deepseek-flash        # gen_ai.operation.name=chat
    │   ├── gen_ai.request.model
    │   ├── gen_ai.usage.input_tokens / output_tokens
    │   └── gen_ai.response.finish_reasons
    └── execute_tool search_protocol_knowledge
```

最终全流程实测：结果 `1a7dc3e3-7644-4d10-9ed6-64809461b71e` 的 Trace `89efd4447cc7ea7f95d54b5dd0f9ed6e` 包含 144 个 Span，实际链路为 `agent.run -> invoke_agent -> chat/execute_tool`，其中 `execute_tool` 为 `read_skill_resource`。较早专项复验的结果 `f676acdf-4d48-4938-8894-8fe710eac522` 对应 146 Span 的 Trace `4ee37cd161c4148f38e6c07a7cadb9f8`，作为历史证据保留。

默认只导出元数据与 token；`gen_ai.input.messages`、`gen_ai.output.messages`、`gen_ai.tool.call.arguments/result`、
`gen_ai.tool.definitions`、`agentscope.function.*` 不写入 Span 属性、Event、异常附加属性或 Link。需要在本地查看消息或工具正文时显式开启：

```powershell
$env:IPE_RUNTIME_OTEL_CONTENT_ENABLED='true'
$env:IPE_RUNTIME_OTEL_MAX_CONTENT_CHARS='8192'
```

开启后仍会对常见凭据 JSON 字段、Bearer Token、`sk-*` 密钥、URL 内嵌凭据和私钥块做脱敏；`IPE_RUNTIME_OTEL_MAX_CONTENT_CHARS` 大于 0 时再按配置长度截断，示例中的 8192 表示 8192 字符上限，设置为 0 表示不设应用层字符上限。prompt/response 属于业务数据，不应在生产默认开启；Tempo / Doris 的保留期和访问权限需要同步评估。

`IPE_RUNTIME_OTEL_CONTENT_ENABLED` 只控制 Tempo 中的消息和工具正文属性。评估所需的工具参数/结果审计证据由 Runtime 事件写入 Control 数据库，每次调用的参数整体或结果整体最多 8000 字符并在写入前脱敏；它与 Trace 内容开关独立，详见 [Agent 能力评估](agent-evaluation.md) 和 [Agent 使用说明](agent-operation-guide.md)。

Grafana 查询：

- Tempo Explore 选择 `service_name=iot-protocol-expert-agent-runtime`，按 Span 名称 `invoke_agent`、`chat`、`execute_tool` 过滤。
- 打开任一 `chat` Span，在 Attributes 中查看 `gen_ai.usage.input_tokens` / `output_tokens`。
- 按 `trace_id` 汇总所有 `chat` Span 的 token 属性，可得到一次对话的总 token；Doris 查询按实际 `otel_traces` 表结构调整。

## 指标

`/actuator/prometheus` 与 Grafana 中可用的主要指标：

```text
http_server_requests_seconds_bucket
ipe_protocol_operations_total
ipe_protocol_operation_duration_seconds_bucket
jvm_memory_used_bytes
process_cpu_usage
traces_span_metrics_calls_total
traces_span_metrics_duration_milliseconds_bucket
```

业务指标只使用 side、operation、frame_type、outcome 四个维度；公共标签为 application_name、active_profile、instance。桩号、订单、序列号、Trace ID 不作为指标标签；HTTP uri 标签设置 200 种上限。

## 日志

日志文件：协议通道与运维平台**不再写本地滚动日志**，仅输出结构化控制台并发送 OTLP；模拟器保留 `logs/iot-protocol-expert-simulator.json` 便于设备侧排查。

```text
logs/iot-protocol-expert-simulator.json
```

JSON 使用 Boot 原生 Logstash 格式，包含服务名、版本、环境、线程、logger、级别、message；Micrometer MDC 的 `traceId` / `spanId` 重命名为 `trace_id` / `span_id`，与 Tempo / Loki 中的同名字段对齐。协议事件保留 `event.name=protocol.operation`、`ipe.side`、`ipe.operation`、`ipe.frame.type`、`ipe.frame.sequence`、`outcome`、`duration_ms`。

API 日志使用路由模板和状态码，不记录请求/响应正文和查询串；成功 GET 为 DEBUG，写操作 INFO，4xx WARN，5xx ERROR。文件 20MB 轮转、7 天、总量 200MB，异常堆栈最多 8192 字符。

## 验证

```powershell
mvn -B package
python scripts/verify-spring-local.py
```

验收脚本会先在 PostgreSQL 中创建一次性数据库，使用独立目录和固定隔离端口启动 OPS（29082）、Gateway（29080）、
Simulator（29081，TCP 28768、OCPP 28769）三个实例，再启动本地 OTLP 接收器，验证充电计费、断网补传、并充、
同 Trace 的 HTTP 桥接、真实 TCP 命令与 ACK 父子关系、JDBC Span、JSON 日志关联、Prometheus 直方图，以及链路、
指标、日志三类 OTLP 请求。结束后停止测试进程并删除临时数据库，不接触业务库或用户调试实例。

这些端口用于隔离本地调试实例；运行前需确认端口未被其他进程占用。`IPE_OTLP_ENDPOINT` 只填写 Collector 基础地址，
应用会追加 `/v1/traces`、`/v1/logs`、`/v1/metrics`，不要填写具体导出路径。

脚本需要 `psycopg2`；数据库账号需能连接 `postgres` 管理库并创建、终止连接和删除临时数据库。连接配置优先读取
`PEP_CONTROL_DB_*`，兼容 `IPE_OPS_DB_*`；URL、用户名和密码从同一组配置读取，原 URL 的 TLS query 参数会保留。

Tempo 查询地址和 Loki 地址必须从 Control/OPS 所在网络可达；Collector 写入地址不能替代 Tempo 查询地址。若服务端
只监听本机，应部署受控的反向代理后再配置 `IPE_TEMPO_QUERY_BASE_URL` 和 `IPE_LOKI_URL`，否则结果页的“加载链路”
和服务侧日志诊断会明确不可用。

服务端健康检查：

```bash
cd /path/to/ipe-observability
docker compose ps
curl -s 127.0.0.1:3200/ready     # Tempo
curl -s 127.0.0.1:3100/ready     # Loki
curl -s 127.0.0.1:13133          # Collector
```

结果见 `docs/spring-observability-verification.json`；验收 JSON 是本机生成产物，默认不纳入版本控制。

## 协议边界

标准云快充 1.6 报文没有 W3C traceparent 字段。每个 TCP 接收通常创建独立根 Span；平台本地按桩、应答类型和序列号关联等待命令的 Context，不修改协议报文。HTTP 桥接可以正常跨服务传递 Trace。

参考：[Spring Boot Tracing](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html)、[OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)、[Grafana Tempo](https://grafana.com/docs/tempo/latest/)、[Grafana Loki](https://grafana.com/docs/loki/latest/)。
