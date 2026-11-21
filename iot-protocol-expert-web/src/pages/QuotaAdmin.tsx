import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { CurrentUser, QuotaGrantRequest, QuotaPolicyState, QuotaSubject } from '../api/types'
import {
  formatQuotaSubject,
  formatUnits,
  MY_QUOTA_KEY,
  policyStatus,
  quotaModeLabels
} from '../utils/quota'
import { formatDateTime } from '../utils/dateTime'

type ScopeKind = QuotaGrantRequest['scopeKind']
type Target = { value: string; label: string }

const durationOptions = [
  { value: 86_400, label: '天' },
  { value: 3_600, label: '小时' },
  { value: 60, label: '分钟' },
  { value: 1, label: '秒' }
]

interface QuotaFormValues {
  mode: QuotaGrantRequest['mode']
  limitUnits: number
  durationValue?: number
  durationUnit?: number
  policyKey?: string
  reason?: string
}

function PolicyEditor({
  scopeKind,
  target,
  policy,
  policies,
  canRead,
  onClose
}: {
  scopeKind: ScopeKind
  target: Target
  policy?: QuotaPolicyState
  policies: QuotaPolicyState[]
  canRead: boolean
  onClose: () => void
}) {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [form] = Form.useForm<QuotaFormValues>()
  const [requestKey, setRequestKey] = useState(() => crypto.randomUUID())
  const [newPolicyKey] = useState(() => `quota:${crypto.randomUUID()}`)
  const modeOptions = [
    { value: 'TOTAL' as const, label: quotaModeLabels.TOTAL },
    { value: 'ON_FIRST_USE_WINDOW' as const, label: quotaModeLabels.ON_FIRST_USE_WINDOW }
  ]
  const initialMode = policy?.mode ?? 'TOTAL'
  const mode = Form.useWatch('mode', form) ?? initialMode
  const changingMode = Boolean(policy && mode !== policy.mode)
  const durationUnit = Form.useWatch('durationUnit', form)
  const seconds = policy?.durationSeconds
  const initialUnit =
    seconds == null
      ? 86_400
      : (durationOptions.find(({ value }) => seconds % value === 0)?.value ?? 1)
  const grant = useMutation({
    mutationFn: (values: QuotaFormValues) =>
      api.grantQuota(
        {
          scopeKind,
          subjectId: target.value,
          mode: values.mode,
          limitUnits: values.limitUnits,
          durationSeconds:
            values.mode === 'ON_FIRST_USE_WINDOW'
              ? (values.durationValue ?? 0) * (values.durationUnit ?? 1)
              : undefined,
          policyKey:
            policy?.policyKey ?? (canRead ? newPolicyKey : values.policyKey?.trim() || undefined),
          reason: values.reason?.trim() || undefined
        },
        requestKey
      ),
    onSuccess: () => {
      message.success('额度策略已保存')
      void queryClient.invalidateQueries({
        queryKey: ['admin-quota-account', scopeKind, target.value]
      })
      void queryClient.invalidateQueries({ queryKey: MY_QUOTA_KEY })
      onClose()
    },
    onError: (error: Error) => message.error(error.message)
  })

  return (
    <Modal
      open
      title={policy ? '调整额度策略' : canRead ? '分配额度' : '保存额度策略'}
      okText={policy ? '保存调整' : canRead ? '确认分配' : '保存策略'}
      cancelText="取消"
      confirmLoading={grant.isPending}
      cancelButtonProps={{ disabled: grant.isPending }}
      closable={!grant.isPending}
      maskClosable={!grant.isPending}
      keyboard={!grant.isPending}
      onCancel={onClose}
      onOk={() => form.submit()}
    >
      <div className="quota-editor-target">
        <span>分配对象</span>
        <strong>{target.label}</strong>
      </div>
      <Form
        form={form}
        layout="vertical"
        disabled={grant.isPending}
        initialValues={{
          mode: initialMode,
          limitUnits: policy?.limitUnits,
          durationUnit: initialUnit,
          durationValue: seconds == null ? undefined : seconds / initialUnit
        }}
        onValuesChange={() => setRequestKey(crypto.randomUUID())}
        onFinish={(values) => grant.mutate(values)}
      >
        <Form.Item name="mode" label="额度类型" rules={[{ required: true }]}>
          <Select options={modeOptions} />
        </Form.Item>
        {changingMode ? (
          <Alert
            className="quota-editor-alert"
            type="warning"
            showIcon
            message="更换额度类型会重新开始该策略的用量统计。"
          />
        ) : !policy && policies.length > 0 ? (
          <Alert
            className="quota-editor-alert"
            type="info"
            showIcon
            message="新增策略后，每次调用须同时满足该对象的所有额度策略。"
          />
        ) : null}
        <Form.Item
          name="limitUnits"
          label="额度上限（Units）"
          extra={
            policy && !changingMode ? '保存后会更新该策略的上限，已用额度继续累计。' : undefined
          }
          rules={[{ required: true, message: '请输入额度上限' }]}
        >
          <InputNumber
            min={1}
            max={Number.MAX_SAFE_INTEGER}
            precision={0}
            style={{ width: '100%' }}
          />
        </Form.Item>
        {mode === 'ON_FIRST_USE_WINDOW' ? (
          <div className="quota-duration-fields">
            <Form.Item
              name="durationValue"
              label="周期时长"
              extra={
                policy && !changingMode
                  ? '修改后的时长从下一周期开始生效。'
                  : '从首次调用开始计时，到期后的调用会开启新周期。'
              }
              rules={[{ required: true, message: '请输入周期时长' }]}
            >
              <InputNumber
                min={1}
                max={Math.floor(Number.MAX_SAFE_INTEGER / (durationUnit ?? initialUnit))}
                precision={0}
                style={{ width: '100%' }}
              />
            </Form.Item>
            <Form.Item name="durationUnit" label="时长单位" rules={[{ required: true }]}>
              <Select options={durationOptions} />
            </Form.Item>
          </div>
        ) : null}
        {!canRead ? (
          <Form.Item
            name="policyKey"
            label="已有策略标识（选填）"
            extra="留空时按所选类型保存默认策略。"
          >
            <Input maxLength={64} />
          </Form.Item>
        ) : null}
        <Form.Item name="reason" label="调整说明（选填）">
          <Input.TextArea autoSize={{ minRows: 2, maxRows: 4 }} maxLength={500} />
        </Form.Item>
      </Form>
    </Modal>
  )
}

