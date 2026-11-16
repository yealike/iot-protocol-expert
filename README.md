# iot-protocol-expert

充电桩协议平台，由运营平台、协议通道和模拟器组成。使用 Java 21、Spring Boot、PostgreSQL 和独立 React 前端。

| 模块 | 职责 | 默认端口 |
| --- | --- | --- |
| `iot-protocol-expert-ops` | 充电桩、订单、账单、报文、设备命令及运营管理 | HTTP 19082 |
| `iot-protocol-expert-protocol` | 连接、编解码、协议适配和上下行转发，不持久化业务数据 | HTTP 19080；云快充 TCP 8768；OCPP WS 8769 |
| `iot-protocol-expert-simulator` | 从运营平台选择设备，按绑定协议模拟上报，支持多桩并发 | HTTP 19081 |
| `iot-protocol-expert-ai` | AI 助手与 Agent 能力平台：会话、Run 调度、受控工具、协议知识检索 | HTTP 19083 |
| `iot-protocol-expert-agent-runtime` | 在线 Agent 执行进程：发布准备、MCP/Skill 装配、模型调用和事件回写 | HTTP 19084 |
| `iot-protocol-expert-codec` | 协议通道与模拟器共用的纯编解码库 | 无 |
| `iot-protocol-expert-web` | 运营平台独立前端 | 开发端口 5173 |

协议通道通过 `ProtocolServer` SPI 管理云快充 1.6 和 OCPP 1.6J。设备数据与账单通过内部接口提交运营平台；业务数据由运营平台保存。

AI 服务复用运营平台的数据库实例（独立 `ipe_ai` schema）。在线 Agent 平台拆为 Control（19083）和 Runtime（19084）：Control 保存模型、凭据、MCP、Skill、Agent、发布、会话与 Run；Runtime 不访问数据库，只通过签名内部接口拉取 manifest、执行模型/MCP 并回写事件。Control 通过 Nacos 发现 Runtime；Control 注册 MCP/Skill 组件，Runtime 注册基础服务与精确 Agent 版本，再经 Higress MCP 访问 OPS；`IPE_AI_REGISTRY_ENABLED` 控制在线 Agent 平台注册，`IPE_NACOS_DISCOVERY_ENABLED` 控制 Spring Cloud 服务发现。实现范围、接口、配置和排障见 `docs/ai-assistant.md`，验收状态见 `docs/online-agent/implementation-log.md`。

## 本地运行

需要 JDK 21、Maven、PostgreSQL 和 TDengine 3.x。应用数据库账号需有创建 `ipe_ops`、`ipe_ai` 和 `ipe_simulator` schema 的权限。将 `.env.example` 复制为 `.env.local` 并填写本地连接、令牌和 JDK 路径；启动脚本会读取它，IDE 启动时需自行加载。订单时序使用 TDengine WebSocket 接口，应用账号需有创建数据库和表的权限。然后运行：

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\develop\apache-maven-3.8.8\bin\mvn.cmd' -B verify
.\scripts\start-local.ps1 -DeviceId 1
```

前端在 `iot-protocol-expert-web` 下执行 `npm ci`、`npm run dev`；生产构建使用 `npm run build`。本地数据库、服务地址和令牌在 `.env.local` 中配置；启动参数可覆盖相应配置。

AI 服务应用账号需要创建 `ipe_ai` schema 的权限；可用 `IPE_AI_DB_*` 单独指定连接。启用大模型时设置 `IPE_MODEL_ENABLED=true` 与 `IPE_MODEL_BASE_URL`、`IPE_MODEL_API_KEY`、`IPE_MODEL_NAME`（OpenAI 兼容），密钥只放本机环境变量。首次使用协议问答前，以 ADMIN 身份调用 `POST /api/ai/v1/knowledge/index` 建立索引。

### 在线 Agent 与 Runtime

需要 Nacos/Higress 时在 `.env.local` 显式设置 `IPE_AI_REGISTRY_ENABLED=true`、`IPE_AI_MCP_REGISTRY_ENABLED=true`，并保证两个进程使用同一个 `IPE_INTERNAL_SERVICE_KEY` 和 `IPE_CREDENTIAL_MASTER_KEY`；同时显式设置 `IPE_OPS_BASE_URL=http://127.0.0.1:19082`。OPS 还必须启用 `IPE_NACOS_DISCOVERY_ENABLED=true`、设置可达的 `IPE_OPS_ADVERTISE_IP` 并注册 `iot-protocol-expert-ops`，否则 Higress 的 MCP `tools/call` 会返回 503。Control 通过 Nacos 发现 Runtime，默认禁止直连回退（仅 `IPE_RUNTIME_DIRECT_FALLBACK_ENABLED=true` 时用于本地调试）；Control、Runtime、Agent 精确版本以及 MCP/Skill 组件均注册 Nacos，`ai_registry_projection` 只保存数据库投影。首次启动或重建后执行：

