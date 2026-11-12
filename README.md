# iot-protocol-expert

多设备能源物联运维平台。覆盖充电桩、光伏、风机、储能、地锁、门禁、摄像头、电表八类设备，
由**协议通道**负责连接与编解码，**运维平台**以「产品 + 物模型 + 设备实例」统一承载台账与实时数据，
**前端**是独立的 React SPA，**模拟器**从运维平台选桩并按档案协议接入。
Java 21 / Spring Boot 4.0.9 / Netty / PostgreSQL 16 / Flyway / React 19 + Vite + Ant Design 5。

领域模型与表结构见 [物模型与设备域设计](docs/thing-model.md)。

## 服务边界

| 模块 | 端口 | 职责 | 持久化 |
|---|---|---|---|
| `iot-protocol-expert-codec` | — | 云快充与 OCPP 纯编解码库 | 无 |
| `iot-protocol-expert-protocol` | 19080；TCP 8768；ws 8769 | 连接、校验、编解码、会话与下行发送 | **零业务持久化** |
| `iot-protocol-expert-ops` | 19082 | 产品与物模型、设备、通道、属性/事件/告警、订单账单、报文、命令、排障、用户与审计 | PostgreSQL |
| `iot-protocol-expert-web` | 5173（dev） | React SPA：总览、设备、产品与物模型、告警、园区 3D 沙盘 | 无 |
| `iot-protocol-expert-simulator` | 19081 | 从平台选桩，模拟插枪、充电、上报与账单补传，内嵌园区沙盘 | 模拟器自身状态文件 |

```text
模拟器 --TCP/WS--> 协议通道 --内部 HTTP--> 运维平台(PostgreSQL)
   |                    ^                    |
   +---- 选桩/只读 -----+---- 下行命令 -------+
```

- 协议通道通过 `协议编码 + 协议侧设备编号` 查档；两台文本编号相同的设备可同时存在。
- 业务应答由运维平台事务结果决定；平台拒绝或不可达时按协议返回失败/保留重试条件。
- 通道只保留连接、会话、应答关联与短时发送缓冲；重启后由设备重连恢复。
- 服务间使用 `X-Ops-Service-Token`；模拟器使用只读的 `X-Ops-Simulator-Token`。

架构、接口与验收细节见 [运维平台与排障](docs/ops-platform.md)。

## 启动（本地调试）

需要 JDK 21、PostgreSQL 16。仓库不部署服务；中间件可放在个人服务器，本机运行应用。

```powershell
# 1) 准备 PostgreSQL（示例：服务器 100.101.91.38:55432）
#    docker run -d --name ipe-postgres -e POSTGRES_DB=ipe_ops -e POSTGRES_USER=ipe \
#      -e POSTGRES_PASSWORD=devpass -p 55432:5432 -v ipe_pg_data:/var/lib/postgresql/data postgres:16

# 2) 编译
$env:JAVA_HOME='D:\develop\jdk\jdk-21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -B package

# 3) 启动三端（默认使用本机 19080/19081/19082，数据库读取 application.yml 默认值）
.\scripts\start-local.ps1 -DeviceId 1
```

- 运维平台：<http://localhost:19082/>（React SPA，未登录时显示登录页）。
- 协议通道：<http://localhost:19080>，TCP `8768`，OCPP `ws://localhost:8769/ocpp/{编号}`。
- 模拟器：<http://localhost:19081>，右上角从平台设备目录选择一台桩。
- 停止：`.\scripts\stop-local.ps1`。
- 数据库连接可用 `IPE_OPS_DB_URL / IPE_OPS_DB_USER / IPE_OPS_DB_PASSWORD` 覆盖；服务间地址用 `IPE_OPS_BASE_URL / IPE_PROTOCOL_BASE_URL` 覆盖。

### 演示账号

| 账号 | 密码 | 角色 | 能力 |
|---|---|---|---|
| `admin` | `admin123` | ADMIN | 电站、设备、用户、命令与排障 |
| `ops` | `ops123` | OPS | 设备远程启停、排障、查询 |
| `viewer` | `viewer123` | VIEWER | 只读查询 |

