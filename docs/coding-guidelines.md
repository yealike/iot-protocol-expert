# 阿里巴巴开发规范适配与审查记录

审查日期：2026-11-16。范围：六个后端 Maven 模块的 Java、MyBatis XML、Flyway SQL，重点人工检查持久化、删除、并发、参数边界及异常响应。前端和线上数据库未在本次审查范围内。

依据：[阿里巴巴 P3C 官方仓库](https://github.com/alibaba/p3c)、[Java 开发手册在线版](https://alibaba.github.io/Alibaba-Java-Coding-Guidelines/)。项目使用 Java 21 / Spring Boot 4 / PostgreSQL，沿用既有 Spotless 格式化；MySQL 专属 DDL 不机械套用。检查脚本实现规范的明确子集，不是完整 P3C 扫描器，零告警不代表代码完全符合手册。

## 修复清单

| 优先级 | 问题与触发条件 | 修复 |
| --- | --- | --- |
| P1 | V5 已清理外键，V8/V9 又引入协议文档与 IAM 外键；模拟器 V1 也有外键 | 新增 OPS V10、模拟器 V2，按各自 schema 清理物理外键，保留历史迁移校验和；不使用 CASCADE |
| P1 | 未激活设备删除只删主表，可能留下配置孤儿或破坏历史记录的关联 | 事务内检查子设备、订单、账单、告警规则/记录、属性时序、事件、命令、报文和排障记录；有引用则拒绝，允许删除时显式清理标签、通道和最新属性 |
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

检查项：禁止 Executors 工厂隐式线程池、无 Locale 大小写转换、printStackTrace、SimpleDateFormat；Mapper 禁止 SELECT * 与未经审查的 ${...}；SQL 禁止新增外键和 ON DELETE 级联。已应用迁移通过统一换行后的文件内容 SHA-256 基线豁免，因此修改这些旧文件也会失败。

已审查的 SQL 标识符插值：DeviceInstance / Product / AlarmRecord 排序来自 PageSupport 白名单；TDengine 数据库名为固定常量，表名为订单号 SHA-256。不能直接传入请求参数。普通值继续使用 #{...}。

ScheduledThreadPoolExecutor 的延迟队列仍是 JDK 无界实现：Modbus 每个配置设备仅注册一个固定周期任务，任务数等于配置设备数；SSE 由信号量限制活动任务数并启用 removeOnCancelPolicy。这需要人工检查，静态脚本不能证明上限。

## 验证与边界

新增回归测试覆盖设备历史删除保护/清理顺序、分页溢出/排序白名单、无效费率拒绝、SSE 容量及重复清理、异常状态保留与内部信息隐藏。既有 Mapper 参数绑定和结果映射测试验证显式列调整。

数据库集成测试按项目既有环境变量条件执行；本次未向共享测试、UAT 或生产数据库执行迁移。新迁移需在部署环境由 Flyway 执行，现有数据不会因移除约束而删除。数据库迁移和真实网络负载行为仍需部署前在隔离环境验收。

本次最终验证：`mvn -B -Pcoding-guidelines spotless:apply verify` 成功；Surefire 共 117 项，107 项通过、10 项按环境条件跳过，0 失败/错误；规范检查 288 文件、0 告警；扫描器 6 个隔离正反例检查通过；`git diff --check` 通过。
