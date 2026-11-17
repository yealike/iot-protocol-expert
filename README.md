# iot-protocol-expert

充电桩协议调查与验证工作台。核心闭环是「调查任务 → 证据 → 假设 → 实验 → 结论」，同时提供设备接入、订单、模型网关、配额和 Agent 发布能力。使用 Java 21、Spring Boot、PostgreSQL 和独立 React 前端。

| 模块 | 职责 | 默认端口 |
| --- | --- | --- |
| `pep-control` | 身份权限、租户项目、设备与充电交易、调查任务、Agent 目录、知识、评测和审计的模块化单体 | HTTP 19082 |
| `pep-model-gateway` | 唯一模型出口：供应商密钥、逻辑模型、预占结算、首次使用窗口和用量审计 | HTTP 19085 |
| `pep-agent-runtime` | 执行已发布 Agent、受控工具、心跳、事件回写和检查点，不访问业务数据库 | HTTP 19084 |
| `pep-device-gateway` | 设备连接、协议编解码和上下行转发，不持久化业务数据 | HTTP 19080；云快充 TCP 8768；OCPP WS 8769 |
| `pep-simulator` | 可重复场景、虚拟时钟、设备状态机和故障注入 | HTTP 19081 |
| `pep-console` | 调查工作台、设备/订单页面和管理控制台 | 开发端口 5173 |
| `pep-device-codec` | 设备网关与模拟器共用的纯编解码库 | 无 |
| `pep-runtime-contract` / `pep-model-contract` | Runtime 与模型网关的稳定契约，不依赖服务实现 | 无 |

设备网关通过 `ProtocolServer` SPI 管理云快充 1.6 和 OCPP 1.6J；业务事实由 `pep-control` 保存。模型调用只允许经过 `pep-model-gateway`，Control 与 Runtime 都不持有上游供应商密钥。

数据库按所有权拆分为 `pep_platform`、`pep_domain`、`pep_agent`（Control）和 `pep_metering`（模型网关）。调查任务、证据、假设、实验和结论位于 `pep_agent`；配额账本以 PostgreSQL 为权威。Control 通过签名内部接口调度 Runtime；Nacos 为可选集成，不可用时回退静态地址，不阻塞启动。

## 本地运行

需要 JDK 21、Maven、PostgreSQL 和 TDengine 3.x。应用数据库账号需有创建 `pep_platform`、`pep_domain`、`pep_agent`、`pep_metering` 和 `ipe_simulator` schema 的权限。将 `.env.example` 复制为 `.env.local` 并填写本地连接、令牌和 JDK 路径；启动脚本会读取它，IDE 启动时需自行加载。订单时序使用 TDengine WebSocket 接口，应用账号需有创建数据库和表的权限。然后运行：

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\develop\apache-maven-3.8.8\bin\mvn.cmd' -B verify
.\scripts\start-local.ps1 -DeviceId 1
```

`start-local.ps1` 按 `control → model-gateway → agent-runtime → device-gateway → simulator` 顺序启动并等待健康检查；PID 与日志分别在 `run/` 和 `logs/`。前端在 `pep-console` 下执行 `npm ci`、`npm run dev`；生产构建使用 `npm run build`。停止全部服务使用 `.\scripts\stop-local.ps1`。

模型供应商只通过模型网关管理 API 登记，密钥加密后只写不读：

```powershell
$headers = @{ 'X-PEP-Service-Token' = $env:PEP_MODEL_GATEWAY_SERVICE_TOKEN }
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:19085/api/model-gateway/v1/admin/providers' `
  -Headers $headers -ContentType 'application/json' `
  -Body (@{ code='deepseek'; name='DeepSeek'; baseUrl='https://api.deepseek.com/v1'; apiKey=$env:IPE_MODEL_API_KEY } | ConvertTo-Json)
Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:19085/api/model-gateway/v1/admin/models' `
  -Headers $headers -ContentType 'application/json' `
  -Body (@{ key='deepseek-default'; displayName='DeepSeek'; providerCode='deepseek'; upstreamModel='deepseek-chat'; contextWindow=65536; maxOutputTokens=4096; countingMode='ESTIMATE' } | ConvertTo-Json)
```

浏览器端额度查询走 Control 的 `/api/control/v1/me/quota` 与 `/me/usage`，不直接接触网关令牌。首次使用协议问答前，以管理员身份调用 `POST /api/ai/v1/knowledge/index` 建立索引。

### 在线 Agent 与 Runtime