```powershell
.\scripts\seed-ai.ps1
```

脚本按 Runtime → Control 顺序启动，等待健康检查，并以 `IPE_AI_BOOTSTRAP_ENABLED=true` 触发 Control 的模型/MCP/Skill/Agent 引导；它不会启动 OPS。`start-local.ps1` 会启动 OPS、AI 等进程但不启动 Runtime；其 AI 进程记录在 `run/ai.pid`，seed 默认拒绝重复启动，使用 `.\scripts\seed-ai.ps1 -Restart` 可在校验 PID 与 jar 后停止并接管。清理 `ipe_ai` 在线 Agent 数据时执行 `.\scripts\reset-ai.ps1 -Confirm -StopServices`（或先自行停止两个进程后使用 `-Confirm`）；Flyway、权限、知识库、审计和旧版 Agent 历史会保留。

健康检查为 `GET http://127.0.0.1:19083/actuator/health` 和 `GET http://127.0.0.1:19084/actuator/health/readiness`；配置、发布、内部接口和故障排查见 [AI 助手与在线 Agent 平台](docs/ai-assistant.md)。

首次运行将在独立 `ipe_ops` schema 初始化演示数据；不会清理已有 `public` 数据。现有 `public` 数据不会自动迁移。订单回放仅包含新接入的采样，显示的接收时间与设备时间分别标识。

## 模拟器

登录运营平台后从「充电模拟器」进入。模拟器服务仍需启动，运营平台通过 `IPE_SIMULATOR_BASE_URL`（默认 `http://127.0.0.1:19081`）连接模拟器。

设备列表由运营平台提供，选择设备后使用其协议和协议设备编号。主界面支持插枪、启动、停止、实时上报和故障模拟；云快充故障值为 16 位故障位图，OCPP 为标准 `StatusNotification.errorCode`。并发模拟区域可添加其他设备，分别控制枪和上报数据；同一设备不能同时出现在主模拟器和并发列表。

模拟器使用独立 PostgreSQL schema `ipe_simulator`，不在 `ipe_ops` 或 `ipe_ai` 中建表。默认复用 `IPE_OPS_DB_*` 连接，可通过 `IPE_SIMULATOR_DB_URL/USER/PASSWORD` 单独指定。Flyway 使用专属 `db/simulator-migration` 目录与 `ipe_simulator.flyway_schema_history`。`device_state` 按设备 ID、协议及协议设备编号保存电表、费率、充电检查点、已完成会话与云快充待补传账单；`charging_record` 永久归档完整充电账单（电量、费用、起止时间、停止原因及尖峰平谷明细），页面仍只展示最近 100 条。状态和账单在同一事务中保存，重复保存不会新增重复账单；数据库短暂不可用时保留内存数据并在模拟周期重试。

首次恢复每台设备时，若数据库还没有对应快照，会只读导入 `data/simulator-state-<设备ID>.properties`；未选设备的配置模式使用 `data/simulator-state.properties`，可用 `IPE_SIMULATOR_LEGACY_STATE_FILE` 指定旧文件基准路径。旧文件保留备查，不再写入；已有数据库快照后不重复导入。重启时完成会话可恢复显示，未完成会话按最后检查点以断电原因结算。数据库集成测试需显式提供 `IPE_SIMULATOR_TEST_DB_URL/USER/PASSWORD`，仅在模拟器 schema 内创建与清理随机测试记录。

具体协议能力和已知限制见 `docs/ocpp16.md`、`docs/protocol-coverage.md` 和 `docs/protocol-decisions.md`。

## 开发规范

按阿里巴巴 Java 开发手册适配审查，范围、修复记录和运行方式见 [开发规范](docs/coding-guidelines.md)。提交前运行 `mvn -Pcoding-guidelines verify`（JDK 21、Python 3）。
