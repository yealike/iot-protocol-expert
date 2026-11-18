import { Alert, Card, Col, Empty, Row, Statistic, Table, Tag, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CurrentUser, UsageItem } from '../api/types'
import QuotaAdmin from './QuotaAdmin'

const { Text } = Typography

function formatUnits(value: number) {
  return value.toLocaleString('zh-CN')
}

function activationLabel(state: string) {
  return state === 'ACTIVE' ? '窗口已激活' : '等待首次调用'
}

export default function Usage({ user }: { user: CurrentUser }) {
  const quota = useQuery({ queryKey: ['my-quota'], queryFn: api.myQuota })
  const usage = useQuery({ queryKey: ['my-usage'], queryFn: () => api.myUsage(100) })
  const canGrant = (user.permissions ?? []).includes('quota:grant')

  return (
    <div className="page-stack">
      <Card title="模型额度" loading={quota.isLoading}>
        {quota.error ? (
          <Alert type="error" showIcon message={(quota.error as Error).message} />
        ) : null}
        {quota.isSuccess && !quota.error && quota.data.policies.length === 0 ? (
          <Empty description="当前账号尚未分配模型额度" />
        ) : null}
        <Row gutter={[16, 16]}>
          {(quota.data?.policies ?? []).map((policy) => (
            <Col xs={24} lg={12} key={policy.accountId + policy.policyKey}>
              <Card size="small" type="inner" title={policy.policyKey}>
                <Row gutter={16}>
                  <Col span={8}>
                    <Statistic
                      title="限额"
                      value={policy.limitUnits}
                      formatter={(v) => formatUnits(Number(v))}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title="已用"
                      value={policy.usedUnits}
                      formatter={(v) => formatUnits(Number(v))}
                    />
                  </Col>
                  <Col span={8}>
                    <Statistic
                      title="剩余"
                      value={policy.remainingUnits}
                      formatter={(v) => formatUnits(Number(v))}
                    />
                  </Col>
                </Row>
                <div style={{ marginTop: 12, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <Tag color={policy.mode === 'TOTAL' ? 'blue' : 'purple'}>{policy.mode}</Tag>
                  <Tag color={policy.activationState === 'ACTIVE' ? 'green' : 'default'}>
                    {activationLabel(policy.activationState)}
                  </Tag>
                  <Text type="secondary">冻结 {formatUnits(policy.heldUnits)}</Text>
                  {policy.debtUnits > 0 ? (
                    <Text type="danger">待补扣 {formatUnits(policy.debtUnits)}</Text>
                  ) : null}
                </div>
                {policy.startAt ? (
                  <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
                    窗口：{policy.startAt} → {policy.endAt}
                  </Text>
                ) : null}
              </Card>
            </Col>
          ))}
        </Row>
        {(quota.data?.pendingReconciliation ?? 0) > 0 ? (
          <Alert
            style={{ marginTop: 16 }}
            type="warning"
            showIcon
            message={`有 ${quota.data?.pendingReconciliation} 次调用等待对账`}
          />
        ) : null}
      </Card>

      <Card title="最近模型调用" loading={usage.isLoading}>
        {usage.error ? (
          <Alert type="error" showIcon message={(usage.error as Error).message} />
        ) : null}
        <Table<UsageItem>
          rowKey="callId"
          size="small"
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          dataSource={usage.data ?? []}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 180 },
            { title: '逻辑模型', dataIndex: 'model', width: 160 },
            { title: '供应商', dataIndex: 'provider', width: 140 },
            {
              title: '状态',
              dataIndex: 'state',
              width: 120,
              render: (value: string) => (
                <Tag
                  color={
                    value === 'SETTLED' ? 'green' : value === 'UNCERTAIN' ? 'orange' : 'default'
                  }
                >
                  {value}
                </Tag>
              )
            },
            {
              title: '输入 / 输出',
              width: 140,
              render: (_, row) =>
                `${formatUnits(row.inputTokens)} / ${formatUnits(row.outputTokens)}`
            },
            {
              title: '计量',
              dataIndex: 'quotaUnits',
              width: 120,
              render: (value: number, row) => `${formatUnits(value)} (${row.quality})`
            },
            { title: 'Run', dataIndex: 'runId', ellipsis: true }
          ]}
        />
      </Card>

      {canGrant ? <QuotaAdmin user={user} /> : null}
    </div>
  )
}
