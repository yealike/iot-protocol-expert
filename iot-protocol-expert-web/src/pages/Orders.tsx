import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Button, Card, Input, Select, Space, Table, Tag } from 'antd'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import type { OrderView } from '../api/types'

const labels: Record<string, string> = {
  PENDING: '待启动',
  CHARGING: '充电中',
  STOPPED: '已停止',
  SETTLED: '已结算',
  FAILED: '失败'
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
    <>
      <div className="page-header">
        <div>
          <h1>订单管理</h1>
          <p>查看充电订单及账单，金额标注数据来源</p>
        </div>
      </div>
      <Card bordered={false}>
        <Space wrap style={{ marginBottom: 16 }}>
          <Input.Search
            placeholder="订单号"
            allowClear
            onSearch={(v) => {
              setOrderNo(v.trim())
              setPage(0)
            }}
            style={{ width: 240 }}
          />
          <Select
            placeholder="订单状态"
            allowClear
            value={status}
            onChange={(v) => {
              setStatus(v)
              setPage(0)
            }}
            style={{ width: 150 }}
            options={Object.entries(labels).map(([value, label]) => ({ value, label }))}
          />
          {deviceId && <Link to="/orders">清除设备筛选</Link>}
          <Button onClick={() => orders.refetch()}>刷新</Button>
        </Space>
        {orders.isError && (
          <p role="alert">
            {orders.error instanceof Error ? orders.error.message : '订单加载失败'}
          </p>
        )}
        <Table<OrderView>
          rowKey="id"
          loading={orders.isLoading}
          dataSource={orders.data?.content ?? []}
          scroll={{ x: 1050 }}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: orders.data?.total ?? 0,
            showSizeChanger: true,
            onChange: (p, s) => {
              setPage(p - 1)
              setSize(s)
            }
          }}
          columns={[
            {
              title: '订单号',
              dataIndex: 'orderNo',
              render: (v: string) => <Link to={`/orders/${encodeURIComponent(v)}`}>{v}</Link>
            },
            {
              title: '设备 / 枪口',
              render: (_, r) => (
                <Link to={`/devices/${r.deviceId}`}>
                  {r.protocolDeviceId} / {r.gunNo}
                </Link>
              )
            },
            {
              title: '状态',
              dataIndex: 'status',
              render: (v: string) => (
                <Tag color={v === 'FAILED' ? 'red' : v === 'CHARGING' ? 'green' : 'default'}>
                  {labels[v] ?? v}
                </Tag>
              )
            },
            { title: '开始时间', dataIndex: 'startTime', render: (v) => v ?? '—' },
            { title: '结束时间', dataIndex: 'endTime', render: (v) => v ?? '—' },
            { title: '电量 (kWh)', dataIndex: 'energy', render: (v) => v ?? '—' },
            {
              title: '金额',
              render: (_, r) =>
                r.amount == null
                  ? '—'
                  : `${r.amount} (${r.amountSource === 'DEVICE_BILL' ? '设备账单' : r.amountSource === 'PLATFORM_ESTIMATE' ? '平台估算' : '来源未知'})`
            }
          ]}
        />
      </Card>
    </>
  )
}