export default function QuotaAdmin({ user }: { user: CurrentUser }) {
  const canRead = Boolean(user.permissions?.includes('usage:read'))
  const canGrant = Boolean(user.permissions?.includes('quota:grant'))
  const [scopeKind, setScopeKind] = useState<ScopeKind>('USER')
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [target, setTarget] = useState<Target>()
  const [editor, setEditor] = useState<QuotaPolicyState | 'new' | null>(null)

  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(search.trim()), 250)
    return () => window.clearTimeout(timer)
  }, [search])

  const directory = useQuery({
    queryKey: ['quota-subjects', scopeKind, query],
    queryFn: () => api.quotaSubjects(scopeKind, query),
    enabled: canRead || canGrant
  })
  const account = useQuery({
    queryKey: ['admin-quota-account', scopeKind, target?.value],
    queryFn: () => api.adminQuotaAccount(scopeKind, target?.value ?? ''),
    enabled: canRead && Boolean(target)
  })
  const waitingForSearch = search.trim() !== query
  const policies = account.data?.policies ?? []
  const canCreate = !canRead || account.isSuccess

  if (!canRead && !canGrant) {
    return <Alert type="warning" showIcon message="当前账号无权访问额度管理" />
  }

  return (
    <div className="usage-page">
      <div className="page-header">
        <div>
          <h1>额度管理</h1>
          <p>按用户或服务账号查询、分配和调整模型额度。</p>
        </div>
      </div>
      <Card variant="borderless" className="usage-panel">
        <div className="quota-target-fields">
          <div>
            <label htmlFor="quota-scope">对象类型</label>
            <Select<ScopeKind>
              id="quota-scope"
              value={scopeKind}
              options={[
                { value: 'USER', label: '用户' },
                { value: 'SERVICE_ACCOUNT', label: '服务账号' }
              ]}
              onChange={(value) => {
                setScopeKind(value)
                setTarget(undefined)
                setSearch('')
                setQuery('')
              }}
            />
          </div>
          <div>
            <label htmlFor="quota-target">分配对象</label>
            <Select<Target>
              id="quota-target"
              value={target}
              labelInValue
              allowClear
              showSearch
              virtual={false}
              filterOption={false}
              searchValue={search}
              onSearch={(value) => setSearch(value.slice(0, 120))}
              onOpenChange={(open) => {
                if (!open) setSearch('')
              }}
              placeholder={
                scopeKind === 'USER' ? '搜索姓名或登录账号' : '搜索服务名称、账号标识或项目'
              }
              loading={directory.isFetching || waitingForSearch}
              notFoundContent={
                directory.isFetching || waitingForSearch ? '正在搜索…' : '暂无匹配对象'
              }
              options={
                waitingForSearch
                  ? []
                  : directory.data?.map((subject: QuotaSubject) => ({
                      value: subject.id,
                      label:
                        formatQuotaSubject(subject) +
                        (subject.state === 'ACTIVE' ? '' : ' · 已停用')
                    }))
              }
              onChange={(value) => {
                setTarget(value)
                setSearch('')
              }}
            />
          </div>
        </div>
        {directory.error ? (
          <Alert
            className="quota-inline-alert"
            type="error"
            showIcon
            message="分配对象加载失败"
            action={
              <Button size="small" onClick={() => directory.refetch()}>
                重试
              </Button>
            }
          />
        ) : null}
      </Card>

      <Card
        title={target?.label ?? '额度策略'}
        variant="borderless"
        className="usage-panel quota-admin-card"
        extra={
          target ? (
            <Space wrap>
              {canRead ? (
                <Button
                  icon={<ReloadOutlined />}
                  loading={account.isFetching}
                  onClick={() => account.refetch()}
                >
                  刷新
                </Button>
              ) : null}
              {canGrant && canCreate ? (
                <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditor('new')}>
                  {canRead ? '分配额度' : '配置额度'}
                </Button>
              ) : null}
            </Space>
          ) : null
        }
      >
        {!target ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请选择要查看的用户或服务账号" />
        ) : !canRead ? (
          <Alert
            type="info"
            showIcon
            message="当前账号可以配置额度，查看余额和策略列表需要用量查看权限。"
          />
        ) : account.error ? (
          <Alert
            type="error"
            showIcon
            message="额度策略加载失败"
            description={account.error.message}
          />
        ) : (
          <>
            {account.data ? (
              <div className="quota-account-summary">
                <span>
                  当前可用 <strong>{formatUnits(account.data.availableUnits)}</strong> Units
                </span>
                {account.data.pendingReconciliation > 0 ? (
                  <Tag color="orange">待对账 {account.data.pendingReconciliation} 次</Tag>
                ) : null}
              </div>
            ) : null}
            <Table<QuotaPolicyState>
              rowKey="accountId"
              size="middle"
              loading={account.isLoading}
              dataSource={policies}
              pagination={false}
              scroll={{ x: 790 }}
              locale={{ emptyText: '该对象尚未分配额度' }}
              columns={[
                {
                  title: '策略',
                  render: (_, policy) => (
                    <div className="usage-units">
                      <strong>{quotaModeLabels[policy.mode]}</strong>
                      <small className="mono">{policy.policyKey}</small>
                    </div>
                  )
                },
                {
                  title: '上限',
                  dataIndex: 'limitUnits',
                  width: 85,
                  align: 'right',
                  render: formatUnits
                },
                {
                  title: '已用',
                  dataIndex: 'usedUnits',
                  width: 85,
                  align: 'right',
                  render: formatUnits
                },
                {
                  title: '冻结',
                  dataIndex: 'heldUnits',
                  width: 85,
                  align: 'right',
                  render: formatUnits
                },
                {
                  title: '可用',
                  dataIndex: 'availableUnits',
                  width: 85,
                  align: 'right',
                  render: formatUnits
                },
                {
                  title: '状态',
                  width: 150,
                  render: (_, policy) => {
                    const status = policyStatus(policy)
                    return (
                      <div className="usage-units">
                        <Tag color={status.color} bordered={false}>
                          {status.label}
                        </Tag>
                        {!policy.expiredWindow && policy.endAt ? (
                          <small>到期 {formatDateTime(policy.endAt)}</small>
                        ) : null}
                      </div>
                    )
                  }
                },
                ...(canGrant
                  ? [
                      {
                        title: '操作',
                        width: 80,
                        render: (_: unknown, policy: QuotaPolicyState) => (
                          <Button type="link" onClick={() => setEditor(policy)}>
                            调整
                          </Button>
                        )
                      }
                    ]
                  : [])
              ]}
            />
          </>
        )}
      </Card>

      {target && editor ? (
        <PolicyEditor
          scopeKind={scopeKind}
          target={target}
          policy={editor === 'new' ? undefined : editor}
          policies={policies}
          canRead={canRead}
          onClose={() => setEditor(null)}
        />
      ) : null}
    </div>
  )
}
