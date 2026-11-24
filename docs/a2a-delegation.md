# A2A 同 Runtime 委派

协议专家通过 Control 创建同步 Task、独立子会话和子 Run。同一父 Run 串行委派，目标固定为发布清单中的 Release ID 和摘要。子任务使用目标 Agent 的 Prompt、Skill、授权工具和独立的模型 Token，子会话只接收本次任务说明。

## 配置与发布

1. 在 Agent 管理页发布目标 Agent，确认发布准备完成。
2. 编辑父 Agent，填写基础说明。在 **Team 委派** 中选择目标 Agent 和精确 Release，配置别名、使用场景和调用限额。留空 MCP 工具范围会开放目标已审核的全部只读 MCP 工具。
3. 保存委派配置，再使用原有编译和发布流程。Runtime 会确认绑定版本已准备。**必需依赖**表示父 Run 启动前也检查目标可用性。
4. 在助手中选择已发布的父 Agent。父 Agent 调用委派后，对话展示子任务卡片。具有目标 Agent 权限的会话所有者可以打开只读轨迹。

草稿通过 `GET /api/ai/v1/agents/{id}/team` 和 `PUT /api/ai/v1/agents/{id}/delegates` 读写。保存要求当前 `expectedRevision`。能力卡片 `GET /api/ai/v1/agents/{id}/card` 受使用权限约束，ETag 来自当前发布摘要。会话详情返回当前用户可见的 `delegations`，父会话 SSE 用任务 ID 和状态触发刷新。

## 部署边界

- 当前部署按单租户、单项目运行。最终用户仍是配额主体，租户和项目使用既有部署级配置，历史配额账户无需迁移。
- 依次升级 Runtime、模型网关和 Control。OPS 需先应用 `V12__a2a_team.sql`，网关升级时应用 `V3__a2a_usage_attribution.sql`。旧 Manifest 的摘要算法和旧 Token 编码继续保留。
- 子 Run 发起模型调用前，网关以根 Run 为单位检查已结算用量和预占估算。计费以模型网关的 `model_call` 和 `model_usage` 为准。父 Run 的展示用量包含它结束时已经落库的子 Run 用量。
- `ipe.ai.delegation.max-active-roots` 默认允许两条委派链并行。每条链的深度、次数及调用时限取发布策略和平台限额。父 Run 停止会向下传播，未确认终态的任务由现有 Run 回收和 Task 状态同步处理。
- 工具参数 `deadline_seconds` 只控制父 Agent 的等待时间。等待超时后，子 Run 可以在自身截止时间前继续执行，状态仍可通过会话详情查看。
- 已有 Runtime–Control 客户端继续使用其内部 HMAC 签名、连接与读取超时和业务异常转换。同步子 Run 复用现有执行生命周期，在父 Runtime 内由有界委派链上的虚拟线程等待模型流。
- 跨 Runtime 调度、外部 A2A 协议、异步恢复、Artifact 和共享任务板属于后续阶段。
