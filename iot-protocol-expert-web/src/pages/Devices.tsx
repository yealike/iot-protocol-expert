import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, Empty, Input, Select, Space, Table, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
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
        render: (value: string) => (
          <span className="mono" style={{ fontSize: 12 }}>
            {value}
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
      { title: '站点', dataIndex: 'stationName', width: 160 },
      { title: '最近上报', dataIndex: 'lastSeen', width: 170 }
    ],
    []
  )

  return (
    <>
      <div className="page-header">
        <div>
          <h1>设备</h1>
          <p>充电桩 / 光伏 / 风机 / 储能 / 地锁 / 门禁 / 摄像头 / 电表统一台账</p>
        </div>
        <Space>
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
    </>
  )
}
