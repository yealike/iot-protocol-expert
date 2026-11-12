# OCPP 1.6J 接入说明

平台从「单一云快充 TCP 服务」升级为多协议服务端：一个协议一个监听器，共用设备、枪状态、订单、交易与帧流水。当前内置：

| 协议 | 传输 | 默认地址 | 设备寻址 |
|---|---|---|---|
| 云快充 1.6 | Netty TCP | `127.0.0.1:8768` | 登录帧中的 14 位桩号 |
| OCPP 1.6J | Netty WebSocket | `ws://127.0.0.1:8769/ocpp/{chargePointId}` | URL 路径中的桩号 |

`GET /api/protocol/servers` 返回协议、端口、启用与运行状态；`GET /api/ocpp/catalog` 返回 OCPP 动作目录。

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

## OCPP 运维接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ocpp/{桩号}/status` | 在线、枪数、厂商型号、配置键 |
| POST | `/api/ocpp/{桩号}/reset` | `{type:"Soft"\|"Hard"}` |
| POST | `/api/ocpp/{桩号}/unlock` | `{connectorId:1}` |
| GET | `/api/ocpp/{桩号}/configuration?key=HeartbeatInterval` | 读取配置 |
| POST | `/api/ocpp/{桩号}/configuration` | `{key,value}` |
| POST | `/api/ocpp/{桩号}/availability` | `{connectorId,type:"Operative"\|"Inoperative"}` |
| POST | `/api/ocpp/{桩号}/trigger` | `{requestedMessage,connectorId}` |
| POST | `/api/ocpp/{桩号}/clear-cache` | 清空设备配置缓存 |
| POST | `/api/ocpp/{桩号}/data-transfer` | `{vendorId,messageId,data}` |
| POST | `/api/ocpp/{桩号}/diagnostics` | `{location,retries,retryInterval}` |
| POST | `/api/ocpp/{桩号}/firmware` | `{location,retrieveDate}` |
| POST | `/api/ocpp/{桩号}/raw-call` | `{action,payload}` 透传任意动作 |

平台启停统一走 `/api/devices/{桩号}/start|stop`，服务端按设备 `protocol` 路由到云快充或 OCPP。OCPP 1.6J 不支持并充，`parallel-start` 会明确返回不支持。

## 模拟器协议切换

模拟器页面右上角新增协议选择：

- `ykc16`：沿用原 TCP 客户端与帧流水。
- `ocpp16j`：使用 JDK WebSocket 连接 `ws://平台:8769/ocpp/{桩号}`，完成 BootNotification、Heartbeat、StatusNotification、Authorize、StartTransaction、MeterValues、StopTransaction 闭环，并响应平台远程启动/停机、Reset、TriggerMessage、GetConfiguration 等命令。

切换时先按旧协议结束在充订单，再断开旧连接、重建新会话；插枪状态与车辆参数保留，订单、交易和报文流水进入新的协议上下文。`POST /api/protocol {"protocol":"ocpp16j"}` 也可直接切换。

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
| `pending-timeout-seconds` | 10 | 下行 CALL 等应答 |

模拟器 `ipe.simulator.*`：

| 参数 | 默认值 | 说明 |
|---|---|---|
| `protocol` | `ykc16` | `ykc16` / `ocpp16j` |
| `ocpp-port` / `ocpp-path` | 8769 / `/ocpp` | OCPP 目标地址 |
| `platform-host` | 127.0.0.1 | 两种协议共用 |

## 边界

- `Authorize` 为演示实现，未接入真实卡账户或白名单。
- OCPP 1.6J 未实现预约、智能充电配置、本地白名单同步、诊断文件上传与真实固件下载。
- `MeterValues` 金额为平台按当前费率估算值，不代表真实结算账单；最终金额仍以订单结算结果为准。
- 云快充与 OCPP 共用同一设备档案；同一桩号切换协议时，平台以最后一次连接协议为准。