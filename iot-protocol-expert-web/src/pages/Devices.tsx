import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography
} from 'antd'
import {
  ApiOutlined,
  DeleteOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined
} from '@ant-design/icons'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { productProtocol } from '../api/protocol'
import { DeviceStateTag } from '../components/tags'
import type {
  DeviceRow,
  DeviceSaveRequest,
  DeviceState
} from '../api/types'
import { formatDateTime } from '../utils/dateTime'

const STATE_OPTIONS: { label: string; value: DeviceState }[] = [
  { label: '在线', value: 'ONLINE' },
  { label: '离线', value: 'OFFLINE' },
  { label: '故障', value: 'FAULT' },
  { label: '未激活', value: 'NOT_ACTIVE' }
]

interface DeviceFormValues {
  sn?: string
  name: string
  productId: number
  enabled: boolean
  protocolMappings: Array<{
    thirdPartyDeviceId?: string
    protocolConnectorNo?: string
  }>
}

function emptyMappings(count = 1): DeviceFormValues['protocolMappings'] {
  return Array.from({ length: Math.max(1, count) }, () => ({
    thirdPartyDeviceId: '',
    protocolConnectorNo: ''
  }))
}

function gunNoFor(index: number): string {
  return String(index + 1).padStart(2, '0')
}

