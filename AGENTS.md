# 项目开发约定

- 以阿里巴巴《Java 开发手册》为审查依据，项目适配与检查范围见 `docs/coding-guidelines.md`。
- Java 21、PostgreSQL；不能机械套用 MySQL 专属字段类型与 DDL。
- 禁止新增物理外键及数据库级联；关联校验、删除保护与附属数据处理在应用服务事务内显式完成。已发布 Flyway 迁移不可修改，用新迁移修正。
- MyBatis 显式列出查询列；动态排序须使用 PageSupport 白名单；动态数据库/表名须由固定常量或受控算法生成。
- 明确线程池容量、线程名称、拒绝策略和关闭方式。周期任务说明任务数量上限，取消后移除任务；拒绝提交时恢复计数和任务状态。
- 协议标识、文件类型、枚举等与用户语言无关的大小写转换必须指定 Locale.ROOT。
- 服务端异常记录堆栈，对外使用稳定的错误信息，保留业务 HTTP 状态码。金额使用 BigDecimal，入口验证数值范围、精度及空值。
- 验证：`python scripts/verify-coding-guidelines.py` 和 `mvn -Pcoding-guidelines verify`（JDK 21、Python 3）。