## 多协议

| 协议 | 地址 | 说明 |
|---|---|---|
| 云快充 1.6 | TCP `8768` | 登录帧绑定桩号，0x31/0x32 启动，0x3B/0x40 交易记录与确认 |
| OCPP 1.6J | `ws://localhost:8769/ocpp/{chargePointId}` | BootNotification、StartTransaction、MeterValues、StopTransaction |

协议通过 `ProtocolServer` SPI 注册，由 `ProtocolServerManager` 统一启停。协议细节见 [OCPP 1.6J 接入说明](docs/ocpp16.md)。

## 运维平台功能

- **电站**：列表、详情、运营概览；在线桩、可用/充电中/故障枪，按日期查询订单数、电量、金额（桩上报账单与平台估算分列）。
- **设备**：按 SN / 协议侧编号 / 协议 / 站点 / 在线状态查找；登记、编辑、迁站、停用；枪状态、最近订单、最近报文与命令结果；人工远程启停。
- **订单与账单**：按站点、设备、枪、协议、流水号、时间、状态查询；订单详情展示启停时间、电量、金额来源、停止原因、平台流水号与协议交易号；关联账单。
- **智能排障**：输入 SN、订单号、HEX 或 OCPP JSON；输出协议判定、结构化字段、处理时间线、规范引用与待确认项；覆盖报文释义与停充后账单缺失。
- **命令与审计**：命令先落记录再投递通道，区分发送、应答、成功、拒绝、超时与等待状态；写操作记录操作人、对象、时间与结果。

## 充电实验室（模拟器）

1. 在右上角选择平台设备；协议与设备编号由档案决定，只读展示。
2. 等待「平台已登录」，确认桩已接收平台费率。
3. 选择枪号，设置电池容量、SOC、VIN、物理卡号与停止条件，插枪后启动。
4. 使用速度选择器或「+1分钟」「+30分钟」推进；真实 TCP 心跳与上报间隔仍按真实时间。
5. 本地/APP 停止、满电、限电量/金额/时间、余额不足或故障都会生成账单；云快充等待 `0x40` 确认，OCPP 由平台结算。

模拟器状态按平台设备 ID 分文件保存（`./data/simulator-state-{deviceId}.properties`），切换回原设备后继续补传旧账单。3D 场景与离线补传能力保留，详见 [3D视觉升级](docs/3d-visual-upgrade.md) 与下文。

## 可观测性

三端均通过 OTLP HTTP 发送 trace / log / metric 到共享 OpenTelemetry Collector，再由 Grafana + Tempo / Loki / Prometheus 展示。协议通道与运维平台不再写本地滚动日志，模拟器保留必要的本地运行日志。

```powershell
$env:IPE_OTLP_ENABLED='true'
$env:IPE_OTLP_ENDPOINT='http://100.101.91.38:4318'
.\scripts\start-local.ps1
```

- Grafana：<http://100.101.91.38:3000>
- Tempo 已实测收到 `iot-protocol-expert-ops` 的 HTTP 与定时任务链路。
- Loki 当前未返回标签，排障结果会把「集中日志不可用」作为待确认项，不影响业务帧流水与确定性解析。
- 本地无 Grafana 时可查看 `/actuator/telemetry`（local profile）与 `/actuator/prometheus`。[本地调试说明](docs/observability.md)

## 配置

平台配置位于 `iot-protocol-expert-ops/src/main/resources/application.yml`，协议通道与模拟器各有自己的 `application.yml`。常用环境变量：

