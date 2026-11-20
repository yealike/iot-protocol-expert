import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, Input, Select, Table, Tag } from 'antd'
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons'
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

export default function Orders() {
  const [params] = useSearchParams()
  const [orderNo, setOrderNo] = useState('')
  const [status, setStatus] = useState<string>()
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const deviceId = params.get('deviceId') ?? undefined
  const orders = useQuery({
    queryKey: ['orders', orderNo, status, page, size, deviceId],
    queryFn: () => api.queryOrders({ orderNo, status, page, size, deviceId })
  })

  return (
    <div className="orders-page">
      <div className="page-header">
        <div>
          <h1>订单管理</h1>
          <p>查询充电订单、设备枪口与账单信息，跟踪订单全生命周期。</p>
        </div>
        <Button icon={<ReloadOutlined />} loading={orders.isFetching} onClick={() => orders.refetch()}>
          刷新订单
        </Button>
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
              <span>设备筛选中</span>
              <Link to="/orders">清除筛选</Link>
            </div>
          ) : null}
        </div>
      </Card>

      <Card variant="borderless" className="order-table-card" styles={{ body: { padding: 0 } }}>
        <div className="order-table-heading">
          <div><h2>充电订单</h2><p>订单金额保留来源标识，便于核对设备账单与平台估算</p></div>
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
          scroll={{ x: 1320 }}
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
              width: 230,
              fixed: 'left',
              render: (value: string) => (
                <Link className="order-number mono" to={`/orders/${encodeURIComponent(value)}`}>
                  {value}
                </Link>
              )
            },
            {
              title: '桩号',
              dataIndex: 'protocolDeviceId',
              width: 170,
              render: (value: string, row) => (
                <Link className="pile-number mono" to={`/devices/${row.deviceId}`}>
                  {value || '—'}
                </Link>
              )
            },
            {
              title: '枪号',
              dataIndex: 'gunNo',
              width: 100,
              render: (value: string) => <span className="gun-number">{value || '—'}</span>
            },
            {
              title: '订单状态',
              dataIndex: 'status',
              width: 120,
              render: (value: string) => (
                <Tag color={statusColors[value] ?? 'default'} bordered={false}>
                  {labels[value] ?? value}
                </Tag>
              )
            },
            {
              title: '开始时间',
              dataIndex: 'startTime',
              width: 170,
              render: (value: string | null) => formatDateTime(value)
            },
            {
              title: '结束时间',
              dataIndex: 'endTime',
              width: 170,
              render: (value: string | null) => formatDateTime(value)
            },
            {
              title: '充电量（kWh）',
              dataIndex: 'energy',
              width: 140,
              align: 'right',
              render: (value: number | null) => <span className="energy-value">{value ?? '—'}</span>
            },
            {
              title: '订单金额',
              width: 190,
              align: 'right',
              render: (_, row) =>
                row.amount == null ? (
                  '—'
                ) : (
                  <div className="order-amount">
                    <strong>¥ {row.amount}</strong>
                    <small>
                      {row.amountSource === 'DEVICE_BILL'
                        ? '设备账单'
                        : row.amountSource === 'PLATFORM_ESTIMATE'
                          ? '平台估算'
                          : '来源未知'}
                    </small>
                  </div>
                )
            }
          ]}
        />
      </Card>
    </div>
  )
}
