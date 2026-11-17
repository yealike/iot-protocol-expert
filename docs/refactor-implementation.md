# 协议专家重构实施记录

实施日期：2026-11-17。对应设计目录 `重构/README.md` 与 `01`~`06` 篇。

## 1. 已落地范围

| 设计项 | 实施结果 |
| --- | --- |
| `pep` 模块与包统一 | Control 合并原 OPS 与 AI Control；Java 包统一到 `top.yealike.pep` |
| 进程边界 | `pep-control` 19082、`pep-model-gateway` 19085、`pep-agent-runtime` 19084、`pep-device-gateway` 19080、`pep-simulator` 19081 |
| 数据库所有权 | `pep_platform`（身份/租户/项目）、`pep_domain`（设备/订单/审计）、`pep_agent`（Agent 与调查）、`pep_metering`（模型计量） |
| 身份与权限 | 8 个内置角色、作用域角色绑定、项目成员、服务账号、访问密钥、资源共享、统一 `AccessService` |
| 调查任务 | 任务/事件/证据/假设/实验/结论/审批，任务行锁串行分配事件序号，证据 SHA-256 去重，结论版本追加 |
| 模型网关 | 逻辑模型目录、AES-256-GCM 供应商密钥、短期 Run 凭据、预占/结算/释放/调整、唯一模型出口 |
| 配额 | TOTAL 与 `ON_FIRST_USE_WINDOW`；首次调用懒启动固定窗口；冻结与结算原子化；幂等授予与重放 |
| Runtime | 不访问数据库，通过 HMAC 签名的 `/internal/ai/v1/**` 拉取发布、Run 上下文并回写事件 |
| 本地运行 | `start-local.ps1` 五进程编排、`stop-local.ps1`、`seed-ai.ps1`、`reset-ai.ps1` |
| 前端 | 新增「调查任务」「模型额度」页面；AI 与 OPS 统一由 19082 提供，去掉 19083 代理 |

## 2. 关键接口

### 调查任务

```text
POST   /api/control/v1/investigations
GET    /api/control/v1/investigations?projectId=...
GET    /api/control/v1/investigations/{id}
POST   /api/control/v1/investigations/{id}/transitions
GET    /api/control/v1/investigations/{id}/events?afterSeq=0
POST   /api/control/v1/investigations/{id}/evidence
GET    /api/control/v1/investigations/{id}/evidence
POST   /api/control/v1/investigations/{id}/hypotheses
PATCH  /api/control/v1/investigations/{id}/hypotheses/{hypothesisId}
POST   /api/control/v1/investigations/{id}/experiments
PATCH  /api/control/v1/investigations/{id}/experiments/{experimentId}
POST   /api/control/v1/investigations/{id}/conclusions
GET    /api/control/v1/investigations/{id}/conclusions
POST   /api/control/v1/investigations/{id}/approvals
POST   /api/control/v1/investigations/{id}/approvals/{approvalId}/use
```

状态机：`OPEN → INVESTIGATING → WAITING_USER / WAITING_APPROVAL / WAITING_QUOTA → VALIDATING → RESOLVED / INCONCLUSIVE / CANCELLED`。每次跃迁要求 `expectedVersion`，防止并发覆盖。

### 身份与资源

```text
GET  /api/control/v1/tenants
GET  /api/control/v1/tenants/{tenantId}/projects
GET  /api/control/v1/projects/{projectId}/members
PUT  /api/control/v1/projects/{projectId}/members/{userId}
GET  /api/control/v1/roles
GET  /api/control/v1/service-accounts
POST /api/control/v1/projects/{projectId}/access-keys
POST /api/control/v1/projects/{projectId}/resource-grants
```

访问密钥明文只在创建响应出现一次，数据库保存摘要、前缀和状态。

### 模型网关与配额

```text
POST /api/model-gateway/v1/admin/providers
POST /api/model-gateway/v1/admin/models
POST /api/model-gateway/v1/admin/quota-grants
GET  /api/model-gateway/v1/me/quota?scopeKind=USER&subjectId=...
GET  /api/model-gateway/v1/me/usage?scopeKind=USER&subjectId=...
POST /v1/chat/completions
```

