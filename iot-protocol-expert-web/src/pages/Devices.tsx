import { formatDateTime } from '../utils/dateTime'
import { productProtocol } from '../api/protocol'
import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
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
  message
} from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { DeviceStateTag } from '../components/tags'
import type { DeviceRow, DeviceState, DeviceTypeCode } from '../api/types'

const STATE_OPTIONS: { label: string; value: DeviceState }[] = [
  { label: '在线', value: 'ONLINE' },
  { label: '离线', value: 'OFFLINE' },
  { label: '故障', value: 'FAULT' },
  { label: '未激活', value: 'NOT_ACTIVE' }
]

export default function Devices() {
  const navigate = useNavigate()
  const [keyword, setKeyword] = useState('')
  const [deviceType, setDeviceType] = useState<DeviceTypeCode | undefined>()
  const [states, setStates] = useState<DeviceState[] | undefined>()
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [creating, setCreating] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()
  const [messages, messageHolder] = message.useMessage()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })

  const products = useQuery({
    queryKey: ['device-create-products'],
    queryFn: () => api.queryProducts({ size: 100 }),
    enabled: creating
  })

  const types = useQuery({ queryKey: ['device-types'], queryFn: api.deviceTypes })
  const devices = useQuery({
    queryKey: ['devices', keyword, deviceType, states, page, size],
    queryFn: () =>
      api.queryDevices({
        keyword: keyword || undefined,
        deviceType,
        states: states?.length ? states : undefined,
        page,
        size,
        sort: '-lastSeen'
      })
  })

  const columns = useMemo(
    () => [
      {
        title: '设备',
        dataIndex: 'name',
        render: (_: unknown, row: DeviceRow) => (
          <Space direction="vertical" size={0}>
            <Link to={`/devices/${row.id}`} style={{ fontWeight: 600 }}>
              {row.name}
            </Link>
            <span className="mono" style={{ fontSize: 11, color: '#6d839b' }}>
              {row.deviceCode} · {row.protocolDeviceId}
            </span>
          </Space>
        )
      },
      {
        title: '类型',
        dataIndex: 'deviceTypeLabel',
        width: 130,
        render: (label: string) => (
          <Tag bordered={false} color="cyan">
            {label}
          </Tag>
        )
      },
      {
        title: '协议',
        dataIndex: 'protocol',
        width: 110,
        render: (_: string, row: DeviceRow) => (
          <span className="mono" style={{ fontSize: 12 }}>
            {productProtocol(row)}
          </span>
        )
      },
      {
        title: '状态',
        dataIndex: 'state',
        width: 100,
        render: (state: DeviceState) => <DeviceStateTag state={state} />
      },
      {
        title: '关键指标',
        dataIndex: 'highlights',
        render: (highlights: Record<string, string>) => (
          <div className="chip-row" style={{ marginTop: 0 }}>
            {Object.entries(highlights ?? {})
              .slice(0, 4)
              .map(([key, value]) => (
                <span className="chip" key={key}>
                  {value}
                </span>
              ))}
          </div>
        )
      },
      { title: '产品', dataIndex: 'productName', width: 160 },
      { title: '站点', dataIndex: 'stationName', width: 160 },
      { title: '最近上报', dataIndex: 'lastSeen', width: 170, render: (value: string | null) => formatDateTime(value) }
    ],
    []
  )

  return (
    <>
      {messageHolder}
      <div className="page-header">
        <div>
          <h1>设备</h1>
          <p>充电桩设备台账</p>
        </div>
        <Space>
          {me.data?.permissions?.includes('ops:manage') && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
              创建设备
            </Button>
          )}
          <Input.Search
            allowClear
            placeholder="设备编码 / 名称 / 协议编号"
            style={{ width: 240 }}
            onSearch={(value) => {
              setPage(0)
              setKeyword(value)
            }}
          />
          <Select
            allowClear
            placeholder="设备类型"
            style={{ width: 150 }}
            options={(types.data ?? []).map((type) => ({
              label: `${type.label}（${type.total ?? 0}）`,
              value: type.deviceType
            }))}
            onChange={(value) => {
              setPage(0)
              setDeviceType(value)
            }}
          />
          <Select
            mode="multiple"
            allowClear
            placeholder="状态"
            style={{ minWidth: 150 }}
            options={STATE_OPTIONS}
            onChange={(value) => {
              setPage(0)
              setStates(value.length ? value : undefined)
            }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => devices.refetch()} />
        </Space>
      </div>

      <Card bordered={false} styles={{ body: { padding: 0 } }}>
        {devices.isError ? (
          <Empty
            description={devices.error instanceof Error ? devices.error.message : '加载失败'}
          />
        ) : (
          <Table
            rowKey="id"
            size="middle"
            loading={devices.isLoading}
            columns={columns}
            dataSource={devices.data?.content ?? []}
            onRow={(row) => ({
              onClick: () => navigate(`/devices/${row.id}`),
              style: { cursor: 'pointer' }
            })}
            pagination={{
              current: (devices.data?.page ?? 0) + 1,
              pageSize: devices.data?.size ?? size,
              total: devices.data?.total ?? 0,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 台设备`,
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
        title="创建设备档案"
        open={creating}
        confirmLoading={saving}
        onCancel={() => !saving && setCreating(false)}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ enabled: true, allowSimulate: false, allowWork: false }}
          onFinish={async (values) => {
            setSaving(true)
            try {
              await api.saveDevice(values)
              messages.success('设备档案已创建')
              setCreating(false)
              form.resetFields()
              await devices.refetch()
            } catch (error) {
              messages.error(error instanceof Error ? error.message : '创建设备失败')
            } finally {
              setSaving(false)
            }
          }}
        >
          <Form.Item
            name="productId"
            label="产品"
            rules={[{ required: true, message: '请选择已发布产品' }]}
          >
            <Select
              showSearch
              optionFilterProp="label"
              placeholder={products.isError ? '产品加载失败' : '选择已发布产品'}
              loading={products.isLoading}
              options={(products.data?.content ?? [])
                .filter((item) => item.state === 'PUBLISHED')
                .map((item) => ({ value: item.id, label: `${item.name} · ${productProtocol(item)}` }))}
            />
          </Form.Item>
          <Form.Item
            name="deviceCode"
            label="设备编码"
            rules={[{ required: true, whitespace: true, max: 64 }]}
          >
            <Input placeholder="平台唯一编码；模拟设备建议以 SIM- 开头" />
          </Form.Item>
          <Form.Item
            name="name"
            label="设备名称"
            rules={[{ required: true, whitespace: true, max: 128 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="protocolDeviceId"
            label="协议设备编号"
            rules={[{ required: true, whitespace: true, max: 64 }]}
          >
            <Input placeholder="须与网关配置中的协议设备编号一致" />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="allowSimulate" label="允许模拟数据" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="allowWork" label="允许远程操作" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
