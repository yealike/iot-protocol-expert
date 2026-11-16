# 多协议运维平台与智能排障（首版）

本文记录 `iot-protocol-expert-ops` 的实施口径、接口契约、本地运行与验收结果。设计依据为上级目录 `设计文档/多协议充电桩运维与智能排障平台设计方案.md`。

## 1. 服务划分

| 服务 | 端口 | 持久化 | 说明 |
|---|---|---|---|
| `iot-protocol-expert-codec` | — | 无 | 纯编解码库，协议通道与运维平台共用 |
| `iot-protocol-expert-protocol` | 19080 / TCP 8768 / ws 8769 | **无** | 连接、校验、编解码、会话、应答关联、下行发送 |
| `iot-protocol-expert-ops` | 19082 | PostgreSQL | 电站、设备、订单、账单、报文、命令、排障、用户、审计 |
| `iot-protocol-expert-simulator` | 19081 | 模拟端状态文件 | 从平台选桩，按档案协议接入，模拟充电与补传 |

协议通道已移除 H2、JPA、业务 Repository、平台静态控制台与本地滚动日志。通道内仅保留内存会话与短时发送缓冲；进程重启后依赖设备重连。

## 2. 接入与身份

- 协议通道以 `协议编码 + 协议侧设备编号` 查档：云快充使用登录帧桩号，OCPP 使用连接路径中的 `chargePointId`。
- 平台设备 ID（`device.id`）是订单、账单、枪状态、报文、命令的业务主键。
- 同一文本编号可对应两台独立设备；切换模拟器设备后只影响当前设备。
- 连接事件携带 `sessionId`；旧连接的断开事件不会覆盖新会话。
- 默认要求预登记；演示环境可用 `IPE_AUTO_REGISTER=true` 自动登记为待建档设备。

### 内部接口（协议通道 → 运维平台）

请求头：`X-Ops-Service-Token`。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/internal/devices/login` | 云快充登录，返回设备档案 |
| GET | `/internal/devices/resolve` | 按协议 + 编号查档 |
| POST | `/internal/devices/touch` | 心跳刷新最后通信时间 |
| POST | `/internal/devices/offline` | 断线，sessionId 校验 |
| PATCH | `/internal/devices/patch` | 参数设置成功后同步允许工作/功率 |
| POST | `/internal/orders/start` | 0x31 启动判定，返回订单号与失败原因 |
| GET | `/internal/orders/active` | 按设备 + 枪查进行中订单 |
| GET | `/internal/orders/by-no/{orderNo}` | 按平台流水号查订单 |
| GET | `/internal/orders/by-ocpp` | 按 OCPP 整数交易号查订单 |
| POST | `/internal/orders/{no}/bind-ocpp` | 绑定 OCPP 整数交易号 |
| POST | `/internal/orders/{no}/meter` | 更新实时电量/金额 |
| POST | `/internal/orders/{no}/charging` | 进入充电态 |
| POST | `/internal/orders/{no}/stop` | 停止 |
| POST | `/internal/orders/{no}/fail` | 启动失败 |
| POST | `/internal/orders/{no}/settle` | 结算 |
| POST | `/internal/bills/ykc` | 0x3B 交易记录入账（幂等） |
| POST | `/internal/frames` | 原始报文与解析摘要留存 |
| POST | `/internal/gun-states` | 枪状态 + 订单实时计量 |
| GET | `/internal/rate-models/active` | 当前费率模型 |
| GET | `/internal/rate-models/verify` | 费率模型一致性 |
| GET | `/internal/rate-models/price` | 指定时刻单价（OCPP 估算） |
| POST | `/internal/ocpp/boot` | BootNotification |
| POST | `/internal/ocpp/heartbeat` | OCPP 心跳 |
| POST | `/internal/ocpp/status` | StatusNotification |
| POST | `/internal/ocpp/authorize` | Authorize |
| POST | `/internal/ocpp/start-transaction` | StartTransaction |
| POST | `/internal/ocpp/meter-values` | MeterValues |
| POST | `/internal/ocpp/stop-transaction` | StopTransaction 结算 + 账单 |

### 下行命令（运维平台 → 协议通道）

`POST /internal/commands`，请求体：

```json
{"protocol":"YKC16","protocolDeviceId":"32010600000001","type":"remote-start",
 "params":{"orderNo":"...","gunNo":"01","idTag":"IPE-APP","balance":"1000.00"},
 "operator":"admin"}
