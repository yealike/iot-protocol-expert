# OCPP 1.6J 接入说明

平台从「单一云快充 TCP 服务」升级为多协议服务端：一个协议一个监听器，共用设备、枪状态、订单、交易与帧流水。当前内置：

| 协议 | 传输 | 默认地址 | 设备寻址 |
|---|---|---|---|
| 云快充 1.6 | Netty TCP | `127.0.0.1:8768` | 登录帧中的 14 位桩号 |
| OCPP 1.6J | Netty WebSocket | `ws://127.0.0.1:8769/ocpp/{chargePointId}` | URL 路径中的桩号 |

`GET /api/protocol/servers` 返回协议、端口、启用与运行状态，`GET /api/protocol/types` 返回协议类型。
`GET /api/protocol/catalog` 与 `POST /api/protocol/decode` 是云快充帧目录和报文解码调试入口，不是 OCPP 动作接口。

## 协议无关内核

```
core/
  ProtocolType            协议枚举（YKC16 / OCPP16J）
  ProtocolServer          协议服务端 SPI：protocol / enabled / port / start / stop
  ProtocolServerManager   统一生命周期，按配置启动各协议监听
  OnlineDeviceDirectory   在线会话抽象；YKC 与 OCPP 各自实现，聚合后供控制台查询
  ChargingCommandRouter   按设备档案中的 protocol 路由启停命令
```

新增协议只需实现 `ProtocolServer`，把上行消息转换成对 `DeviceService`、`GunStateService`、`OrderService`、`RateModelService` 的调用，并在 `DeviceEntity.protocol` 中登记协议标识。云快充专用字段和帧编解码不会被其它协议引用。

## 已实现的 OCPP 1.6J 动作

设备 → 平台：

- `BootNotification`：自动建档/更新厂商、型号、序列号、固件版本，返回 `Accepted`、服务器时间和 300 秒心跳间隔。
- `Heartbeat`：刷新设备最后在线时间。
- `StatusNotification`：映射 `Available / Preparing / Charging / SuspendedEV / SuspendedEVSE / Finishing / Reserved / Unavailable / Faulted` 到平台枪状态。
- `Authorize`：演示环境接受任意非空 `idTag`。
- `StartTransaction`：优先复用远程启动创建的待启动订单，否则新建订单；分配整数 `transactionId` 并绑定起始电表（Wh）。
- `MeterValues`：解析 `Energy.Active.Import.Register`、`Power.Active.Import`、`Voltage`、`Current.Import`、`SoC`，换算 kWh 后写入订单和枪状态；金额按平台当前生效费率估算。
- `StopTransaction`：按 `meterStop - meterStart` 结算订单，`Remote / EmergencyStop / EVDisconnected / PowerLoss / Reboot / Local` 映射到平台停止原因。
- `DataTransfer`、`DiagnosticsStatusNotification`、`FirmwareStatusNotification`：接收并应答，不改变业务状态。

平台 → 设备（REST，见下表）：

- `RemoteStartTransaction`、`RemoteStopTransaction`
- `Reset`、`UnlockConnector`、`ChangeAvailability`
- `ChangeConfiguration`、`GetConfiguration`、`ClearCache`
- `TriggerMessage`、`DataTransfer`、`GetDiagnostics`、`UpdateFirmware`

下行 CALL 通过 `uniqueId` 关联 `CALLRESULT / CALLERROR`，超时使用 `ipe.protocol.pending-timeout-seconds`。

## 运维接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/protocol/servers` | Gateway：协议监听状态 |
| GET | `/api/protocol/types` | Gateway：支持的协议类型 |
| GET | `/api/protocol/catalog` | Gateway：云快充帧目录 |
| POST | `/api/protocol/decode` | Gateway：云快充线上报文或帧体解码 |
| POST | `/api/ops/commands/start` | OPS：远程启动并创建订单 |
| POST | `/api/ops/commands/stop` | OPS：远程停止 |
| POST | `/api/ops/commands` | OPS：下发通用 OCPP/云快充命令 |
| GET | `/api/ops/commands?deviceId={id}` | OPS：查询命令记录；`deviceId` 可省略 |
| POST | `/internal/commands` | Gateway 内部接口，仅由 OPS 服务间调用 |

平台启停使用设备 ID，不使用桩号路径：

```json
{"deviceId":123,"gunNo":"01","idTag":"APP-USER","physicalCard":"","balance":"1000.00"}
```

