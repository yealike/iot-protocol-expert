import { Alert, Button, Card, Empty, Progress, Skeleton, Table, Tag } from 'antd'
import { ReloadOutlined, WalletOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { NavLink } from 'react-router-dom'
import { api } from '../api/client'
import type { QuotaPolicyState, UsageItem } from '../api/types'
import { formatDateTime } from '../utils/dateTime'
import {
  formatUnits,
  MY_QUOTA_KEY,
  MY_USAGE_KEY,
  policyStatus,
  quotaModeLabels,
  quotaStatus
} from '../utils/quota'

const callStateLabels: Record<string, string> = {
  SETTLED: '已结算',
  UNCERTAIN: '待核对',
  HELD: '已预留',
  RESERVED: '已预留',
  DISPATCHED: '调用中',
  RELEASED: '已释放'
}

function PolicyCard({ policy }: { policy: QuotaPolicyState }) {
  const status = policyStatus(policy)
  const percent = Math.min(100, Math.round((policy.usedUnits / policy.limitUnits) * 100))
  const isWindow = policy.mode === 'ON_FIRST_USE_WINDOW'
  return (
    <section className="usage-policy-card">
      <div className="usage-policy-header">
        <h3>{quotaModeLabels[policy.mode]}</h3>
        <Tag color={status.color} bordered={false}>
          {status.label}
        </Tag>
      </div>
      <div className="usage-progress-heading">
        <span>{policy.expiredWindow ? '上一周期使用进度' : '额度使用进度'}</span>
        <strong>{percent}%</strong>
      </div>
      <Progress percent={percent} showInfo={false} />
      <div className="usage-policy-stats">
        <div>
          <span>上限</span>
          <strong>{formatUnits(policy.limitUnits)}</strong>
        </div>
        <div>
          <span>{policy.expiredWindow ? '上期已用' : '已用'}</span>
          <strong>{formatUnits(policy.usedUnits)}</strong>
        </div>
        <div>
          <span>冻结</span>
          <strong>{formatUnits(policy.heldUnits)}</strong>
        </div>
      </div>
      <div className="usage-policy-meta">
        <span>可用 {formatUnits(policy.availableUnits)} Units</span>
        {policy.debtUnits > 0 ? (
          <span className="danger-text">待补扣 {formatUnits(policy.debtUnits)}</span>
        ) : null}
      </div>
      {isWindow && policy.endAt && !policy.expiredWindow ? (
        <div className="usage-policy-window">本周期到期时间 {formatDateTime(policy.endAt)}</div>
      ) : null}
    </section>
  )
}

export function QuotaOverview() {
  const quota = useQuery({
    queryKey: MY_QUOTA_KEY,
    queryFn: api.myQuota,
    refetchInterval: 30_000
  })

  if (quota.error) {
    return (
      <Alert
        type="error"
        showIcon
        message="额度查询失败"
        description={quota.error.message}
        action={<Button onClick={() => quota.refetch()}>重试</Button>}
      />
    )
  }
  if (!quota.data)
    return (
      <Card variant="borderless">
        <Skeleton active />
      </Card>
    )

  const status = quotaStatus(quota.data)
  const { policies, availableUnits, pendingReconciliation } = quota.data
  return (
    <>
      <section className="quota-overview-balance">
        <div>
          <div className="quota-overview-label">
            <WalletOutlined /> 当前可用额度
          </div>
          <div className="quota-overview-value">
            {formatUnits(availableUnits)} <span>Units</span>
          </div>
          <Tag
            color={status.tone === 'normal' ? 'green' : status.tone === 'warning' ? 'gold' : 'red'}
            bordered={false}
          >
            {status.label}
          </Tag>
        </div>
        <div className="quota-overview-note">
          <Button
            icon={<ReloadOutlined />}
            loading={quota.isFetching}
            onClick={() => quota.refetch()}
          >
            刷新额度
          </Button>
          <span>
            {policies.length > 1
              ? '每次调用受所有额度策略共同限制，以最少的可用额度为准。'
              : 'Units 是额度计量单位。输入和输出 Token 可在用量明细中查看。'}
          </span>
        </div>
      </section>
      {pendingReconciliation > 0 ? (
        <Alert type="warning" showIcon message={`有 ${pendingReconciliation} 次模型调用等待对账`} />
      ) : null}
      <Card title="我的额度策略" variant="borderless" className="usage-panel">
        {policies.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="当前账号尚未分配模型额度，请联系管理员。"
          />
        ) : (
          <div className="usage-policy-grid">
            {policies.map((policy) => (
              <PolicyCard key={policy.accountId} policy={policy} />
            ))}
          </div>
        )}
      </Card>
    </>
  )
}