export default function Devices() {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [searchInput, setSearchInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [states, setStates] = useState<DeviceState[] | undefined>()
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [creating, setCreating] = useState(false)
  const [saving, setSaving] = useState(false)
  const [searching, setSearching] = useState(false)
  const [form] = Form.useForm<DeviceFormValues>()
  const watchedSn = Form.useWatch('sn', form)
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })

  const products = useQuery({
    queryKey: ['device-create-products'],
    queryFn: () => api.queryProducts({ size: 100 }),
    enabled: creating
  })

  const devices = useQuery({
    queryKey: ['devices', keyword, states, page, size],
    queryFn: () =>
      api.queryDevices({
        keyword: keyword || undefined,
        states: states?.length ? states : undefined,
        page,
        size,
        sort: '-lastSeen'
      })
  })

  const columns = useMemo(
    () => [
      {
        title: '设备 SN',
        dataIndex: 'deviceSn',
        width: 170,
        render: (deviceSn: string, row: DeviceRow) => (
          <Typography.Text
            className="copyable-id"
            copyable={{ text: deviceSn, tooltips: ['复制设备 SN', '已复制'] }}
          >
            <Link
              className="mono device-sn-link"
              to={`/devices/${row.id}`}
              onClick={(event) => event.stopPropagation()}
            >
              {deviceSn}
            </Link>
          </Typography.Text>
        )
      },
      {
        title: '设备协议',
        dataIndex: 'protocol',
        width: 140,
        render: (_: string, row: DeviceRow) => (
          <Tag className="protocol-tag" bordered={false}>
            {productProtocol(row)}
          </Tag>
        )
      },
      {
        title: '状态',
        dataIndex: 'state',
        width: 110,
        render: (state: DeviceState) => <DeviceStateTag state={state} />
      },
      {
        title: '关键指标',
        dataIndex: 'keyMetrics',
        render: (keyMetrics: Record<string, string | number> | null) => {
          const entries = Object.entries(keyMetrics ?? {}).slice(0, 4)
          return entries.length ? (
            <div className="device-metric-tags">
              {entries.map(([key, value]) => (
                <span key={key}>{String(value)}</span>
              ))}
            </div>
          ) : (
            <span className="muted">暂无运行数据</span>
          )
        }
      },
      {
        title: '产品',
        dataIndex: 'productName',
        width: 190,
        render: (_: string | null, row: DeviceRow) => (
          <Link
            to={`/products/${row.productId}`}
            onClick={(event) => event.stopPropagation()}
          >
            {row.productName ?? '—'}
          </Link>
        )
      },
      {
        title: '最近上报',
        dataIndex: 'lastSeen',
        width: 180,
        render: (value: string | null) => (
          <span className="table-time">{formatDateTime(value, '从未上报')}</span>
        )
      }
    ],
    []
  )

  const search = async (raw: string) => {
    const value = raw.trim()
    setSearchInput(value)
    setPage(0)

    if (!/^\d{10}$/.test(value)) {
      setKeyword(value)
      return
    }

    setSearching(true)
    try {
      const matched = await api.lookupDevice(value)
      const gunNo = matched.matchedGunNo ?? value.slice(8)
      navigate(`/devices/${matched.id}?gun=${gunNo}`)
    } catch (error) {
      message.error(error instanceof Error ? error.message : '设备搜索失败')
    } finally {
      setSearching(false)
    }
  }

  return (
    <>
      <div className="page-header device-list-header">
        <div>
          <h1>充电桩设备</h1>
          <p>以平台 8 位设备 SN 为唯一身份，10 位枪号用于枪口级定位与协议映射。</p>
        </div>
        <Space wrap className="device-list-actions">
          {me.data?.permissions?.includes('ops:manage') && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              新建设备
            </Button>
          )}
          <Input.Search
            allowClear
            value={searchInput}
            loading={searching}
            prefix={<SearchOutlined />}
            placeholder="8 位设备 SN / 10 位枪号 / 名称"
            className="device-search"
            onChange={(event) => setSearchInput(event.target.value)}
            onSearch={search}
          />
          <Select
            mode="multiple"
            allowClear
            placeholder="设备状态"
            className="device-state-filter"
            options={STATE_OPTIONS}
            onChange={(value) => {
              setPage(0)
              setStates(value.length ? value : undefined)
            }}
          />
          <Button
            icon={<ReloadOutlined />}
            loading={devices.isFetching}
            onClick={() => devices.refetch()}
          >
            刷新
          </Button>
        </Space>
      </div>

      <Card variant="borderless" className="data-card" styles={{ body: { padding: 0 } }}>
        {devices.isError ? (
          <Empty
            description={devices.error instanceof Error ? devices.error.message : '设备加载失败'}
          />
        ) : (
          <Table<DeviceRow>
            rowKey="id"
            size="middle"
            loading={devices.isLoading}
            columns={columns}
            dataSource={devices.data?.content ?? []}
            locale={{ emptyText: <Empty description="暂无充电桩设备" /> }}
            onRow={(row) => ({
              onClick: () => navigate(`/devices/${row.id}`),
              style: { cursor: 'pointer' }
            })}
            pagination={{
              current: (devices.data?.page ?? 0) + 1,
              pageSize: devices.data?.size ?? size,
              total: devices.data?.total ?? 0,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 台充电桩`,
              onChange: (nextPage, nextSize) => {
                setPage(nextPage - 1)
                setSize(nextSize)
              }
            }}
            scroll={{ x: 1080 }}
          />
        )}
      </Card>

      <Modal
        title="新建充电桩"
        width={820}
        className="device-create-modal"
        open={creating}
        confirmLoading={saving}
        okText="创建设备"
        onCancel={() => !saving && setCreating(false)}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form<DeviceFormValues>
          form={form}
          layout="vertical"
          className="device-create-form"
          initialValues={{ enabled: true, protocolMappings: emptyMappings() }}
          onFinish={async (values) => {
            setSaving(true)
            try {
              const body: DeviceSaveRequest = {
                sn: values.sn?.trim() || undefined,
                name: values.name.trim(),
                productId: values.productId,
                enabled: values.enabled,
                protocolMappings: values.protocolMappings.map((mapping, index) => ({
                  gunNo: gunNoFor(index),
                  thirdPartyDeviceId: mapping.thirdPartyDeviceId?.trim() ?? '',
                  protocolConnectorNo: mapping.protocolConnectorNo?.trim() || null,
                  enabled: true
                }))
              }
              await api.saveDevice(body)
              message.success('充电桩已创建')
              setCreating(false)
              form.resetFields()
              await devices.refetch()
            } catch (error) {
              message.error(error instanceof Error ? error.message : '创建设备失败')
            } finally {
              setSaving(false)
            }
          }}
        >
          <div className="device-create-grid">
            <Form.Item
              name="productId"
              label="充电桩产品"
              rules={[{ required: true, message: '请选择已发布产品' }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                placeholder={products.isError ? '产品加载失败' : '选择已发布产品'}
                loading={products.isLoading}
                options={(products.data?.content ?? [])
                  .filter((item) => item.state === 'PUBLISHED')
                  .map((item) => ({
                    value: item.id,
                    label: `${item.name} · ${productProtocol(item)}`,
                    product: item
                  }))}
                onChange={(_, option) => {
                  const product = Array.isArray(option) ? undefined : option?.product
                  const count = Math.max(1, product?.connectorCount ?? 1)
                  form.setFieldValue('protocolMappings', emptyMappings(count))
                }}
              />
            </Form.Item>
            <Form.Item
              name="sn"
              label="设备 SN（可选）"
              extra="留空由平台自动分配。填写时必须为 8 位纯数字，创建后不可修改。"
              rules={[
                {
                  pattern: /^\d{8}$/,
                  message: '设备 SN 必须为 8 位纯数字'
                }
              ]}
            >
              <Input
                maxLength={8}
                inputMode="numeric"
                placeholder="例如 10000000"
                className="mono"
              />
            </Form.Item>
            <Form.Item
              name="name"
              label="设备名称"
              rules={[{ required: true, whitespace: true, max: 128 }]}
            >
              <Input placeholder="例如 1 号充电桩" />
            </Form.Item>
            <Form.Item name="enabled" label="启用设备" valuePropName="checked">
              <Switch />
            </Form.Item>
          </div>

          <Form.List name="protocolMappings">
            {(fields, { add, remove }) => (
              <>
                <div className="mapping-editor-heading">
                  <div>
                    <strong>协议接入映射</strong>
                    <p>平台枪号为设备 SN 加两位枪号，第三方编号由接入协议决定。</p>
                  </div>
                  <Button
                    icon={<PlusOutlined />}
                    onClick={() =>
                      add({ thirdPartyDeviceId: '', protocolConnectorNo: '' })
                    }
                  >
                    添加枪口
                  </Button>
                </div>
                <div className="mapping-editor">
                  {fields.map((field, index) => {
                    const gunNo = gunNoFor(index)
                    const platformGunNo =
                      watchedSn && /^\d{8}$/.test(watchedSn)
                        ? `${watchedSn}${gunNo}`
                        : '创建后生成'
                    return (
                      <div className="mapping-row" key={field.key}>
                        <div className="mapping-identity">
                          <span className="mapping-gun">枪 {gunNo}</span>
                          <span className="mono">{platformGunNo}</span>
                        </div>
                        <Form.Item
                          {...field}
                          name={[field.name, 'thirdPartyDeviceId']}
                          label="第三方设备号"
                        >
                          <Input
                            prefix={<ApiOutlined />}
                            placeholder="云快充桩号 / OCPP chargePointId"
                          />
                        </Form.Item>
                        <Form.Item
                          {...field}
                          name={[field.name, 'protocolConnectorNo']}
                          label="第三方枪号"
                        >
                          <Input placeholder="例如 01" />
                        </Form.Item>
                        <Button
                          aria-label={`删除枪 ${gunNo} 映射`}
                          type="text"
                          danger
                          icon={<DeleteOutlined />}
                          disabled={fields.length === 1}
                          onClick={() => remove(field.name)}
                        />
                      </div>
                    )
                  })}
                </div>
              </>
            )}
          </Form.List>
          <Alert
            type="info"
            showIcon
            message="同一协议下，第三方设备号与第三方枪号组合必须唯一。暂时没有接入配置时可以先留空。"
          />
        </Form>
      </Modal>
    </>
  )
}