```

通道只发送协议帧并解释应答，不建单、不记账。运维平台先保存 `command_record`，再投递并回写 `SUCCESS / REJECTED / TIMEOUT / WAITING_STATE / UNKNOWN`。

## 3. 数据模型

Flyway：`iot-protocol-expert-ops/src/main/resources/db/migration/V1__init.sql`。

| 表 | 关键约束/字段 |
|---|---|
| `station` | 站点编码唯一，时区默认 `Asia/Shanghai` |
| `device` | `unique(protocol, protocol_device_id)`、`unique(sn)`、站点、启用、允许模拟、枪数、在线与会话 |
| `gun_state` | `unique(device_id, gun_no)`，最新状态与状态时间 |
| `charge_order` | 平台流水号唯一，设备 ID + 枪号 + 发生时的站点 + 协议交易标识 |
| `bill` | `unique(protocol, source_type, source_transaction_no)`，订单可空，金额类型（`DEVICE_BILL` / `PLATFORM_ESTIMATE`） |
| `frame_log` | 设备、协议、方向、帧类型/消息 ID、原始与下行报文、trace_id |
| `command_record` | 命令类型、请求、状态、发送/应答时间、操作人 |
| `rate_model` | 48 个半小时时段与四段单价 |
| `ops_user` | 用户名、BCrypt 密码、角色 |
| `audit_log` | 操作人、动作、对象、结果、时间 |
| `protocol_doc` / `protocol_doc_section` | 协议规范元数据、帧/Action、原文与 locator |
| `trouble_case` | 排障输入与结果快照 |

### 账单口径

- 云快充 `0x3B`：按 `协议 + 来源类型 + 交易流水号` 幂等；同事务内结算订单并写账单，金额来源 `DEVICE_BILL`。无对应订单时仍入库并标记「按离线账单入库」。
- OCPP `StopTransaction`：平台估算金额 = 电量 × 停充时刻费率；订单与账单同事务写入，金额来源 `PLATFORM_ESTIMATE`，账单 `rawPayload` 记录费率模型编号与整数交易号。
- 站点统计按订单/账单实际结束时间、站点本地日期左闭右开；迁站只影响后续订单，历史订单保留 `station_id` 快照。

## 4. 页面与权限

- `/ops`：电站、设备、订单与账单、智能排障、命令记录。
- 角色：`VIEWER` 只读；`OPS` 远程启停 + 排障；`ADMIN` 维护电站/设备/用户。Spring Security 表单登录，密码 BCrypt。
- 写操作写 `audit_log`；命令记录区分等待状态与未知状态，重试前应先核对设备状态。
- 模拟器设备目录 `/api/ops/simulator/devices` 使用独立只读令牌，不建立用户会话。

## 5. 智能排障

输入：SN / 平台设备 ID / 订单号、HEX 或 OCPP JSON、问题、时间范围。

确定性流程：

1. 订单号 → 订单 → 平台设备；否则按 SN / 协议 + 编号定位。
2. 报文释义：云快充完整帧解码（CRC/3DES），OCPP 解 `[2/3/4, uniqueId, ...]`。
3. 查订单状态、开始/结束/结算、账单。
4. 查时间窗内报文：云快充 `0x3B` 与 `0x40`；OCPP `StopTransaction` 与同消息 ID 应答。
5. 输出处理时间线，标记 `CONFIRMED / MISSING / UNKNOWN`，给出最后一个有证据的环节。
6. 规范片段按协议版本 + 帧/Action 精确命中，再补关键词检索。
7. 待确认项明确包含：订单未找到、停充后账单缺失、未找到 0x3B/0x40、日志不可用、模型不可用。

模型摘要为可选（OpenAI 兼容 `/chat/completions`）。未配置或调用失败时，页面继续展示解析与时间线，并标记「确定性降级」。

## 6. 本地运行

### 6.1 PostgreSQL

本机 PostgreSQL 示例（密码只在本机环境中设置，不要写入仓库）：

```bash
: "${IPE_OPS_DB_PASSWORD:?请先在本机 shell 中设置 IPE_OPS_DB_PASSWORD}"
docker run -d --name ipe-postgres --restart unless-stopped \
  -e POSTGRES_DB=ipe_ops -e POSTGRES_USER=ipe -e POSTGRES_PASSWORD="$IPE_OPS_DB_PASSWORD" \
  -p 55432:5432 -v ipe_pg_data:/var/lib/postgresql/data postgres:16
```

连接串默认指向本机 `jdbc:postgresql://127.0.0.1:55432/ipe_ops`，用户名默认为 `ipe`。请在本机设置 `IPE_OPS_DB_PASSWORD`，生产环境使用独立密钥管理并限制数据库来源网段。

