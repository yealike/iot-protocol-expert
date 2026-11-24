import type { ReactNode } from 'react'
import { Button, Collapse, Space, Tag, Typography } from 'antd'
import { LoadingOutlined } from '@ant-design/icons'
import type { AiDelegation } from '../api/types'

const states: Record<AiDelegation['state'], { label: string; color: string }> = {
  SUBMITTED: { label: '已提交', color: 'blue' },
  WORKING: { label: '执行中', color: 'processing' },
  CANCEL_REQUESTED: { label: '正在停止', color: 'orange' },
  COMPLETED: { label: '已完成', color: 'success' },
  FAILED: { label: '失败', color: 'error' },
  CANCELED: { label: '已取消', color: 'default' },
  REJECTED: { label: '已拒绝', color: 'error' }
}

export function DelegationStack({
  tasks,
  onOpen,
  renderResult
}: {
  tasks?: AiDelegation[]
  onOpen: (task: AiDelegation) => void
  renderResult: (result: string) => ReactNode
}) {
  if (!tasks?.length) return null
  return (
    <Collapse
      size="small"
      style={{ marginBottom: 10 }}
      items={tasks.map((task) => {
        const state = states[task.state]
        const summary = task.taskSummary ? Array.from(task.taskSummary) : []
        return {
          key: task.taskId,
          label: (
            <Space size={[6, 4]} wrap>
              <strong>{task.targetAgentName || task.targetAgentKey}</strong>
              <Tag
                color={state.color}
                icon={task.state === 'WORKING' ? <LoadingOutlined /> : undefined}
              >
                {state.label}
              </Tag>
              {summary.length ? (
                <Typography.Text type="secondary">
                  {summary.slice(0, 40).join('')}
                  {summary.length > 40 ? '…' : ''}
                </Typography.Text>
              ) : null}
              {task.durationMs != null ? (
                <span>{(task.durationMs / 1000).toFixed(1)} 秒</span>
              ) : null}
              {task.usage?.totalTokens != null ? (
                <span>{task.usage.totalTokens.toLocaleString()} Token</span>
              ) : null}
            </Space>
          ),
          children: (
            <Space direction="vertical" size="small" style={{ width: '100%' }}>
              <Typography.Text type="secondary">{task.alias}</Typography.Text>
              {task.taskSummary ? (
                <Typography.Paragraph>{task.taskSummary}</Typography.Paragraph>
              ) : null}
              {task.resultSummary ? renderResult(task.resultSummary) : null}
              {task.truncated ? (
                <Typography.Text type="secondary">完整结果见子任务轨迹。</Typography.Text>
              ) : null}
              {task.errorMessage ? (
                <Typography.Text type="danger">{task.errorMessage}</Typography.Text>
              ) : null}
              {task.errorCode ? <Typography.Text code>{task.errorCode}</Typography.Text> : null}
              <Typography.Text copyable={{ text: task.taskId }} type="secondary">
                复制任务 ID
              </Typography.Text>
              {task.childConversationId ? (
                <Button size="small" onClick={() => onOpen(task)}>
                  查看子任务轨迹
                </Button>
              ) : null}
            </Space>
          )
        }
      })}
    />
  )
}
