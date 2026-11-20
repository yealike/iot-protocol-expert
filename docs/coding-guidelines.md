# 阿里巴巴开发规范适配与审查记录

审查日期：2026-11-18。范围：11 个 Maven reactor 模块中的 Java、MyBatis XML、Flyway SQL，重点人工检查持久化、删除、并发、参数边界及异常响应。前端和线上数据库未在本次审查范围内。

依据：[阿里巴巴 P3C 官方仓库](https://github.com/alibaba/p3c)、[Java 开发手册在线版](https://alibaba.github.io/Alibaba-Java-Coding-Guidelines/)。项目使用 Java 21 / Spring Boot 4 / PostgreSQL，沿用既有 Spotless 格式化；MySQL 专属 DDL 不机械套用。检查脚本实现规范的明确子集，不是完整 P3C 扫描器，零告警不代表代码完全符合手册。

## 修复清单

| 优先级 | 问题与触发条件 | 修复 |
| --- | --- | --- |
| P1 | AI 平台 Store 仍用 `JdbcTemplate` 手写 SQL，ORM 未统一为 MyBatis，存在列名漂移和 SQL 分散风险 | 8 个 Store、内部签名过滤器与内部控制器全部迁移到 MyBatis Mapper/XML；显式列名、参数化、类型处理器；删除 `JdbcRows`；新增 Mapper 绑定测试 |
| P1 | MCP 与模型入口只做字符串黑名单，`2852039166`、IPv4-mapped IPv6、DNS 别名、云元数据地址可绕过，可能把凭据发往本机/链路本地目标 | 新增共享 `EndpointPolicy`：仅 HTTP(S)、拒绝 userInfo/fragment/loopback/link-local/组播/未指定/云元数据，连接前解析全部 A/AAAA；MCP Probe、Runtime MCP 连接、模型请求统一接入 |
| P1 | 凭据停用后仍可按 UUID 解密使用，MCP/模型仍可绑定停用凭据 | `resolve(UUID)` 与模型/MCP 绑定校验 `enabled`；停用凭据引用计数纳入 `ai_mcp_component.draft_json` |
| P1 | Outbox `complete/retry` 仅按 id 更新，旧 worker 租约过期后可覆盖新 worker；批量 30 秒租约覆盖串行处理，后续记录可能在执行前过期 | `complete/retry` 增加 `status='PENDING'`、`lease_owner`、`lease_until > now()` CAS；租约提升到 120 秒，逐条续租；认领回读过滤过期租约 |
| P2 | 内部接口在 HMAC 校验前无界 `readAllBytes()`，未认证请求可耗尽堆内存 | 新增 `InternalRequestBodies`，4 MiB 上限；Control/Runtime 先校验时间戳与头长度，再有界读取请求体 |
| P2 | Agent 单次 update 在替换绑定后再次 `touchRevision`，一次请求递增两次 revision | 绑定替换不再额外递增；创建路径保持 revision 0，后续 update/publish 各递增一次 |
| P2 | AgentScope OTel 配置声明 `content-enabled` 但运行时未使用，默认仍导出 prompt/response 正文 | `AgentRuntimeTelemetry` 接入共享 `ContentFilteringTracer`；Control/Runtime 默认 `false`，开启后仍截断与脱敏 |
| P2 | Registry projection upsert 先查后写，存在并发插入竞态 | 改为 PostgreSQL `ON CONFLICT ... DO UPDATE` 原子 upsert |
| P2 | 凭据轮换并发读取同一 revision，可能生成重复 revision | 轮换前 `for update` 锁定凭据行 |
| P2 | MCP 删除只清工具策略，遗留测试报告形成孤儿记录 | 删除组件前显式删除 `ai_mcp_test_report` |
| P2 | 内部签名时间戳用 `Math.abs` 比较，极值可溢出；服务身份头无长度上限 | 改为上下界比较并限制 service/nonce/signature 长度 |
| P1 | V5 已清理外键，V8/V9 又引入协议文档与 IAM 外键；模拟器 V1 也有外键 | 新增 OPS V10、模拟器 V2，按各自 schema 清理物理外键，保留历史迁移校验和；不使用 CASCADE |
| P1 | 未激活设备删除只删主表，可能留下配置孤儿或破坏历史记录的关联 | 事务内检查子设备、订单、账单、属性时序、事件、命令、报文和排障记录；有引用则拒绝，允许删除时显式清理标签、通道和最新属性 |
| P1 | 固定线程池默认无界队列，过载可能积压内存；拒绝任务后 inflight / pending 无法释放 | AI 和模拟器改显式 ThreadPoolExecutor、有界队列、命名线程与拒绝策略；拒绝时释放计数/标记，AI 恢复队列状态；AI 派发加同步保护 |
| P1 | SSE 连接数量和生命周期无限制，取消任务可能留在延迟队列，回调与 future 赋值有竞争 | 每实例最多 256 路，超额返回 429；30 分钟超时；取消立即移除；原子释放配额并处理注册竞争。客户端需按游标重连 |
| P1 | 统一异常处理覆盖 ResponseStatusException / AccessDeniedException，客户端错误变 500；底层异常可能返回 SQL/基础设施信息 | OPS/AI 保留状态异常和 403；协议服务保留状态异常；OPS 约束冲突返回 409；服务端异常只在日志保留详情 |
| P2 | AI Mapper 共 18 处 SELECT *，增加无用列传输并依赖表结构 | 使用 resultMap 对应的显式列，保留原查询条件、排序及分页 |
| P2 | pageIndex * pageSize 使用 int，极大页码产生负 offset | long 计算并校验上限，非法页码返回参数错误 |
| P2 | 费率保存只校验编号与时段数量，允许 null、负价、超精度、超协议值、非法时段/计损比例 | 入库前按云快充编码约束校验八种价格、48 个时段及 0–100 计损比例 |
| P2 | 默认费率通过同类自调用受保护方法，@Transactional 不经过 Spring 代理 | 把事务边界放到 public active()，初始化方法改为 private |
| P2 | 多处标识及文件类型转换依赖系统 Locale，土耳其语环境下可能转换错误 | 统一显式 Locale.ROOT |

设备删除保护不等同于数据库外键的并发强约束。未来新增关联写入、在线设备删除或后台清理任务时，必须共同设计锁定/软删除策略，不能绕过服务层直接删除。当前政策保留业务历史，存在历史记录的设备只能停用/注销。

## 持续检查

```powershell
python scripts/verify-coding-guidelines.py
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
& 'D:\develop\apache-maven-3.8.8\bin\mvn.cmd' -B -Pcoding-guidelines verify
```

Maven profile 在根项目 verify 阶段运行扫描，继承关系不会导致在各模块重复执行。需 Python 3；普通 verify 仍运行既有单测与 Spotless，规范门禁需显式启用该 profile。

检查项：禁止 Executors 工厂隐式线程池、无 Locale 大小写转换、printStackTrace、SimpleDateFormat；生产代码禁止 `JdbcTemplate` / `NamedParameterJdbcTemplate` / `JdbcClient`、JPA/Hibernate 与 `DriverManager#getConnection`；Mapper 禁止 SELECT * 与未经审查的 ${...}；SQL 禁止新增外键和 ON DELETE 级联。已应用迁移通过统一换行后的文件内容 SHA-256 基线豁免，因此修改这些旧文件也会失败。

已审查的 SQL 标识符插值：DeviceInstance / Product 排序来自 PageSupport 白名单；TDengine 数据库名为固定常量，表名为订单号 SHA-256。不能直接传入请求参数。普通值继续使用 #{...}。

ScheduledThreadPoolExecutor 的延迟队列仍是 JDK 无界实现：Modbus 每个配置设备仅注册一个固定周期任务，任务数等于配置设备数；SSE 由信号量限制活动任务数并启用 removeOnCancelPolicy。这需要人工检查，静态脚本不能证明上限。

## 验证与边界

新增回归测试覆盖设备历史删除保护/清理顺序、分页溢出/排序白名单、无效费率拒绝、SSE 容量及重复清理、异常状态保留与内部信息隐藏。既有 Mapper 参数绑定和结果映射测试验证显式列调整。

数据库集成测试按项目既有环境变量条件执行；本次未向共享测试、UAT 或生产数据库执行迁移。新迁移需在部署环境由 Flyway 执行，现有数据不会因移除约束而删除。数据库迁移和真实网络负载行为仍需部署前在隔离环境验收。

本次最终验证（2026-11-18）：`mvn -B -Pcoding-guidelines clean verify` 成功；11 个 reactor 模块全部 `SUCCESS`；Surefire 共 279 项，249 项通过、30 项按环境条件跳过，0 失败/错误；规范检查 462 个文件、0 项违规。

残余风险：Runtime 不引入数据库，内部 HMAC nonce 仍只在本进程内去重；多副本部署时同一签名请求在 30 秒时间窗内仍可能被重放到另一副本，必须固定单副本路由或后续引入共享 nonce 存储。`EndpointPolicy` 在连接前解析 DNS，但未把解析结果固定到实际连接，DNS rebinding 的最终阻断仍需网络层出口策略配合。