上例用于 `POST /api/ops/commands/start`；`POST /api/ops/commands/stop` 只需 `deviceId` 和 `gunNo`。服务端按设备档案中的 `protocol` 路由到云快充或 OCPP。OCPP 1.6J 不支持并充，`parallel-start` 会明确返回不支持。

通用命令体为 `{deviceId,type,params}`。OCPP 支持以下 `type`：

| `type` | `params` |
|---|---|
| `remote-start` | `gunNo`、可选 `idTag` |
| `remote-stop` | `transactionId`（快捷停止接口自动补齐；直接通用命令需显式提供） |
| `reset` | `mode:"Soft"\|"Hard"` |
| `unlock` | `connectorId` |
| `configuration` | 读取：`method:"GET",keys:[...]`；修改：`key,value` |
| `availability` | `connectorId,type:"Operative"\|"Inoperative"` |
| `trigger` | `requestedMessage,connectorId` |
| `clear-cache` | 无 |
| `data-transfer` | `vendorId,messageId,data` |
| `diagnostics` | `location,retries,retryInterval` |
| `firmware` | `location,retrieveDate` |
| `raw-call` | `action,payload` |

设备返回 `CALLERROR`、无应答，或顶层 `status` 为 `Rejected`、`NotSupported`、`NotImplemented`、
`UnlockFailed`、`UnknownMessageId`、`UnknownVendorId` 时，命令记录为 `REJECTED`。`RebootRequired`、
`Scheduled`、`Unlocked` 等动作级成功状态仍按成功处理；没有顶层 `status` 的合法应答也会保留原始响应。

### 启动与停止的确认边界

OCPP `RemoteStartTransaction` 返回 `Accepted` 只表示桩已受理，订单保持 `PENDING`；只有设备上送并通过 `StartTransaction` 后订单才进入 `CHARGING`。如果设备随后鉴权或启动交易失败，不会出现“命令 Accepted 但已显示充电成功”的伪状态。

OCPP `RemoteStopTransaction` 返回 `Accepted` 也只表示桩已受理，订单保持 `CHARGING`；只有设备上送 `StopTransaction` 并通过交易归属、设备、枪号和订单状态校验后才结算并生成账单。未知交易号、跨设备交易、非 OCPP 订单和不允许结算的订单状态会返回 OCPP `CALLERROR`。同一已结算交易的重复 `StopTransaction` 幂等返回原结果，不重复建账。

### 并发与一致性边界

- 订单绑定交易号、进入充电态、停止、失败、结算和过期转换都在事务内按订单行执行 `SELECT ... FOR UPDATE`。已进入 `FAILED` 或 `SETTLED` 的订单不能复活，`StopTransaction` 后的迟到 `MeterValues` 也不能改回 `CHARGING`。
- OCPP 整数交易号在 PostgreSQL advisory lock 内分配。`V4__ocpp_transaction_uniqueness.sql` 保证同一设备内的非空交易号唯一，允许不同设备存在相同整数交易号；停止交易始终按设备 ID、交易号和协议定位。
- `V5__active_charge_order_uniqueness.sql` 保证同一设备的同一把枪最多有一张 `PENDING` 或 `CHARGING` 订单。并发远程启动或启动回调只有一方能建立活动订单，其余请求按冲突处理。
- 远程启动命令结果携带 `outcome=ACCEPTED|REJECTED|TIMEOUT|WAITING_STATE|UNKNOWN`。只有设备明确拒绝才把订单标为失败；超时、断线、未知和“等待插枪”都保持 `PENDING`，最终由设备后续交易事件或过期扫描收敛。
- 过期扫描直接查询超过阈值的 `PENDING` 订单，再逐单加锁复核，不会因只看最新一批订单而永久锁住较早的枪。
- 报文留存是 best-effort 诊断数据，使用独立的有界执行器。队列满时丢弃诊断帧并计入 `ipe.protocol.frame.retention.dropped`，不会关闭协议连接或阻止 CALLRESULT；OPS 请求失败单独计入 `ipe.protocol.frame.retention.failed`。

这些约束覆盖本轮已复现的启动超时、重复订单、迟到计量和重复停止路径。多实例长时间压力、全设备协议交叉回归仍属于后续验证范围；特别是迟到的 `Available` 状态与活动订单之间存在短暂表示不一致的可能。

## 模拟器协议切换

模拟器当前使用的协议由所选设备档案决定：

