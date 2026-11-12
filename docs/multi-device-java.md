# 多设备模拟与协议采集

模拟行为属于 `iot-protocol-expert-simulator`；协议解码和属性转发属于 `iot-protocol-expert-protocol`。协议与点表先阅读工作区相邻目录“数据协议文档”中的《00-阅读入口》和《01-当前已实现协议》。Victron 点表仅为公开资料示例，未经过真实设备联调。

## 本地运行

1. 在隔离测试库中通过运维平台创建设备，设备编码建议和协议编号相同，勾选“允许模拟数据”；需要模拟指令时还需勾选“允许远程操作”。

| 产品 | 协议设备编号 | 产品协议 |
|---|---|---|
| 光伏逆变器 | SIM-PV-001 | MODBUS_TCP |
| 风机 | SIM-WIND-001 | MODBUS_TCP |
| 储能柜 | SIM-ESS-001 | MODBUS_TCP |
| 电表 | SIM-METER-001 | MODBUS_TCP |
| 地锁 | SIM-LOCK-001 | MQTT |
| 道闸 | SIM-GATE-001 | MQTT |
| 摄像机 | SIM-CAM-001 | ONVIF（模拟器用 MQTT 模拟状态，不实现真实 ONVIF） |

2. 编译前端及后端后，在启动三端的 PowerShell 设置环境变量。`IPE_EDGE_CONFIG` 与 `IPE_MQTT_CONFIG` 分别指向协议模块的 JSON 配置文件；若不配置，协议采集器不会启动。只需 Modbus 时可不设置 MQTT 相关变量。

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:IPE_SIM_MULTI_ENABLED='true'
$env:IPE_EDGE_CONFIG=(Resolve-Path '.\iot-protocol-expert-protocol\src\main\resources\collector-examples\simulator-modbus.json').Path
$env:IPE_SIM_COMMAND_TOKEN = Read-Host '设置本机模拟命令令牌'
# MQTT 为可选：启用仅供本机模拟的轻量 Broker，或改用已有 Broker。
$env:IPE_SIM_BROKER_ENABLED='true'
$env:IPE_MQTT_CONFIG=(Resolve-Path '.\iot-protocol-expert-protocol\src\main\resources\collector-examples\simulator-mqtt.json').Path
$env:IPE_SIM_MQTT_URL='tcp://127.0.0.1:1883'
& 'D:\develop\apache-maven-3.8.8\bin\mvn.cmd' -B package
.\scripts\start-local.ps1
```

`start-local.ps1` 会把当前环境变量传给本机 ops、protocol 和 simulator。MQTT 如需用户名密码，分别设置 `IPE_MQTT_USERNAME` 和 `IPE_MQTT_PASSWORD`。可通过 IPE_SIM_BROKER_ENABLED=true 启用仅绑定本机的模拟 Broker（默认端口 1883）；它仅实现 MQTT 3.1.1 的受限子集和模拟主题，不具备认证或持久化能力，不能用于生产。协议采集器在 Broker 尚未启动时会重试；外部 Broker 的生产配置要求 TLS 与 JVM 信任库，当前不支持配置 PEM 文件。

3. 浏览 `http://127.0.0.1:19081/multi-device.html`。光伏、风机、储能和电表在本机 15020 端口提供模拟 Modbus TCP；地锁、道闸及摄像机状态通过配置的 Broker 发布。运维平台设备详情页可对已授权的模拟设备下发命令，结果可在指令记录中查看。故障与暂停按钮需要输入 `IPE_SIM_COMMAND_TOKEN`。

4. 协议通道与运维平台间使用 `IPE_OPS_SERVICE_TOKEN`，本机启动脚本可设置本地默认值；不要将这个高权限令牌分发至真实终端。有效属性上报会更新设备在线时间，`IPE_GATEWAY_ONLINE_TTL_SECONDS` 可调整非充电设备离线判定（默认 60 秒）。

## 真实设备范围

- 真实 Modbus 点位模板见 `iot-protocol-expert-protocol/src/main/resources/collector-examples/`。请复制模板，在厂家资料和现场网络核对后设置设备地址、Unit ID、寄存器、无效值、倍率和协议编号；绝不能把模拟地址当作厂家协议。
- MQTT 以精确主题绑定设备，配置字段映射与 TLS；未实现业务幂等、持久消息队列和离线补传。
- 真实设备远程控制、RTU、OPC UA、真实 ONVIF、视频及生产级网关鉴权尚未实现。模拟命令不可用于真实设备。

## 验证

运行 `mvn -B test`。Java 测试覆盖 Modbus 报文、MQTT 订阅映射、本机模拟 Broker 端到端、模拟命令身份与状态、数据验证和原有充电模拟；仍需数据库、外部生产 Broker 联调与真机验收。

