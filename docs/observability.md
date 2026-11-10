# Spring Boot 原生可观测性与本地调试

当前使用 Spring Boot 4.0.9 原生可观测性：Spring 管理 OpenTelemetry SDK、Micrometer Tracing 和指标注册表。无需 Java Agent，也无需设置 JVM Agent 参数。

项目默认以本地模式运行，不自动启动或部署遥测后端。`deploy/observability/` 提供可选的 Collector、Jaeger 和 Loki 配置，连接已有 Prometheus / Grafana 网络时可按环境调整。

## 本地启动

```powershell
# 将 JAVA_HOME 指向本机 JDK 21，或设置 IPE_JAVA_HOME
mvn -B package
.\scripts\start-local.ps1
# 调整代码并重新打包后：先停止，再启动。
.\scripts\stop-local.ps1
```

启动脚本从 IPE_JAVA_HOME、忽略提交的 scripts/local-settings.json 或 JAVA_HOME 读取 JDK 21 路径，并等待两端健康检查；`local` profile 绑定 HTTP 回环地址。IDE 直接运行 `ProtocolApplication` / `SimulatorApplication` 也可以，默认 profile 是 `local`，不需要 VM 参数。IDE 工作目录设为项目根目录，确保两端使用预期的数据文件路径。

| 入口 | 平台端 | 模拟器 |
|---|---|---|
| 业务控制台 | <http://localhost:19080> | <http://localhost:19081> |
| 健康检查 | <http://localhost:19080/actuator/health> | <http://localhost:19081/actuator/health> |
| 最近链路 | <http://localhost:19080/actuator/telemetry> | <http://localhost:19081/actuator/telemetry> |
| 指标目录 | <http://localhost:19080/actuator/metrics> | <http://localhost:19081/actuator/metrics> |
| Prometheus 文本指标 | <http://localhost:19080/actuator/prometheus> | <http://localhost:19081/actuator/prometheus> |

两端业务数据仍分别写入 `data/protocol.mv.db` 和 `data/simulator-state.properties`。完整充电冒烟测试只在隔离实例执行，不操作这两个本地调试实例的充电状态。

## 查看效果

1. 在模拟器控制台执行读取实时数据等操作。
2. 打开 `/actuator/telemetry`，查找 `yunkuaichong request 0x12`、`yunkuaichong receive 0x13`、`query`、`connection` 和 HTTP Span。
3. 复制 `traceId`，分别访问两端 `/actuator/telemetry?traceId=<traceId>`，查看 HTTP 跨服务调用及平台本地 TCP 命令/ACK 父子关系。
4. 在 JSON 日志中搜索同一个 `trace_id`，核对帧类型、序列号、处理结果及耗时。

本地链路端点通过 Spring 提供的 SDK Builder Customizer 安装内存 SpanProcessor，使用同一个 SDK。仅 `local` profile 注册，缓存最近1000个完成的 Span，每次返回最多200个，重启即清空；它是本地排查入口，不替代 Jaeger 的持久化存储。无需在本机安装 Docker。

日志文件：

```text
logs/iot-protocol-expert-protocol.json
logs/iot-protocol-expert-simulator.json
```

启动脚本另写 `logs/protocol.out.log` / `simulator.out.log` 及对应 `err.log`。JSON 使用 Boot 原生 Logstash 格式，包含服务名、版本、环境、线程、logger、级别、message；Micrometer MDC 的 `traceId` / `spanId` 统一重命名为 `trace_id` / `span_id`。启动阶段没有当前 Span 的日志不会伪造关联 ID。

协议事件具有 `event.name=protocol.operation`、`ipe.side`、`ipe.operation`、`ipe.frame.type`、`ipe.frame.sequence`、`outcome`、`duration_ms`。API 日志使用路由模板和状态码，不记录请求/响应正文和查询串；成功 GET 为 DEBUG，写操作 INFO，4xx WARN，5xx ERROR。文件20MB轮转、7天、总量200MB，异常堆栈最多8192字符。

## 组件与配置

| 能力 | 实现 |
|---|---|
| SDK、链路与采样 | `spring-boot-starter-opentelemetry`，Micrometer Tracing → OTel |
| JVM、HTTP、连接池及业务指标 | Spring Actuator + Micrometer |
| 指标 OTLP 导出 | `micrometer-registry-otlp` |
| 本地指标查看 | `micrometer-registry-prometheus`，`/actuator/prometheus` |
| JDBC 操作链路 | Datasource Micrometer 2.0.0（适配 Boot4.0） |
| 跨服务 HTTP | 注入 Boot 的 `RestClient.Builder` 创建客户端，自动传播上下文 |
| 日志 OTLP 导出 | Boot `SdkLoggerProvider` + OTel Logback Appender 2.21.0-alpha（对齐 Boot 的 OTel1.55） |
| Spring 异步执行器 | `ContextPropagatingTaskDecorator` |
| 云快充 TCP | 注入 `Tracer` 和 `MeterRegistry` 的 `ProtocolTelemetry` |

