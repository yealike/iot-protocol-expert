# OpenTelemetry 与 Grafana 可观测性

当前方案：应用通过 **OTLP HTTP** 把 trace、log、metric 发送到服务器上的 **OpenTelemetry Collector**，Collector 再分发：

```text
Protocol/Ops/Simulator --OTLP HTTP--> Collector :4318
                                      ├── traces  --> Tempo :3200  --> Grafana
                                      ├── logs    --> Loki :3100   --> Grafana
                                      └── metrics --> Prometheus (scrape :8889) --> Grafana

Ops --JDBC--> PostgreSQL (设备/订单/账单/报文/命令/排障)
```

服务端栈位于服务器 `/home/yehan/ipe-observability`，复用现有 Grafana / Prometheus 的 `prometheus-grafana_monitoring` 网络。运维平台数据库为服务器上的 `ipe-postgres`（端口 `55432`），应用仍在本机运行。

## 本地启动

```powershell
# 将 JAVA_HOME 指向本机 JDK 21，或设置 IPE_JAVA_HOME
mvn -B package
.\scripts\start-local.ps1
```

启动脚本读取 `IPE_OTLP_ENDPOINT`（未设置时为 `http://127.0.0.1:4318`）；`IPE_OTLP_ENABLED=true` 时自动开启三类 OTLP 导出。

| 入口 | 协议通道 | 运维平台 | 模拟器 |
|---|---|---|---|
| 业务控制台 | — | <http://localhost:19082/ops> | <http://localhost:19081> |
| 健康检查 | <http://localhost:19080/actuator/health> | <http://localhost:19082/actuator/health> | <http://localhost:19081/actuator/health> |
| 最近链路（本地缓存） | <http://localhost:19080/actuator/telemetry> | <http://localhost:19082/actuator/telemetry> | <http://localhost:19081/actuator/telemetry> |
| Prometheus 文本指标 | <http://localhost:19080/actuator/prometheus> | <http://localhost:19082/actuator/prometheus> | <http://localhost:19081/actuator/prometheus> |

`/actuator/telemetry` 仅 `local` profile 启用，缓存最近 1000 个 Span，供没有 Grafana 时快速排查；生产链路以 Tempo 为准。

## IDEA 启动

两个 Run Configuration 都挂载同一份 `.env-local` 即可：

```text
IPE_OTLP_ENABLED=true
IPE_OTLP_ENDPOINT=http://100.101.91.38:4318
IPE_ENVIRONMENT=local
```

服务名由 `spring.application.name` 自动区分（`iot-protocol-expert-protocol` / `iot-protocol-expert-simulator`），不需要 Agent 参数或单独的服务名变量。`.env-local` 已被 `.gitignore` 忽略。

## Grafana

- 地址：<http://100.101.91.38:3000>
- 数据源：`IPE Tempo`、`IPE Loki`、`IPE Prometheus`
- 常用入口：
  - Explore → `IPE Tempo` → Search，按 service / span 名称查询
  - Explore → `IPE Loki` → 按 `service_name`、`trace_id` 查询日志
  - Explore → `IPE Prometheus` → 查询 `http_server_requests_seconds_*`、`ipe_protocol_*`、`traces_span_metrics_*`

Tempo 数据源自带 Trace → Logs 跳转；Loki 的 `trace_id` 派生字段可反向跳回 trace；Prometheus 的 exemplar 关联 `trace_id`。

## 服务端组件

| 组件 | 地址 | 说明 |
|---|---|---|
| OTel Collector | `100.101.91.38:4317`（gRPC）/ `4318`（HTTP） | 应用唯一入口；batch、脱敏、队列重试 |
| Tempo | `127.0.0.1:3200`（仅本机/Grafana） | 本地文件存储，trace 保留 7 天 |
| Loki | `127.0.0.1:3100` | 单机 filesystem，日志保留 7 天 |
| Prometheus | `9090` | 抓取 Collector `:8889` 暴露的应用指标与 spanmetrics |
| Grafana | `3000` | 统一查询与展示 |

Collector 内置脱敏：删除 password / authorization / cookie / token / body / payload / VIN / card 等属性，移除 `db.statement`、`db.query.text`、`url.query`。指标资源属性仅保留低基数标签。

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

验收脚本用独立目录、29080/29081 HTTP、28768 TCP 启动两端实例，并启动一个本地 OTLP 接收器，验证充电计费/断网补传/并充、同 Trace 的 HTTP 桥接、真实 TCP 命令与 ACK 父子关系、JDBC Span、JSON 日志关联、Prometheus 直方图，以及链路/指标/日志三类 OTLP 请求。结束后只清理测试进程，保留用户调试实例。

服务端健康检查：

```bash
cd /home/yehan/ipe-observability
docker compose ps
curl -s 127.0.0.1:3200/ready     # Tempo
curl -s 127.0.0.1:3100/ready     # Loki
curl -s 127.0.0.1:13133          # Collector
```

结果见 `docs/spring-observability-verification.json`；验收 JSON 是本机生成产物，默认不纳入版本控制。

## 协议边界

标准云快充 1.6 报文没有 W3C traceparent 字段。每个 TCP 接收通常创建独立根 Span；平台本地按桩、应答类型和序列号关联等待命令的 Context，不修改协议报文。HTTP 桥接可以正常跨服务传递 Trace。

参考：[Spring Boot Tracing](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html)、[OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/)、[Grafana Tempo](https://grafana.com/docs/tempo/latest/)、[Grafana Loki](https://grafana.com/docs/loki/latest/)。