需要 Nacos/Higress 时在 `.env.local` 显式设置 `IPE_AI_REGISTRY_ENABLED=true`、`IPE_AI_MCP_REGISTRY_ENABLED=true`，并保证 Control 与 Runtime 使用同一个 `IPE_INTERNAL_SERVICE_KEY`，Control 与 Gateway 使用同一个 `PEP_MODEL_GATEWAY_SERVICE_TOKEN`；供应商密钥主密钥只配置在 Gateway。OPS/Control 注册名默认为 `pep-control`，Runtime 为 `pep-agent-runtime`。Control 通过 Nacos 发现 Runtime，默认禁止直连回退（仅 `IPE_RUNTIME_DIRECT_FALLBACK_ENABLED=true` 时用于本地调试）；`ai_registry_projection` 只保存数据库投影。首次启动或重建后执行：

```powershell
.\scripts\seed-ai.ps1
```

脚本确保 Gateway/Runtime/Control 已启动，并以 `IPE_AI_BOOTSTRAP_ENABLED=true` 触发一次 Control 的模型/MCP/Skill/Agent 引导。清理 `pep_agent` 在线 Agent 数据时执行 `.\scripts\reset-ai.ps1 -Confirm -StopServices`（或先自行停止服务后使用 `-Confirm`）；调查任务、Flyway、权限、知识库和审计会保留。

健康检查：Control `GET http://127.0.0.1:19082/actuator/health`，Gateway `19085`，Runtime `GET http://127.0.0.1:19084/actuator/health/readiness`。配置、发布、内部接口和故障排查见 [AI 助手与在线 Agent 平台](docs/ai-assistant.md) 与 [重构实施记录](docs/refactor-implementation.md)。

首次运行由 Flyway 创建 `pep_platform`、`pep_domain`、`pep_agent` 和 `pep_metering` 并写入演示租户、项目、角色与设备数据；不会清理已有 `public` 数据。旧库表仅用于一次性迁移来源，运行期不再双写。订单回放仅包含新接入的采样，显示的接收时间与设备时间分别标识。

## 模拟器

登录运营平台后从「充电模拟器」进入。模拟器服务仍需启动，运营平台通过 `IPE_SIMULATOR_BASE_URL`（默认 `http://127.0.0.1:19081`）连接模拟器。

设备列表由运营平台提供，选择设备后使用其协议和协议设备编号。主界面支持插枪、启动、停止、实时上报和故障模拟；云快充故障值为 16 位故障位图，OCPP 为标准 `StatusNotification.errorCode`。并发模拟区域可添加其他设备，分别控制枪和上报数据；同一设备不能同时出现在主模拟器和并发列表。

模拟器使用独立 PostgreSQL schema `ipe_simulator`，不在 Control 的业务 schema 中建表。默认复用 `PEP_CONTROL_DB_*`（兼容 `IPE_OPS_DB_*`）连接，可通过 `IPE_SIMULATOR_DB_URL/USER/PASSWORD` 单独指定。Flyway 使用专属 `db/simulator-migration` 目录与 `ipe_simulator.flyway_schema_history`。`device_state` 按设备 ID、协议及协议设备编号保存电表、费率、充电检查点、已完成会话与云快充待补传账单；`charging_record` 永久归档完整充电账单（电量、费用、起止时间、停止原因及尖峰平谷明细），页面仍只展示最近 100 条。状态和账单在同一事务中保存，重复保存不会新增重复账单；数据库短暂不可用时保留内存数据并在模拟周期重试。

首次恢复每台设备时，若数据库还没有对应快照，会只读导入 `data/simulator-state-<设备ID>.properties`；未选设备的配置模式使用 `data/simulator-state.properties`，可用 `IPE_SIMULATOR_LEGACY_STATE_FILE` 指定旧文件基准路径。旧文件保留备查，不再写入；已有数据库快照后不重复导入。重启时完成会话可恢复显示，未完成会话按最后检查点以断电原因结算。数据库集成测试需显式提供 `IPE_SIMULATOR_TEST_DB_URL/USER/PASSWORD`，仅在模拟器 schema 内创建与清理随机测试记录。

具体协议能力和已知限制见 `docs/ocpp16.md`、`docs/protocol-coverage.md` 和 `docs/protocol-decisions.md`。

## 开发规范

按阿里巴巴 Java 开发手册适配审查，范围、修复记录和运行方式见 [开发规范](docs/coding-guidelines.md)。提交前运行 `mvn -Pcoding-guidelines verify`（JDK 21、Python 3）。