共用配置位于 `iot-protocol-expert-protocol/src/main/resources/observability-defaults.yml`，由两个应用的 `application.yml` 导入。共用 `ObservabilityConfiguration`、Logback 配置放在同一模块，模拟器显式导入配置。

自定义执行器需要显式安装 `ContextPropagatingTaskDecorator`；原生 `new Thread`、默认 commonPool 和 Netty 回调不保证自动继承调用者上下文。TCP pending 命令使用 Micrometer `TraceContext` 显式恢复 ACK 父上下文。Spring 管理的默认执行器自动应用 TaskDecorator Bean；测试覆盖跨线程传播和线程复用后的清理。

JDBC 默认采集 connection/query；禁用 SQL 参数值、查询日志和逐行操作追踪，并移除 JDBC Observation 中的 SQL/参数高基数属性。保留数据源、驱动、连接池等属性及查询耗时。不要在新增日志的 message 中写入凭据、VIN、卡号或完整敏感报文。

业务指标只使用 side、operation、frame_type、outcome 四个维度；公共标签为 application_name、active_profile、instance。桩号、订单、序列号、Trace ID 不作为指标标签；HTTP uri 标签设置200种上限。

### 指标名称

`/actuator/prometheus` 的主要指标：

```text
http_server_requests_seconds_bucket
ipe_protocol_operations_total
ipe_protocol_operation_duration_seconds_bucket
jvm_memory_used_bytes
process_cpu_usage
jdbc_connections_active
hikaricp_connections_active
```

`operation=request` 表示平台完整命令往返，`receive` 为帧处理，`send` 为 socket 实际异步写完成耗时。失败和超时通过 `outcome=error/timeout` 区分。OTLP registry 的时间基准可能与 Prometheus 的 seconds 不同，连接 Collector 后需按实际导出的单位选择大盘。

### 按需开启 OTLP 外发

默认三类信号的外发均关闭，既不连接本地 Collector，也不连接任何远端服务器。链路本身和 JSON 关联仍工作。

需要连接一个已经运行的本地 Collector 时：

```powershell
.\scripts\start-local.ps1 -EnableOtlp -OtlpEndpoint http://127.0.0.1:4318
```

或在 IDE 设置环境变量：

```text
IPE_OTLP_ENABLED=true
IPE_OTLP_ENDPOINT=http://127.0.0.1:4318
IPE_ENVIRONMENT=local
```

端点变量是公共基础地址，不带 `/v1/traces` 等路径；配置分别拼接 `/v1/traces`、`/v1/metrics`、`/v1/logs`。配置项分别为：

```text
management.tracing.export.otlp.enabled
management.opentelemetry.tracing.export.otlp.endpoint
management.logging.export.otlp.enabled
management.opentelemetry.logging.export.otlp.endpoint
management.otlp.metrics.export.enabled
management.otlp.metrics.export.url
```

外发链路默认100%采样，指标每15秒批量发送。通过 Spring 配置可调整采样和导出间隔。可连接自行部署的 Collector/Jaeger/Prometheus/Loki；项目不会自动发布到服务器。

## 验证

2026-11-10 本次迁移通过36项测试和隔离真实TCP联调。平台同一条请求中记录12个JDBC Span，命令与ACK父子关联通过；两端142条协议事件均包含JSON Trace/Span关联。临时本地接收器收到链路、指标、日志三类OTLP请求，未连接服务器。

```powershell
# 将 JAVA_HOME 指向本机 JDK 21，或设置 IPE_JAVA_HOME
mvn -B package
python scripts/verify-spring-local.py
```

验收脚本用独立目录、29080/29081 HTTP、28768 TCP，启动两端无 Agent 的实例及仅绑定本地临时端口的 OTLP 接收器。验证充电计费/断网补传/并充、同 Trace 的 HTTP 桥接、真实 TCP 命令与 ACK 父子关系、JDBC Span、JSON 日志关联、Prometheus 直方图，以及链路/指标/日志三类 OTLP protobuf 请求。结束后只清理测试进程，保留用户调试实例。

结果见 `docs/spring-observability-verification.json`；验收 JSON 是本机生成产物，默认不纳入版本控制。

## 协议边界

标准云快充1.6报文没有 W3C traceparent 字段。每个 TCP 接收通常创建独立根 Span；平台本地可以按桩、应答类型和序列号关联等待命令的 Context。不会将模拟器与平台之间的原始 TCP 收发伪装为同一条跨服务 Trace，也不修改协议报文。HTTP 桥接可以正常跨服务传递 Trace。

参考：[Spring Boot Tracing](https://docs.spring.io/spring-boot/4.0/reference/actuator/tracing.html)、[异步上下文](https://docs.spring.io/spring-boot/4.0/reference/actuator/observability.html)、[Datasource Micrometer](https://jdbc-observations.github.io/datasource-micrometer/docs/current/docs/html/)、[日志导出](https://docs.spring.io/spring-boot/4.0/reference/actuator/loggers.html)。
