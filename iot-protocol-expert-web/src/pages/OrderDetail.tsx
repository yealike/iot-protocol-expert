import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Select,
  Skeleton,
  Slider,
  Space,
  Table,
  Tag
} from 'antd'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { BillView, TelemetryPoint } from '../api/types'
import Chart from '../components/Chart'

const metrics: { key: keyof TelemetryPoint; label: string; unit: string }[] = [
  { key: 'voltage', label: '电压', unit: 'V' },
  { key: 'current', label: '电流', unit: 'A' },
  { key: 'power', label: '功率', unit: 'kW' },
  { key: 'gunTemperature', label: '枪线 / 设备温度', unit: '°C' },
  { key: 'batteryTemperature', label: '电池温度', unit: '°C' },
  { key: 'soc', label: 'SOC', unit: '%' }
]

function Replay({ orderNo }: { orderNo: string }) {
  const data = useQuery({
    queryKey: ['order-telemetry', orderNo],
    queryFn: () => api.orderTelemetry(orderNo)
  })
  const [metric, setMetric] = useState<keyof TelemetryPoint>('voltage')
  const [cursor, setCursor] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(1)
  const points = data.data?.points ?? []
  useEffect(() => {
    if (data.data) {
      setCursor(data.data.points.length)
      setPlaying(false)
    }
  }, [data.data])
  useEffect(() => {
    if (!playing) return
    const timer = window.setInterval(
      () =>
        setCursor((c) => {
          if (c >= points.length) {
            setPlaying(false)
            return c
          }
          return Math.min(c + 1, points.length)
        }),
      1000 / speed
    )
    return () => window.clearInterval(timer)
  }, [playing, speed, points.length])
  const selected = metrics.find((m) => m.key === metric)!
  const visible = points.slice(0, cursor)
  const option = {
    grid: { left: 60, right: 30, top: 28, bottom: 45 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'time' },
    yAxis: { type: 'value', name: selected.unit, scale: true },
    series: [
      {
        type: 'line',
        name: selected.label,
        showSymbol: false,
        connectNulls: false,
        data: visible.map((p) => [p.time, p[metric]])
      }
    ]
  }
  return (
    <Card
      title="充电过程回放"
      bordered={false}
      style={{ marginTop: 16 }}
      extra={<Button onClick={() => data.refetch()}>刷新</Button>}
    >
      {data.isError ? (
        <Alert
          type="error"
          showIcon
          message={data.error instanceof Error ? data.error.message : '时序数据读取失败'}
        />
      ) : data.isLoading ? (
        <Skeleton active />
      ) : points.length === 0 ? (
        <Empty description="暂无过程数据" />
      ) : (
        <>
          {data.data?.truncated && (
            <Alert type="warning" showIcon message="数据超过 10000 条，目前仅展示最早的 10000 条" />
          )}
          <p className="muted">
            时间来源：
            {points.some((p) => p.timeSource === 'RECEIVED') ? '含平台接收时间' : '设备采样时间'}
            ；缺失值不补零
          </p>
          <Space wrap>
            <Select
              value={metric}
              style={{ width: 180 }}
              options={metrics.map((m) => ({ value: m.key, label: `${m.label} (${m.unit})` }))}
              onChange={setMetric}
            />
            <Button
              onClick={() => {
                if (cursor >= points.length) setCursor(0)
                setPlaying(!playing)
              }}
            >
              {playing ? '暂停' : '播放'}
            </Button>
            <Select
              value={speed}
              onChange={setSpeed}
              options={[1, 2, 4].map((v) => ({ value: v, label: `${v} 点/秒` }))}
              style={{ width: 110 }}
            />
            <span>
              {cursor} / {points.length}
            </span>
          </Space>
          <Chart option={option} height={300} />
          <Slider
            min={0}
            max={points.length}
            value={cursor}
            onChange={(v) => {
              setPlaying(false)
              setCursor(v)
            }}
          />
        </>
      )}
    </Card>
  )
}

export default function OrderDetail() {
  const { orderNo = '' } = useParams()
  const detail = useQuery({
    queryKey: ['order', orderNo],
    queryFn: () => api.order(orderNo),
    enabled: !!orderNo
  })
  if (detail.isLoading) return <Skeleton active />
  if (detail.isError || !detail.data)
    return (
      <Empty description={detail.error instanceof Error ? detail.error.message : '订单不存在'} />
    )
  const { order, bills } = detail.data
  return (
    <>
      <div className="page-header">
        <div>
          <Link to="/orders">← 返回订单列表</Link>
          <h1>订单详情</h1>
          <p className="mono">{order.orderNo}</p>
        </div>
        <Tag>{order.status}</Tag>
      </div>
      <Card title="订单信息" bordered={false}>
        <Descriptions column={{ xs: 1, md: 2, xl: 3 }}>
          <Descriptions.Item label="设备">
            <Link to={`/devices/${order.deviceId}`}>{order.protocolDeviceId}</Link>
          </Descriptions.Item>
          <Descriptions.Item label="枪口">{order.gunNo}</Descriptions.Item>
          <Descriptions.Item label="协议">{order.protocol}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{order.createdAt ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="开始时间">{order.startTime ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="结束时间">{order.endTime ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="电量">
            {order.energy == null ? '—' : `${order.energy} kWh`}
          </Descriptions.Item>
          <Descriptions.Item label="金额">{order.amount ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="金额来源">
            {order.amountSource === 'DEVICE_BILL'
              ? '设备账单'
              : order.amountSource === 'PLATFORM_ESTIMATE'
                ? '平台估算'
                : '来源未知'}
          </Descriptions.Item>
          <Descriptions.Item label="失败原因">{order.failReason ?? '—'}</Descriptions.Item>
        </Descriptions>
      </Card>
      <Replay orderNo={order.orderNo} />
      <Card title="关联账单" bordered={false} style={{ marginTop: 16 }}>
        <Table<BillView>
          rowKey="id"
          dataSource={bills}
          pagination={false}
          scroll={{ x: 800 }}
          columns={[
            { title: '来源', dataIndex: 'sourceType' },
            { title: '来源交易号', dataIndex: 'sourceTransactionNo' },
            { title: '电量 (kWh)', dataIndex: 'energy', render: (v) => v ?? '—' },
            { title: '金额', dataIndex: 'amount', render: (v) => v ?? '—' },
            { title: '币种', dataIndex: 'currency' },
            { title: '校验', render: (_, r) => (r.accepted ? '通过' : r.rejectReason || '未通过') },
            { title: '接收时间', dataIndex: 'receivedAt', render: (v) => v ?? '—' }
          ]}
        />
      </Card>
    </>
  )
}
