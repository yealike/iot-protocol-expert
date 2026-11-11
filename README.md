# iot-protocol-expert

云快充 V1.6 协议平台与充电桩仿真实验室。Java 21 / Spring Boot 4.0.9 / Netty / H2，前端为静态 HTML、JavaScript 和 Three.js。

## 启动

已接入 OpenTelemetry：Micrometer Tracing / Metrics、JDBC Observation、异步上下文与结构化日志通过 OTLP 发送到 OpenTelemetry Collector，再由 Grafana + Tempo / Loki / Prometheus 展示。默认开启导出，`.env-local` 中的 `IPE_OTLP_ENDPOINT` 指定 Collector 地址。[本地调试说明](docs/observability.md)。

```powershell
# 将 JAVA_HOME 指向本机 JDK 21，或设置 IPE_JAVA_HOME
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
mvn -B package
.\scripts\start-local.ps1
```

- 平台控制台：<http://localhost:19080>，TCP：8768。
- 模拟器：<http://localhost:19081>，默认双枪；默认未插枪。
- 停止：`.\scripts\stop-local.ps1`。
- 可观测性：Grafana <http://100.101.91.38:3000>；链路 Tempo、日志 Loki、指标 Prometheus。
- 指标：两端 `/actuator/metrics` 与 `/actuator/prometheus`；JSON 日志位于 `logs/`。
- 启动脚本支持 JAVA_HOME / IPE_JAVA_HOME，也可使用 -JavaHome 指定 JDK 21；支持项目路径中包含空格。

## 充电实验室

1. 等待「平台已登录」，确认桩已接收平台费率。
2. 选择枪号，设置电池容量、SOC、VIN、物理卡号和可选停止条件。
3. 插枪，选择 APP 远程启动、刷卡启动或 VIN 启动。
4. 经握手、参数配置进入恒流充电；80% SOC 后逐渐降流进入恒压阶段。
5. 使用速度选择器或「+1分钟」「+30分钟」推进；实际 TCP 心跳和上报间隔仍使用真实时间。
6. 本地/APP 停止、满电、限电量/金额/时间、余额不足或故障均生成账单，等待平台 `0x40` 确认。

3D 场景使用本地化的 Car Concept 曲面车身（CC BY 4.0，保留署名并移除原贴图、标识、内饰和发动机）与独立生成的充电桩、底盘电池包。插拔枪使用约3.6秒缓动；充电展示“电网交流输入→桩内AC/DC转换→线缆直流输出→底盘电池储能”。默认充电时车身半透明，40组电芯按实际SOC点亮；支持自动、整车、电池透视三种视图。Three.js和模型随JAR打包，运行时无需CDN。无法使用WebGL时仍保留充电控制。详见 [3D视觉升级](docs/3d-visual-upgrade.md)。

## 分时计费

- 在模拟器的「费率模型」编辑八个电费/服务费单价、计损比例、48 个半小时时段。
- 「保存到平台并下发」先保存平台模型，再走 `0x58` 下发及 `0x57` 应答。
- 每笔订单保存费率快照；充电中收到的新模型用于下一笔订单。
- 跨半小时、跨午夜逐段积分，以 BigDecimal 保留计算精度，输出电量/金额四位小数、单价五位小数。
- 尖峰平谷分别累计电量、计损电量、电费和服务费；账单四段金额之和等于总金额。
- 点击「跨时段演示」设为 09:59:50，启动后推进时间，即可看到默认平价到峰价的变化。

## 完整协议与调试

51 种帧都有编解码和结构化查看入口，包括所有 BMS 字段、状态位、并充、离线卡、地锁和维护命令。详见 [协议覆盖](docs/protocol-coverage.md)。

- `GET /api/protocol/catalog`：平台协议目录、BMS 字段、倍率/偏移和状态位定义。
- `POST /api/protocol/decode`：平台完整帧 HEX 校验、解密与解析；也支持 `{frameType,bodyHex}`。
- 平台帧流水和模拟器报文详情可展开全部字段。
- 并充：先插好全部枪，选择「双枪刷卡并充」或「APP并充」；每枪独立流水号、实时数据和账单。
- 停止原因：模拟器支持附录 `0x40~0x90` 的全部原因标签与按指定原因结束订单。

## 离线与恢复

- 在线同步白名单，断开 TCP 后可用对应物理卡离线启动；本地使用已同步费率计费。
- 断网中的已有充电持续推进，结束账单写入本地待传队列；重新登录后补传。
- 收到成功 `0x40` 才删除待传账单；失败应答保留；三次重试用尽仍保留，重新登录后再重试。
- 每秒保存活动订单检查点；进程意外退出后，下一次启动按最后保存的电表值生成 `0x83` 断电账单。
- 历史账单最多保留100笔。白名单、费率、电表、工作参数和待传队列写入 `./data/simulator-state.properties`。