网关只接受两类身份：`Authorization: Bearer <RunToken>`（Runtime 调用）或 `X-PEP-Service-Token`（Control 管理/查询）。浏览器通过 Control 的 `/api/control/v1/me/quota` 与 `/me/usage` 访问，扣费主体由服务端会话确定。

## 3. 数据库与迁移

- `V1__pep_control_baseline.sql`：把旧 OPS 业务表收敛到 `pep_domain`，AI 表收敛到 `pep_agent`。
- `V2__platform_access.sql`：`pep_platform.app_user`、租户、项目、成员、角色、角色绑定、服务账号、访问密钥、资源共享、审计事件；旧角色一次性映射到新角色。
- `V3__investigations.sql`：调查任务、事件、证据、假设、实验、结论和审批。
- `V1__metering_baseline.sql`：供应商、逻辑模型、策略版本、额度账户、窗口桶、调用、预占、结算、调整和幂等变更请求。

迁移测试会断言三件事：种子租户/项目/角色存在、默认绑定完整、上述业务 schema 中没有物理外键。应用启动不得删除旧库；只有显式设置 `PEP_TEST_RESET_SCHEMAS=true` 的测试会清理白名单 schema。

## 4. 验收结果

2026-11-17 本机实测：

- `mvn -o test`：10 个模块全部通过；Control 74 个测试、Runtime 46 个、Simulator 27 个、Device Gateway 7 个。
- 真实 PostgreSQL：`ControlMigrationPostgresTest`、`InvestigationPostgresTest`、`MeteringMigrationPostgresTest` 通过。
- 配额并发：`QuotaServicePostgresTest` 8/8 通过，覆盖懒启动、70/40 并发、总量+窗口原子回滚、重复结算、释放、跨窗迟到结算、100 并发建窗和授予幂等。
- Control 启动：Flyway 识别 4 个迁移，健康检查 UP；`admin` 登录后可读租户、项目、成员和调查任务。
- Runtime 启动：readiness UP，`desired-releases` 对账成功。
- Gateway 联调：登记本地 mock provider 与逻辑模型，签发 Run 凭据调用 `/v1/chat/completions`，上游 usage 12+5 归一化为 17 并结算；Control 代理读取结果一致。
- 前端：`npm run typecheck` 通过；Vite 代理下 `/login`、`/api/control/v1/tenants`、`/me/quota` 正常。

## 5. 尚未落地

以下设计项保留为后续阶段，当前代码不宣称已完成：

- Keycloak/OIDC 真实接入与令牌撤销；当前为 Control 本地会话与 `auth_version` 在线校验。
- TDengine 复合主键、投影与 KEEP 策略的完整重建；当前遥测链路沿用既有实现。
- PostgreSQL RLS 纵深防御；当前授权在应用层完成。
- 前端证据上传、假设/实验/结论编辑、SSE 断线续传的完整交互；当前提供列表、创建、状态推进、证据与事件查看。
- 配额 TPM/并发治理、模型 fallback 与 Provider 字段级契约矩阵。
- Testcontainers CI 与 ArchUnit 模块边界测试；当前使用真实 PG 环境变量门控测试。

## 6. 本地验证命令

```powershell
$env:JAVA_HOME='D:\develop\jdk\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;D:\develop\apache-maven-3.8.8\bin;$env:PATH"

mvn -o clean package -DskipTests
.\scripts\start-local.ps1

# 真实库迁移与调查闭环（读取 .env.local）
$env:PEP_TEST_DB_URL=$env:PEP_CONTROL_DB_URL
$env:PEP_TEST_DB_USER=$env:PEP_CONTROL_DB_USER
$env:PEP_TEST_DB_PASSWORD=$env:PEP_CONTROL_DB_PASSWORD
$env:PEP_TEST_RESET_SCHEMAS='true'
$env:PEP_INVESTIGATION_TEST='true'
mvn -o -pl pep-control -am test -Dtest=ControlMigrationPostgresTest,InvestigationPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```