function UsageHistory() {
  const usage = useQuery({ queryKey: MY_USAGE_KEY, queryFn: () => api.myUsage() })
  return (
    <Card
      title="最近 100 次模型调用"
      variant="borderless"
      className="usage-panel usage-table-panel"
      extra={
        <Button
          icon={<ReloadOutlined />}
          loading={usage.isFetching}
          onClick={() => usage.refetch()}
        >
          刷新
        </Button>
      }
    >
      {usage.error ? <Alert type="error" showIcon message={usage.error.message} /> : null}
      <Table<UsageItem>
        rowKey="callId"
        loading={usage.isLoading}
        scroll={{ x: 880 }}
        pagination={{
          pageSize: 20,
          hideOnSinglePage: true,
          showTotal: (total) => `共 ${total} 次调用`
        }}
        dataSource={usage.data ?? []}
        locale={{ emptyText: usage.error ? '暂时无法载入调用明细' : '暂无模型调用' }}
        columns={[
          {
            title: '调用时间',
            dataIndex: 'createdAt',
            width: 172,
            render: (value: string) => formatDateTime(value)
          },
          {
            title: '模型',
            render: (_, row) => (
              <div className="usage-units">
                <strong>{row.model}</strong>
                <small>{row.provider}</small>
              </div>
            )
          },
          {
            title: '状态',
            dataIndex: 'state',
            width: 100,
            render: (value: string) => (
              <Tag
                color={value === 'SETTLED' ? 'green' : value === 'UNCERTAIN' ? 'orange' : 'default'}
                bordered={false}
              >
                {callStateLabels[value] ?? value}
              </Tag>
            )
          },
          {
            title: '输入 Token',
            width: 115,
            align: 'right',
            render: (_, row) =>
              formatUnits(row.quality === 'ESTIMATED_CAP' ? null : row.inputTokens)
          },
          {
            title: '输出 Token',
            width: 115,
            align: 'right',
            render: (_, row) =>
              formatUnits(row.quality === 'ESTIMATED_CAP' ? null : row.outputTokens)
          },
          {
            title: '扣减（Units）',
            dataIndex: 'quotaUnits',
            width: 130,
            align: 'right',
            render: (value: number | null, row) => (
              <div className="usage-units">
                <strong>{formatUnits(value)}</strong>
                {row.quality === 'ESTIMATED_CAP' ? <small>按预留上限结算</small> : null}
              </div>
            )
          },
          {
            title: 'Run ID',
            dataIndex: 'runId',
            width: 125,
            ellipsis: true,
            render: (value?: string) => <span className="mono muted">{value || '—'}</span>
          }
        ]}
      />
    </Card>
  )
}

export default function Usage({ view }: { view: 'quota' | 'history' }) {
  return (
    <div className="usage-page">
      <div className="page-header">
        <div>
          <h1>模型额度</h1>
          <p>查看自己的可用额度和模型调用记录。</p>
        </div>
      </div>
      <nav className="quota-page-nav" aria-label="模型额度">
        <NavLink to="/usage" end>
          额度总览
        </NavLink>
        <NavLink to="/usage/history">用量明细</NavLink>
      </nav>
      {view === 'quota' ? <QuotaOverview /> : <UsageHistory />}
    </div>
  )
}