## 配置

平台配置位于 `iot-protocol-expert-protocol/src/main/resources/application.yml`，模拟器位于 `iot-protocol-expert-simulator/src/main/resources/application.yml`。可通过 Spring Boot 命令行参数覆盖：

| 模拟器参数 `ipe.simulator.*` | 默认值 | 作用 |
|---|---|---|
| platform-host / platform-port | 127.0.0.1 / 8768 | TCP 平台地址 |
| platform-http-port | 19080 | 同源控制台桥接的平台 HTTP 端口 |
| pile-code / gun-count | 32010600000001 / 2 | 14位桩号与枪数 |
| rated-voltage / rated-current | 750 / 100 | 额定电压V、电流A |
| encryption-enabled | false | 启用 3DES；平台按登录会话协商 |
| heartbeat-seconds | 10 | 心跳，连续三周期未获回复则重连 |
| idle-realtime-seconds / charging-realtime-seconds | 300 / 15 | 实时帧上报周期 |
| state-file | ./data/simulator-state.properties | 每个模拟实例需使用不同文件 |
| transaction-retry-seconds / transaction-max-retries | 30 / 3 | 待传账单重试 |

平台 H2 文件为 `./data/protocol.mv.db`。模拟器「配置参数」查看实际运行参数、功率限制、白名单与升级状态；`0x52` 真正影响充电功率。规范没有参数查询帧，配置查看使用模拟器本地 REST。

## 模拟器新增 API

| 路径（POST，另行注明除外） | 请求体 / 含义 |
|---|---|
| GET `/api/state`、`/api/config`、`/api/logs` | 状态、配置、报文及结构化字段 |
| `/api/clock` | `{time?,speed?,advanceSeconds?}`，仅空闲允许直接修改时间，倍速0~3600 |
| `/api/guns/{gun}/vehicle` | `{capacityKwh,soc,vin,physicalCard}` |
| `/api/guns/{gun}/limits` | `{energy,amount,minutes}`，0表示不限制 |
| `/api/guns/{gun}/{action}` | action为plug、unplug、start、vin-start、offline-start、stop、report |
| `/api/guns/{gun}/fault?enable={boolean}` | 急停故障与恢复 |
| `/api/guns/{gun}/terminate?reason=114` | 按附录原因终止并生成账单，参数为十进制 |
| `/api/parallel-start` | 全部枪主动刷卡并充 |
| GET `/api/stop-reasons` | 全部停止原因字典 |
| `/api/upgrade-scenario` | `{result:0~3}` 固件更新模拟结果 |
| `/api/platform/...` | 桥接固定配置的协议平台，用于控制台，非云快充协议扩展 |

原有平台设备、订单、交易、费率 REST 保留；新增 `POST /api/devices/{pileCode}/parallel-start`。

## 验证与边界

```powershell
mvn -B test
# 使用已经启动的隔离测试实例，脚本会执行充电、卡管理和参数修改：
python scripts/smoke-test.py --base-url http://127.0.0.1:29081
```

36项自动化测试通过；另完成明文/3DES真实TCP联调和桌面/移动页面浏览器检查。详见 [验收记录](docs/verification.md)。

本工程实现协议和仿真闭环，平台鉴权仍为参考演示逻辑，不接真实资金账户。BMS 为规范字段仿真，不连接真实 CAN 总线；物理电池模型为简化恒流/恒压模型。FTP 固件更新只模拟元数据校验、空闲执行与结果，不下载或执行固件。文档矛盾及取舍见 [协议决策](docs/protocol-decisions.md)。

3D 技术选择、模型替换方法和依赖许可见 [3D落地方案](docs/3d-options.md)。

## 仓库与本地配置

- Java 包名前缀与 Maven groupId：`top.yealike.ipe`。
- 两端默认使用公开的3DES演示测试向量，且默认不启用加密。真实设备使用私有密钥时，通过 `IPE_CRYPTO_KEY_HEX` 为平台和模拟器同时设置相同的16/24字节十六进制值。
- 可参考 `.env.example` 配置环境变量。Spring Boot 不会自动加载 `.env`，请在 shell 或 IDE 中导入。
- `.idea`、运行数据、日志、临时目录、构建包、密钥文件、本地配置和生成的验收记录均不提交。源码、可复用脚本、文档预览图及必要的3D资源保留。
- 可选浏览器验证脚本使用标准 `playwright` 包，需要先安装该依赖和 Chromium；不依赖特定用户的工具缓存路径。
- Three.js 的 MIT 许可及车辆模型的 CC BY 4.0 许可、署名和修改说明随资源保留；详见 [3D落地方案](docs/3d-options.md)。
