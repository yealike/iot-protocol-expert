import { Alert, Button, Card, Empty, Progress, Table, Tag } from 'antd'
import {
  AccountBookOutlined,
  LockOutlined,
  PayCircleOutlined,
  ReloadOutlined,
  WalletOutlined
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CurrentUser, UsageItem } from '../api/types'
import { formatDateTime } from '../utils/dateTime'
import { MetricCard } from '../components/tags'
import QuotaAdmin from './QuotaAdmin'

function formatUnits(value: number) {
  return value.toLocaleString('zh-CN')
}

function activationLabel(state: string) {
  return state === 'ACTIVE' ? '窗口已激活' : '等待首次调用'
}

const callStateLabels: Record<string, string> = {
  SETTLED: '已结算',
  UNCERTAIN: '待核对',
  RESERVED: '已预留',
  DISPATCHED: '调用中',
  RELEASED: '已释放'
}

export default function Usage({ user }: { user: CurrentUser }) {
  const quota = useQuery({ queryKey: ['my-quota'], queryFn: api.myQuota })
  const usage = useQuery({ queryKey: ['my-usage'], queryFn: () => api.myUsage(100) })
  const canGrant = (user.permissions ?? []).includes('quota:grant')
  const policies = quota.data?.policies ?? []
  const totals = policies.reduce(
    (result, policy) => ({
      limit: result.limit + policy.limitUnits,
      used: result.used + policy.usedUnits,
      held: result.held + policy.heldUnits,
      remaining: result.remaining + policy.remainingUnits
    }),
    { limit: 0, used: 0, held: 0, remaining: 0 }
  )

  return (
    <div className="usage-page">
      <div className="page-header">
        <div>
          <h1>模型额度</h1>
          <p>统一查看额度消耗、策略窗口和模型调用明细。</p>
        </div>
        <Button
          icon={<ReloadOutlined />}
          loading={quota.isFetching || usage.isFetching}
          onClick={() => Promise.all([quota.refetch(), usage.refetch()])}
        >
          刷新数据
        </Button>
      </div>

      {quota.error ? <Alert type="error" showIcon message={(quota.error as Error).message} /> : null}

      <div className="metric-grid usage-summary-grid">
        <MetricCard icon={<WalletOutlined />} label="额度上限" value={quota.data ? formatUnits(totals.limit) : '—'} suffix="Units" />
        <MetricCard icon={<AccountBookOutlined />} label="累计已用" value={quota.data ? formatUnits(totals.used) : '—'} suffix="Units" />
        <MetricCard icon={<PayCircleOutlined />} label="当前剩余" value={quota.data ? formatUnits(totals.remaining) : '—'} suffix="Units" />
        <MetricCard icon={<LockOutlined />} label="调用冻结" value={quota.data ? formatUnits(totals.held) : '—'} suffix="Units" />
      </div>

      <Card
        title="额度策略"
        variant="borderless"
        className="usage-panel"
        extra={<span className="muted">{policies.length} 条策略</span>}
        loading={quota.isLoading}
      >
        {quota.isSuccess && !quota.error && policies.length === 0 ? (
          <Empty description="当前账号尚未分配模型额度" />
        ) : null}
        <div className="usage-policy-grid">
          {policies.map((policy) => {
            const usedPercent = policy.limitUnits > 0
              ? Math.min(100, Math.round((policy.usedUnits / policy.limitUnits) * 100))
              : 0
            return (
              <section className="usage-policy-card" key={policy.accountId + policy.policyKey}>
                <div className="usage-policy-header">
                  <div><span>额度策略</span><strong className="mono">{policy.policyKey}</strong></div>
                  <Tag color={policy.activationState === 'ACTIVE' ? 'green' : 'default'} bordered={false}>
                    {activationLabel(policy.activationState)}
                  </Tag>
                </div>
                <div className="usage-progress-heading"><span>额度使用进度</span><strong>{usedPercent}%</strong></div>
                <Progress
                  percent={usedPercent}
                  showInfo={false}
                  strokeColor={policy.remainingUnits <= 0 ? '#d9525c' : '#3563e9'}
                  trailColor="#edf1f6"
                />
                <div className="usage-policy-stats">
                  <div><span>额度上限</span><strong>{formatUnits(policy.limitUnits)}</strong></div>
                  <div><span>已使用</span><strong>{formatUnits(policy.usedUnits)}</strong></div>
                  <div><span>剩余</span><strong>{formatUnits(policy.remainingUnits)}</strong></div>
                </div>
                <div className="usage-policy-meta">
                  <Tag color={policy.mode === 'TOTAL' ? 'blue' : 'purple'} bordered={false}>
                    {policy.mode === 'TOTAL' ? '固定额度' : '首次使用窗口'}
                  </Tag>
                  <span>冻结 {formatUnits(policy.heldUnits)}</span>
                  {policy.debtUnits > 0 ? <span className="danger-text">待补扣 {formatUnits(policy.debtUnits)}</span> : null}
                </div>
                {policy.startAt ? (
                  <div className="usage-policy-window">
                    窗口：{formatDateTime(policy.startAt)} 至 {formatDateTime(policy.endAt)}
                  </div>
                ) : null}
              </section>
            )
          })}
        </div>
        {(quota.data?.pendingReconciliation ?? 0) > 0 ? (
          <Alert
            className="reconciliation-alert"
            type="warning"
            showIcon
            message={`有 ${quota.data?.pendingReconciliation} 次调用等待对账`}
          />
        ) : null}
      </Card>

      <Card title="最近模型调用" variant="borderless" className="usage-panel usage-table-panel" loading={usage.isLoading}>
        {usage.error ? <Alert type="error" showIcon message={(usage.error as Error).message} /> : null}
        <Table<UsageItem>
          rowKey="callId"
          size="middle"
          scroll={{ x: 1120 }}
          pagination={{ pageSize: 20, hideOnSinglePage: true, showTotal: (total) => `共 ${total} 次调用` }}
          dataSource={usage.data ?? []}
          columns={[
            { title: '调用时间', dataIndex: 'createdAt', width: 180, render: (value: string) => formatDateTime(value) },
            { title: '逻辑模型', dataIndex: 'model', width: 160, render: (value: string) => <span className="usage-model-name">{value}</span> },
            { title: '供应商', dataIndex: 'provider', width: 140 },
            {
              title: '状态',
              dataIndex: 'state',
              width: 110,
              render: (value: string) => (
                <Tag color={value === 'SETTLED' ? 'green' : value === 'UNCERTAIN' ? 'orange' : 'default'} bordered={false}>
                  {callStateLabels[value] ?? value}
                </Tag>
              )
            },
            {
              title: '输入 / 输出 Tokens',
              width: 170,
              align: 'right',
              render: (_, row) => `${formatUnits(row.inputTokens)} / ${formatUnits(row.outputTokens)}`
            },
            {
              title: '计量（Units）',
              dataIndex: 'quotaUnits',
              width: 150,
              align: 'right',
              render: (value: number, row) => (
                <div className="usage-units"><strong>{formatUnits(value)}</strong><small>{row.quality}</small></div>
              )
            },
            { title: 'Run ID', dataIndex: 'runId', ellipsis: true, render: (value?: string) => <span className="mono muted">{value || '—'}</span> }
          ]}
        />
      </Card>

      {canGrant ? <QuotaAdmin user={user} /> : null}
    </div>
  )
}