### 6.2 启动

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -B package
.\scripts\start-local.ps1 -DeviceId 1
```

- 运维平台 API <http://localhost:19082>，前端 <http://localhost:5173>，协议通道 19080，模拟器 19081。
- 模拟器右上角可切换设备；同一实例同一时刻只运行一台选中设备。
- 停止：`.\scripts\stop-local.ps1`。

### 6.3 OTLP

```powershell
$env:IPE_OTLP_ENABLED='true'
$env:IPE_OTLP_ENDPOINT='http://127.0.0.1:4318'
.\scripts\start-local.ps1
```

三端使用 Spring Boot 原生 OpenTelemetry 自动装配（Micrometer Tracing/Metrics、OTLP HTTP exporter、结构化日志、JDBC Observation），与协议通道、模拟器原有接法一致。协议通道与运维平台不写本地滚动日志。

## 7. 验收记录（2026-11-11，本地）

环境：JDK 21、PostgreSQL 16.14、协议通道 19080、模拟器 19081、运维平台 19082、本机 OTLP Collector `127.0.0.1:4318`。

| 项目 | 结果 |
|---|---|
| 空库启动 | Flyway 自动建表，seed 电站/两台同编号设备/费率/用户 |
| 云快充完整链路 | 选桩 → 登录 → 插枪 → 0x31 → 实时 → 停止 → 0x3B → 订单 `SETTLED`、账单 `DEVICE_BILL`（38.6468 kWh / 42.5115 元） |
| OCPP 完整链路 | 选桩 → BootNotification → 平台远程启动 → StartTransaction → Meter 值 → StopTransaction → 账单 `PLATFORM_ESTIMATE`（18.839 kWh / 20.7229 元） |
| 同编号隔离 | 切换后仅 OCPP 设备在线，云快充设备离线；两台设备订单/账单互不影响 |
| 命令 | `remote-start` 记录 `SUCCESS`，设备随后上报 StartTransaction |
| 站点统计 | 2 笔订单，电量 57.4858，订单金额 63.2344，桩账单 42.5115，平台估算 20.7229 |
| 排障 | 订单号定位设备与订单，时间线确认 0x3B/0x40/账单，最后环节「交易记录确认 0x40」 |
| OTLP | Tempo 查到 `iot-protocol-expert-ops` 的 HTTP 请求与定时任务 trace |
| Loki | 当前实例未返回标签；排障明确提示日志证据不足，业务帧流水仍可用 |

## 8. 迁移原则

1. 先备份旧 H2 文件，在副本中导出设备、订单、账单与帧流水。
2. 按旧记录的协议证据回填 `protocol + protocol_device_id`；无法可靠关联的记录保留来源并列出清单。
3. 切换前后核对设备数、关联订单数、账单金额与未归属条数。
4. 切换时保留旧库只读快照；切换后产生的新记录需纳入回滚方案，避免恢复旧快照时丢失新增数据。

首版未自动搬迁旧 H2 数据。

## 9. 已知边界

- 主动告警、工单、批量导入、跨时段精确计费、支付结算、多 Agent、第三种协议在后续迭代。
- 设备离线/故障直接显示在列表；持续离线、停充账单超时等主动告警未实现。
- 模型摘要为可选能力，默认关闭；不参与金额与时间等硬事实。
- 集中日志检索依赖 Loki，当前环境日志链路未返回数据，排障会降级为业务帧流水证据。
- 通道内报文留存为同步 HTTP；平台不可达时该帧仅记录通道日志，不阻塞设备会话。

## 文档更新说明

本文描述的是改造前的运维平台实现。设备域已经在 V1 重建中改为
「产品 + 物模型 + 设备实例 + 通道 + 属性」模型，`device`、`gun_state` 两张表不再存在，
相关章节（设备表结构、枪状态、设备接口）以 [物模型与设备域设计](thing-model.md) 为准。

接口层面的变化：

- 设备查询改为 `POST /api/ops/devices/query`，返回统一分页结构；
- 设备详情 `GET /api/ops/devices/{id}` 返回档案 + 物模型 + 通道 + 属性 + 事件 + 告警 + 指令；
- 新增产品与物模型接口 `/api/ops/products/**`、告警接口 `/api/ops/alarms/**`、总览 `/api/ops/overview`；
- `/internal/**` 与 `/api/ops/simulator/**` 的契约保持不变，协议通道与模拟器无需改调用方式。
