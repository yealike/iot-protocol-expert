# iot-protocol-expert

充电桩协议平台，由运营平台、协议通道和模拟器组成。使用 Java 21、Spring Boot、PostgreSQL 和独立 React 前端。

| 模块 | 职责 | 默认端口 |
| --- | --- | --- |
| `iot-protocol-expert-ops` | 充电桩、订单、账单、报文、设备命令及运营管理 | HTTP 19082 |
| `iot-protocol-expert-protocol` | 连接、编解码、协议适配和上下行转发，不持久化业务数据 | HTTP 19080；云快充 TCP 8768；OCPP WS 8769 |
| `iot-protocol-expert-simulator` | 从运营平台选择设备，按绑定协议模拟上报，支持多桩并发 | HTTP 19081 |
| `iot-protocol-expert-codec` | 协议通道与模拟器共用的纯编解码库 | 无 |
| `iot-protocol-expert-web` | 运营平台独立前端 | 开发端口 5173 |

协议通道通过 `ProtocolServer` SPI 管理云快充 1.6 和 OCPP 1.6J。设备数据与账单通过内部接口提交运营平台；业务数据由运营平台保存。

## 本地运行

需要 JDK 21、Maven 及 PostgreSQL。设置数据库密码等环境变量后运行：

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\develop\apache-maven-3.8.8\bin\mvn.cmd' -B verify
.\scripts\start-local.ps1 -DeviceId 1
```

前端在 `iot-protocol-expert-web` 下执行 `npm ci`、`npm run dev`；生产构建使用 `npm run build`。设置 `IPE_OPS_DB_URL`、`IPE_OPS_DB_USER`、`IPE_OPS_DB_PASSWORD` 指定数据库，服务间地址与令牌见各模块的 `application.yml`。

Flyway 会在已有迁移基础上执行 `V4__chargers_only.sql`：删除非充电桩设备、对应产品和关联遥测/告警；关联订单或账单的记录会受外键保护并使迁移失败。执行前应备份数据库并确认数据影响。空库会先运行历史迁移，再由 V4 清理非充电桩数据。

## 模拟器

设备列表由运营平台提供，选择设备后使用其协议和协议设备编号。主界面支持插枪、启动、停止、实时上报和故障模拟；云快充故障值为 16 位故障位图，OCPP 为标准 `StatusNotification.errorCode`。并发模拟区域可添加其他设备，分别控制枪和上报数据；同一设备不能同时出现在主模拟器和并发列表。状态与待补传账单按设备 ID 分文件保存。

具体协议能力和已知限制见 `docs/ocpp16.md`、`docs/protocol-coverage.md` 和 `docs/protocol-decisions.md`。