- `ykc16`：沿用原 TCP 客户端与帧流水。
- `ocpp16j`：使用 JDK WebSocket 连接 `ws://平台:8769/ocpp/{桩号}`，完成 BootNotification、Heartbeat、StatusNotification、Authorize、StartTransaction、MeterValues、StopTransaction 闭环，并响应平台远程启动/停机、Reset、TriggerMessage、GetConfiguration 等命令。

换桩前须先结束在充订单；换桩时保存当前设备检查点、断开旧连接，再加载目标设备档案和会话。实际操作使用
`POST /api/device/select {"deviceId":123}`；旧客户端调用的 `POST /api/protocol {"protocol":"ocpp16j"}`
现在返回 HTTP 400，提示从设备列表重新选桩。

OCPP 停止交易采用持久化 outbox：模拟器在发送 `StopTransaction` 前保存交易号、枪号、`meterStop`、结束时间和原因；收到成功 `CALLRESULT` 后才移出队列。`CALLERROR`、超时、断线、非 `Accepted` 的 `idTagInfo.status` 都不会删除记录。WebSocket 重连且 BootNotification 被接受后会立即补传，模拟器进程重启后也会从设备检查点恢复。`GET /api/state` 的 `pendingOcppStops` 可观察待传记录和剩余重试次数。

单次连接内的补传使用 `transaction-max-retries` 和 `transaction-retry-seconds` 限次重试。次数耗尽后记录仍持久化，但本次连接不再发送；下一次成功 BootNotification 会重置次数并继续补传。

OCPP 模式下费率模型没有对应的标准下发帧：模拟器禁用「保存到平台并下发」，平台使用当前生效的 48 段费率，按停止时刻所在时段估算金额。

## 配置

平台 `ipe.protocol.*`（`application.yml`）：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `ykc-enabled` / `tcp-port` | true / 8768 | 云快充监听 |
| `ocpp-enabled` / `ocpp-port` | true / 8769 | OCPP WebSocket 监听 |
| `ocpp-path` | `/ocpp` | 路径前缀 |
| `ocpp-default-connector-count` | 2 | BootNotification 未提供枪数时的默认值 |
| `ocpp-idle-timeout-seconds` | 300 | 读空闲断开 |
| `ops-timeout-ms` | 15000 | Gateway 调用 OPS 内部接口的超时，可由 `IPE_OPS_TIMEOUT_MS` 覆盖 |
| `pending-timeout-seconds` | 15 | 下行 CALL 等应答，可由 `IPE_PROTOCOL_PENDING_TIMEOUT_SECONDS` 覆盖 |

模拟器 `ipe.simulator.*`：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `protocol` | `ykc16` | `ykc16` / `ocpp16j` |
| `ocpp-port` / `ocpp-path` | 8769 / `/ocpp` | OCPP 目标地址 |
| `platform-host` | 127.0.0.1 | 两种协议共用 |
| `platform-proxy-timeout-seconds` | 60 | 模拟器页面转发平台命令时等待 OPS/Gateway 的最长时间 |
| `transaction-retry-seconds` | 30 | OCPP 停止交易等待补传的间隔 |
| `transaction-max-retries` | 3 | 单次成功连接内的最大补传次数 |

Gateway 到 OPS 的 `ipe.protocol.ops-timeout-ms` 默认 15000，OPS 到 Gateway 的
`ipe.ops.protocol-timeout-ms` 默认 20000，Gateway 等设备应答默认 15000。部署配置应保持
`Simulator 平台代理超时 > OPS 协议 HTTP 超时 > Gateway 设备应答超时 > 设备正常 ACK 时间`，避免调用方先失败、
而设备随后实际执行成功。Gateway 到 OPS 的 BootNotification 首次调用在最新冷启动复验中一次通过；仍保留重试兜底，
并建议在编排层等待 OPS readiness 后再启动设备连接。验证环境使用 90 秒 / 70 秒 / 45 秒，以覆盖真实 TCP 交互耗时。

## 边界

- `Authorize` 为演示实现，未接入真实卡账户或白名单。
- OCPP 1.6J 未实现预约、智能充电配置、本地白名单同步、诊断文件上传与真实固件下载。
- `MeterValues` 金额为平台按当前费率估算值，不代表真实结算账单；最终金额仍以订单结算结果为准。
- 云快充与 OCPP 共用同一设备档案；同一桩号切换协议时，平台以最后一次连接协议为准。
