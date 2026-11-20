import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Tag,
  Typography
} from 'antd'
import { api } from '../api/client'
import type { CurrentUser, QuotaGrantRequest, QuotaPolicyState } from '../api/types'

const { Text } = Typography

interface QuotaGrantFormValues {
  scopeKind: QuotaGrantRequest['scopeKind']
  subjectId: string
  mode: QuotaGrantRequest['mode']
  limitUnits: number
  durationSeconds?: number
  policyKey?: string
  reason?: string
}

export default function QuotaAdmin({ user }: { user: CurrentUser }) {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [form] = Form.useForm<QuotaGrantFormValues>()
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())
  const scopeKind = Form.useWatch('scopeKind', form) ?? 'USER'
  const subjectId = (Form.useWatch('subjectId', form) ?? '').trim()
  const mode = Form.useWatch('mode', form) ?? 'TOTAL'
  const canRead = (user.permissions ?? []).includes('usage:read')
  const account = useQuery({
    queryKey: ['admin-quota-account', scopeKind, subjectId],
    queryFn: () => api.adminQuotaAccount(scopeKind, subjectId),
    enabled: canRead && !!subjectId
  })
  const grant = useMutation({
    mutationFn: ({
      values,
      requestKey
    }: {
      values: QuotaGrantFormValues
      requestKey: string
    }) => {
      const body: QuotaGrantRequest = {
        scopeKind: values.scopeKind,
        subjectId: values.subjectId.trim(),
        mode: values.mode,
        limitUnits: values.limitUnits,
        policyKey: values.policyKey?.trim() || undefined,
        reason: values.reason?.trim() || undefined,
        ...(values.mode === 'ON_FIRST_USE_WINDOW'
          ? { durationSeconds: values.durationSeconds }
          : {})
      }
      return api.grantQuota(body, requestKey)
    },
    onSuccess: () => {
      message.success('额度策略已保存')
      setIdempotencyKey(crypto.randomUUID())
      void queryClient.invalidateQueries({
        queryKey: ['admin-quota-account', scopeKind, subjectId]
      })
    },
    onError: (error: Error) => message.error(error.message)
  })

  return (
    <Card title="额度分配与管理" variant="borderless" className="quota-admin-card">
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="TOTAL 是该策略的额度上限，不是在当前余额上追加。"
        description="调整同一策略时请复用 policyKey。窗口模式在首次调用时激活，到期后由后续调用开启新窗口。"
      />
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          scopeKind: 'USER',
          subjectId: user.id,
          mode: 'TOTAL',
          limitUnits: 100000
        }}
        onValuesChange={() => setIdempotencyKey(crypto.randomUUID())}
        onFinish={(values) => grant.mutate({ values, requestKey: idempotencyKey })}
      >
        <div className="product-form-grid">
          <Form.Item name="scopeKind" label="主体类型" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'USER', label: '用户' },
                { value: 'SERVICE_ACCOUNT', label: '服务账号' }
              ]}
            />
          </Form.Item>
          <Form.Item name="subjectId" label="主体 ID" rules={[{ required: true }]}>
            <Input placeholder="用户 ID 或服务账号标识" />
          </Form.Item>
          <Form.Item name="mode" label="额度模式" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'TOTAL', label: 'TOTAL · 固定额度上限' },
                { value: 'ON_FIRST_USE_WINDOW', label: 'ON_FIRST_USE_WINDOW · 首次使用窗口' }
              ]}
            />
          </Form.Item>
          <Form.Item name="limitUnits" label="额度上限" rules={[{ required: true }]}>
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          {mode === 'ON_FIRST_USE_WINDOW' ? (
            <Form.Item
              name="durationSeconds"
              label="窗口时长（秒）"
              rules={[{ required: true }]}
            >
              <InputNumber min={1} precision={0} style={{ width: '100%' }} placeholder="3600" />
            </Form.Item>
          ) : null}
          <Form.Item
            name="policyKey"
            label="策略标识（可选）"
            extra="调整已有策略时必须复用原策略标识。"
          >
            <Input placeholder="user:<id>:TOTAL" />
          </Form.Item>
        </div>
        <Form.Item name="reason" label="原因">
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={500} />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={grant.isPending}>
          保存额度策略
        </Button>
      </Form>

      {!canRead ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 16 }}
          message="当前账号缺少 usage:read，无法预览目标主体的额度快照。"
        />
      ) : null}
      {account.error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginTop: 16 }}
          message={(account.error as Error).message}
        />
      ) : null}
      {account.data ? (
        <Space direction="vertical" size={12} style={{ width: '100%', marginTop: 16 }}>
          <Descriptions
            size="small"
            bordered
            column={2}
            items={[
              { key: 'scope', label: '主体', children: `${account.data.scopeKind} / ${account.data.subjectId}` },
              {
                key: 'pending',
                label: '待对账',
                children: String(account.data.pendingReconciliation)
              }
            ]}
          />
          <Table<QuotaPolicyState>
            rowKey={(policy) => policy.accountId + policy.policyKey}
            size="small"
            dataSource={account.data.policies}
            pagination={false}
            locale={{ emptyText: '该主体暂无额度策略' }}
            columns={[
              { title: '策略', dataIndex: 'policyKey' },
              {
                title: '模式',
                dataIndex: 'mode',
                width: 180,
                render: (value: string) => <Tag>{value}</Tag>
              },
              { title: '限额', dataIndex: 'limitUnits', width: 130 },
              { title: '已用', dataIndex: 'usedUnits', width: 130 },
              { title: '冻结', dataIndex: 'heldUnits', width: 130 },
              { title: '剩余', dataIndex: 'remainingUnits', width: 130 },
              {
                title: '状态',
                width: 130,
                render: (_, policy) => (
                  <Text type={policy.state === 'ACTIVE' ? undefined : 'secondary'}>
                    {policy.state} / {policy.activationState}
                  </Text>
                )
              }
            ]}
          />
        </Space>
      ) : null}
    </Card>
  )
}
