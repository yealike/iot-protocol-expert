import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, Input, Select, Space, Table, Tag, Tooltip, Typography } from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type { OrderView } from '../api/types'
import { formatDateTime } from '../utils/dateTime'

const labels: Record<string, string> = {
  PENDING: '待启动',
  CHARGING: '充电中',
  STOPPED: '已停止',
  SETTLED: '已结算',
  FAILED: '失败'
}

const statusColors: Record<string, string> = {
  PENDING: 'gold',
  CHARGING: 'processing',
  STOPPED: 'default',
  SETTLED: 'green',
  FAILED: 'red'
}

const amountSourceLabels: Record<string, string> = {
  DEVICE_BILL: '设备账单',
  PLATFORM_ESTIMATE: '平台估算'
}

function durationSeconds(order: OrderView): number | null {
  if (order.durationSeconds != null) return order.durationSeconds
  if (!order.startTime) return null
  const start = dayjs(order.startTime)
  if (!start.isValid()) return null
  const end = order.endTime
    ? dayjs(order.endTime)
    : order.status === 'CHARGING' || order.status === 'PENDING'
      ? dayjs()
      : null
  if (!end?.isValid()) return null
  return Math.max(0, end.diff(start, 'second'))
}

function formatDuration(seconds: number | null): string {
  if (seconds == null) return '—'
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const remainder = seconds % 60
  return [hours, minutes, remainder]
    .map((value) => String(value).padStart(2, '0'))
    .join(':')
}

function formatEnergy(value: number | null): string {
  return value == null ? '—' : Number(value).toFixed(3)
}

function formatAmount(value: number | null): string {
  return value == null ? '—' : Number(value).toFixed(2)
}

export default function Orders() {
  const [params] = useSearchParams()
  const [orderNo, setOrderNo] = useState('')
  const [status, setStatus] = useState<string>()
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const deviceId = params.get('deviceId') ?? undefined
  const orders = useQuery({
    queryKey: ['orders', orderNo, status, page, size, deviceId],
    queryFn: () => api.queryOrders({ orderNo, status, page, size, deviceId }),
    refetchInterval: (query) =>
      query.state.data?.content.some(
        (order) => order.status === 'CHARGING' || order.status === 'PENDING'
      )
        ? 15_000
        : false
  })

  return (
    <div className="orders-page">
      <div className="page-header">
        <div>
          <h1>充电订单</h1>
          <p>优先查看金额、电量和充电时长，追踪每一笔充电交易。</p>
        </div>
        <Space wrap>
          {deviceId ? (
            <Tag bordered={false} color="blue">
              已按设备筛选
            </Tag>
          ) : null}
          <Button
            icon={<ReloadOutlined />}
            loading={orders.isFetching}
            onClick={() => orders.refetch()}
          >
            刷新订单
          </Button>
        </Space>
      </div>

      <Card variant="borderless" className="order-filter-card">
        <div className="order-filters">
          <div className="order-filter-field">
            <label htmlFor="order-search">订单号</label>
            <Input.Search
              id="order-search"
              placeholder="输入完整或部分订单号"
              prefix={<SearchOutlined />}
              allowClear
              onSearch={(value) => {
                setOrderNo(value.trim())
                setPage(0)
              }}
            />
          </div>
          <div className="order-filter-field status-filter">
            <label htmlFor="order-status">订单状态</label>
            <Select
              id="order-status"
              placeholder="全部状态"
              allowClear
              value={status}
              onChange={(value) => {
                setStatus(value)
                setPage(0)
              }}
              options={Object.entries(labels).map(([value, label]) => ({ value, label }))}
            />
          </div>
          {deviceId ? (
            <div className="device-filter-chip">
              <span>仅显示当前设备订单</span>
              <Link to="/orders">清除筛选</Link>
            </div>
          ) : null}
        </div>
      </Card>

      <Card variant="borderless" className="order-table-card" styles={{ body: { padding: 0 } }}>
        <div className="order-table-heading">
          <div>
            <h2>订单明细</h2>
            <p>订单金额保留来源标识，便于核对设备账单与平台估算。</p>
          </div>
          <span>共 {orders.data?.total ?? 0} 笔</span>
        </div>
        {orders.isError ? (
          <p role="alert" className="order-error">
            {orders.error instanceof Error ? orders.error.message : '订单加载失败'}
          </p>
        ) : null}
        <Table<OrderView>
          rowKey="id"
          loading={orders.isLoading}
          dataSource={orders.data?.content ?? []}
          scroll={{ x: 1110 }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: orders.data?.total ?? 0,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (total) => `共 ${total} 笔订单`,
            onChange: (nextPage, nextSize) => {
              setPage(nextPage - 1)
              setSize(nextSize)
            }
          }}
          columns={[
            {
              title: '订单号',
              dataIndex: 'orderNo',
              width: 290,
              fixed: 'left',
              render: (value: string) => (
                <Typography.Text
                  className="copyable-id order-number"
                  copyable={{ text: value, tooltips: ['复制订单号', '已复制'] }}
                >
                  <Link to={`/orders/${encodeURIComponent(value)}`}>{value}</Link>
                </Typography.Text>
              )
            },
            {
              title: '充电金额',
              dataIndex: 'amount',
              width: 125,
              align: 'right',
              render: (value: number | null, row) => (
                <div className="order-amount">
                  <strong>¥ {formatAmount(value)}</strong>
                  <small>
                    {amountSourceLabels[row.amountSource] ?? row.amountSource ?? '来源未知'}
                  </small>
                </div>
              )
            },
            {
              title: '充电电量',
              dataIndex: 'energy',
              width: 125,
              align: 'right',
              render: (value: number | null) => (
                <span className="numeric-cell">
                  {formatEnergy(value)}
                  {value == null ? null : <small> kWh</small>}
                </span>
              )
            },
            {
              title: '充电时长',
              width: 115,
              align: 'right',
              render: (_, row) => (
                <span className="numeric-cell mono">
                  {formatDuration(durationSeconds(row))}
                </span>
              )
            },
            {
              title: '订单状态',
              dataIndex: 'status',
              width: 105,
              render: (value: string) => (
                <Tag color={statusColors[value] ?? 'default'} bordered={false}>
                  {labels[value] ?? value}
                </Tag>
              )
            },
            {
              title: '设备 SN / 枪号',
              width: 190,
              render: (_, row) => (
                <div className="order-device-cell">
                  <Link
                    className="mono"
                    to={`/devices/${row.deviceId}?gun=${encodeURIComponent(row.gunNo)}`}
                  >
                    {row.deviceSn ?? '—'}
                  </Link>
                  <Tooltip title={row.platformGunNo ?? '平台枪号待生成'}>
                    <span>枪 {row.gunNo || '—'}</span>
                  </Tooltip>
                </div>
              )
            },
            {
              title: '开始时间',
              dataIndex: 'startTime',
              width: 160,
              render: (value: string | null) => (
                <span className="table-time">{formatDateTime(value)}</span>
              )
            }
          ]}
        />
      </Card>
    </div>
  )
}