| 变量 | 作用 |
|---|---|
| `IPE_OPS_DB_URL / IPE_OPS_DB_USER / IPE_OPS_DB_PASSWORD` | 运维平台 PostgreSQL |
| `IPE_OPS_BASE_URL` | 通道/模拟器访问运维平台 |
| `IPE_PROTOCOL_BASE_URL` | 运维平台访问协议通道 |
| `IPE_OPS_SERVICE_TOKEN` | 服务间身份 |
| `IPE_OPS_SIMULATOR_TOKEN` | 模拟器只读设备目录 |
| `IPE_SIMULATOR_DEVICE_ID` | 模拟器启动时自动选中的设备 |
| `IPE_MODEL_ENABLED / IPE_MODEL_BASE_URL / IPE_MODEL_API_KEY / IPE_MODEL_NAME` | 可选模型摘要（OpenAI 兼容） |
| `IPE_LOKI_URL` | 可选 Loki 查询地址 |
| `IPE_OTLP_ENABLED / IPE_OTLP_ENDPOINT / IPE_ENVIRONMENT` | OTLP 导出 |

数据库结构由 Flyway `V1__init.sql` 管理；启动时校验迁移版本，空库自动建表并写入演示站点、9 个产品（8 类设备 + 电表）、
覆盖 3D 沙盘的 11 台演示设备、告警规则、费率与演示用户。

> **本次是 V1 重建**：`device` / `gun_state` 表已被 `device_instance` / `device_channel` / `device_property_latest` 取代。
> 旧库需要清空 schema 后重新迁移（Flyway 会因校验和不一致拒绝启动）。

### 前端构建

```powershell
cd iot-protocol-expert-web
npm install
npm run dev          # 开发：http://localhost:5173，API 代理到 19082
npm run build:ops    # 生产：产物直接写入 ops 模块 static/，随 jar 一起发布
```

### 代码格式化

后端用 [Spotless](https://github.com/diffplug/spotless) + palantir-java-format（4 空格缩进、120 列），前端用 Prettier。

```powershell
mvn spotless:apply          # 统一格式化全部 Java 源码与 yml/properties 的空白
mvn spotless:check          # 只校验，不改写（verify 阶段已绑定，不合规会让构建失败）

cd iot-protocol-expert-web
npm run format              # 统一格式化前端源码
npm run format:check        # 只校验
```

编辑器另可读取仓库根目录的 `.editorconfig`；前端样式规则见 `iot-protocol-expert-web/.prettierrc.json`。

## 验证

```powershell
mvn -B package
# 三端启动后，用演示账号访问 /ops，或按 docs/ops-platform.md 的验收步骤执行。
```

已在本地完成：

- 云快充：模拟器选桩 → 登录 → 插枪 → 0x31 启动 → 实时计量 → 停止 → 0x3B → 订单 `SETTLED` + 账单 `DEVICE_BILL`。
- OCPP：同编号设备 → BootNotification → 平台远程启动 → StartTransaction → MeterValues → StopTransaction → 账单 `PLATFORM_ESTIMATE`。
- 同站点两台同文本编号设备切换后，仅当前设备在线，历史订单与账单保留在原平台设备 ID。
- 排障：按订单号定位设备、订单、0x3B/0x40 报文与账单，输出最后一个有证据环节。
- OTLP：运维平台 trace 已进入 Tempo。

## 已知边界

- 首版不连接真实充电桩资金账户；模型摘要默认关闭，失败时返回确定性降级结果。
- 云快充实现有验收样本的帧子集；3DES、扫描件 OCR、离线刷卡/双枪并充/远程升级的完整回归不在首版。
- 集中日志（Loki）当前未形成可用标签，排障会明确标注日志证据不足。
- 历史 H2 数据迁移需按 `docs/ops-platform.md` 的迁移原则执行，首版未自动搬迁旧库。

## 文档索引

- [运维平台与排障](docs/ops-platform.md)：边界、数据模型、内部 API、页面、验收、迁移
- [可观测性](docs/observability.md)：OTLP、Grafana、指标与日志
- [OCPP 1.6J](docs/ocpp16.md)：动作、命令与模拟器
- [协议覆盖](docs/protocol-coverage.md)：云快充帧与解码入口
- [协议决策](docs/protocol-decisions.md)：CRC、字段冲突、账单重试
- [3D 方案](docs/3d-options.md)、[3D 视觉升级](docs/3d-visual-upgrade.md)
- [验收记录](docs/verification.md)