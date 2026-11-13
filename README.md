# iot-protocol-expert

充电桩协议平台，由运营平台、协议通道和模拟器组成。使用 Java 21、Spring Boot、PostgreSQL 和独立 React 前端。

| 模块 | 职责 | 默认端口 |
| --- | --- | --- |
| `iot-protocol-expert-ops` | 充电桩、订单、账单、报文、设备命令及运营管理 | HTTP 19082 |
| `iot-protocol-expert-protocol` | 连接、编解码、协议适配和上下行转发，不持久化业务数据 | HTTP 19080；云快充 TCP 8768；OCPP WS 8769 |
| `iot-protocol-expert-simulator` | 从运营平台选择设备，按绑定协议模拟上报，支持多桩并发 | HTTP 19081 |
| `iot-protocol-expert-ai` | AI 助手与 Agent 能力平台：会话、Run 调度、受控工具、协议知识检索 | HTTP 19083 |
| `iot-protocol-expert-codec` | 协议通道与模拟器共用的纯编解码库 | 无 |
| `iot-protocol-expert-web` | 运营平台独立前端 | 开发端口 5173 |

协议通道通过 `ProtocolServer` SPI 管理云快充 1.6 和 OCPP 1.6J。设备数据与账单通过内部接口提交运营平台；业务数据由运营平台保存。

AI 服务复用运营平台的数据库实例（独立 `ipe_ai` schema），身份校验与业务数据读取统一通过 Higress MCP。运维与 AI 都使用 Spring Cloud Alibaba 官方 starter 注册到 Nacos，AI 再从 Nacos MCP Registry 订阅 `ipe-ops-rest` 得到入口与工具清单（`IPE_NACOS_DISCOVERY_ENABLED` 控制本地是否连接）。模型默认关闭，关闭时执行确定性工具降级。实现范围、接口与限制见 `docs/ai-assistant.md`。

## 本地运行

需要 JDK 21、Maven、PostgreSQL 和 TDengine 3.x。应用数据库账号需有创建 `ipe_ops` 和 `ipe_ai` schema 的权限。将 `.env.example` 复制为 `.env-local` 并填写本地连接、令牌和 JDK 路径；启动脚本会读取它，IDE 启动时需自行加载。订单时序使用 TDengine WebSocket 接口，应用账号需有创建数据库和表的权限。然后运行：

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\develop\apache-maven-3.8.8\bin\mvn.cmd' -B verify
.\scripts\start-local.ps1 -DeviceId 1
```

前端在 `iot-protocol-expert-web` 下执行 `npm ci`、`npm run dev`；生产构建使用 `npm run build`。本地数据库、服务地址和令牌在 `.env-local` 中配置；启动参数可覆盖相应配置。

AI 服务应用账号需要创建 `ipe_ai` schema 的权限；可用 `IPE_AI_DB_*` 单独指定连接。启用大模型时设置 `IPE_MODEL_ENABLED=true` 与 `IPE_MODEL_BASE_URL`、`IPE_MODEL_API_KEY`、`IPE_MODEL_NAME`（OpenAI 兼容），密钥只放本机环境变量。首次使用协议问答前，以 ADMIN 身份调用 `POST /api/ai/knowledge/index` 建立索引。

首次运行将在独立 `ipe_ops` schema 初始化演示数据；不会清理已有 `public` 数据。现有 `public` 数据不会自动迁移。订单回放仅包含新接入的采样，显示的接收时间与设备时间分别标识。

## 模拟器

登录运营平台后从「充电模拟器」进入。模拟器服务仍需启动，运营平台通过 `IPE_SIMULATOR_BASE_URL`（默认 `http://127.0.0.1:19081`）连接模拟器。

设备列表由运营平台提供，选择设备后使用其协议和协议设备编号。主界面支持插枪、启动、停止、实时上报和故障模拟；云快充故障值为 16 位故障位图，OCPP 为标准 `StatusNotification.errorCode`。并发模拟区域可添加其他设备，分别控制枪和上报数据；同一设备不能同时出现在主模拟器和并发列表。状态与待补传账单按设备 ID 分文件保存。

具体协议能力和已知限制见 `docs/ocpp16.md`、`docs/protocol-coverage.md` 和 `docs/protocol-decisions.md`。
