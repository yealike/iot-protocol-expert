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
  Table,
  Tag,
  Typography
} from 'antd'
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import { productProtocol } from '../api/protocol'
import type {
  BillView,
  OrderFeeSegment,
  OrderView,
  TelemetryPoint
} from '../api/types'
import Chart from '../components/Chart'
import { formatDateTime } from '../utils/dateTime'

const metrics: { key: keyof TelemetryPoint; label: string; unit: string }[] = [
  { key: 'soc', label: 'SOC', unit: '%' },
  { key: 'voltage', label: '电压', unit: 'V' },
  { key: 'current', label: '电流', unit: 'A' },
  { key: 'power', label: '功率', unit: 'kW' },
  { key: 'gunTemperature', label: '枪线温度', unit: '°C' },
  { key: 'batteryTemperature', label: '电池温度', unit: '°C' }
]

const statusLabels: Record<string, string> = {
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

const feePeriods: Record<number, { label: string; color: string }> = {
  0: { label: '尖', color: '#e85555' },
  1: { label: '峰', color: '#e69a3a' },
  2: { label: '平', color: '#4778d9' },
  3: { label: '谷', color: '#2c9c7a' }
}

function amountSourceLabel(source: string | null | undefined): string {
  return (source ? amountSourceLabels[source] : undefined) ?? '来源未知'
}

function formatAmount(value: number | null): string {
  return value == null ? '—' : `¥ ${Number(value).toFixed(2)}`
}

function formatNumber(value: number | null, digits: number): string {
  return value == null ? '—' : Number(value).toFixed(digits)
}

function periodLabel(period: number): { label: string; color: string } {
  return feePeriods[period] ?? { label: `时段 ${period}`, color: 'default' }
}

function durationOf(order: OrderView): number | null {
  if (order.durationSeconds != null) return order.durationSeconds
  if (!order.startTime) return null
  const start = dayjs(order.startTime)
  const end = order.endTime
    ? dayjs(order.endTime)
    : order.status === 'CHARGING' || order.status === 'PENDING'
      ? dayjs()
      : null
  if (!start.isValid() || !end?.isValid()) return null
  return Math.max(0, end.diff(start, 'second'))
}

function formatDuration(seconds: number | null): string {
  if (seconds == null) return '—'
  return [Math.floor(seconds / 3600), Math.floor((seconds % 3600) / 60), seconds % 60]
    .map((value) => String(value).padStart(2, '0'))
    .join(':')
}

function Replay({ orderNo }: { orderNo: string }) {
  const data = useQuery({
    queryKey: ['order-telemetry', orderNo],
    queryFn: () => api.orderTelemetry(orderNo)
  })
  const [metric, setMetric] = useState<keyof TelemetryPoint>('soc')
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
        setCursor((current) => {
          if (current >= points.length) {
            setPlaying(false)
            return current
          }
          return Math.min(current + 1, points.length)
        }),
      1000 / speed
    )
    return () => window.clearInterval(timer)
  }, [playing, speed, points.length])

  const selected = metrics.find((item) => item.key === metric) ?? metrics[0]
  const visible = points.slice(0, cursor)
  const currentPoint = visible[visible.length - 1]
  const option = {
    grid: { left: 62, right: 30, top: 30, bottom: 48 },
    tooltip: {
      trigger: 'axis',
      formatter: (items: Array<{ value: [string, number | null] }>) => {
        const point = items[0]?.value
        return point
          ? `${formatDateTime(point[0])}<br/>${selected.label}: ${point[1] ?? '—'} ${selected.unit}`
          : ''
      }
    },
    xAxis: {
      type: 'time',
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#dfe6ef' } },
      axisLabel: {
        color: '#718096',
        hideOverlap: true,
        formatter: (value: number) => dayjs(value).format('HH:mm')
      }
    },
    yAxis: {
      type: 'value',
      name: selected.unit,
      scale: true,
      axisLabel: { color: '#718096' },
      splitLine: { lineStyle: { color: '#edf1f6' } }
    },
    series: [
      {
        type: 'line',
        name: selected.label,
        showSymbol: false,
        smooth: true,
        connectNulls: false,
        lineStyle: { color: '#3563e9', width: 2.5 },
        areaStyle: { color: 'rgba(53, 99, 233, 0.08)' },
        data: visible.map((point) => [point.time, point[metric]])
      }
    ]
  }

  return (
    <Card
      title="充电过程回放"
      variant="borderless"
      className="replay-card"
      extra={
        <Button
          icon={<ReloadOutlined />}
          loading={data.isFetching}
          onClick={() => data.refetch()}
        >
          刷新
        </Button>
      }
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
        <Empty description="暂无充电过程数据" />
      ) : (
        <>
          {data.data?.truncated ? (
            <Alert
              type="warning"
              showIcon
              message="数据超过 10000 条，目前仅展示最早的 10000 条"
            />
          ) : null}
          <div className="replay-toolbar">
            <Select
              value={metric}
              style={{ width: 170 }}
              options={metrics.map((item) => ({
                value: item.key,
                label: `${item.label}（${item.unit}）`
              }))}
              onChange={setMetric}
            />
            <Button
              type="primary"
              onClick={() => {
                if (cursor >= points.length) setCursor(0)
                setPlaying((value) => !value)
              }}
            >
              {playing ? '暂停回放' : '播放回放'}
            </Button>
            <Select
              value={speed}
              onChange={setSpeed}
              options={[1, 2, 4].map((value) => ({
                value,
                label: `${value} 点/秒`
              }))}
              style={{ width: 110 }}
            />
            <span className="replay-progress">
              {cursor} / {points.length}
            </span>
            {currentPoint ? (
              <span className="replay-current">
                当前 {selected.label}：
                <strong>
                  {currentPoint[metric] ?? '—'} {selected.unit}
                </strong>
              </span>
            ) : null}
          </div>
          <Chart option={option} height={320} />
          <Slider
            min={0}
            max={points.length}
            value={cursor}
            tooltip={{ formatter: (value) => `${value ?? 0} / ${points.length}` }}
            onChange={(value) => {
              setPlaying(false)
              setCursor(value)
            }}
          />
          <p className="replay-note">
            时间来源：
            {points.some((point) => point.timeSource === 'RECEIVED')
              ? '含平台接收时间'
              : '设备采样时间'}
            ，缺失值不补零。
          </p>
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
    enabled: Boolean(orderNo)
  })

  if (detail.isLoading) return <Skeleton active />
  if (detail.isError || !detail.data) {
    return (
      <Empty description={detail.error instanceof Error ? detail.error.message : '订单不存在'} />
    )
  }

  const { order, bills } = detail.data
  const feeSegments = detail.data.feeSegments ?? []
  const protocol = productProtocol({ protocol: order.protocol })

  return (
    <>
      <div className="page-header order-detail-header">
        <div>
          <Link to="/orders" className="back-link">
            <ArrowLeftOutlined /> 返回订单列表
          </Link>
          <h1>订单详情</h1>
          <Typography.Text
            className="mono order-detail-number"
            copyable={{ text: order.orderNo, tooltips: ['复制订单号', '已复制'] }}
          >
            {order.orderNo}
          </Typography.Text>
        </div>
        <Tag color={statusColors[order.status] ?? 'default'} bordered={false}>
          {statusLabels[order.status] ?? order.status}
        </Tag>
      </div>

      <div className="order-summary-grid">
        <div>
          <span>充电金额</span>
          <strong>{formatAmount(order.amount)}</strong>
          <small>{amountSourceLabel(order.amountSource)}</small>
        </div>
        <div>
          <span>充电电量</span>
          <strong>{formatNumber(order.energy, 3)}</strong>
          <small>kWh</small>
        </div>
        <div>
          <span>充电时长</span>
          <strong className="mono">{formatDuration(durationOf(order))}</strong>
          <small>{order.status === 'CHARGING' ? '仍在充电' : '设备采样时间'}</small>
        </div>
        <div>
          <span>设备 / 枪号</span>
          <strong className="mono">
            <Link to={`/devices/${order.deviceId}?gun=${encodeURIComponent(order.gunNo)}`}>
              {order.deviceSn ?? '—'}
            </Link>
          </strong>
          <small>{order.platformGunNo ?? `枪 ${order.gunNo}`}</small>
        </div>
      </div>

      <Card title="订单信息" variant="borderless" className="order-info-card">
        <Descriptions column={{ xs: 1, md: 2, xl: 3 }}>
          <Descriptions.Item label="协议">{protocol || '—'}</Descriptions.Item>
          <Descriptions.Item label="交易号">{order.transactionNo ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatDateTime(order.createdAt)}</Descriptions.Item>
          <Descriptions.Item label="开始时间">{formatDateTime(order.startTime)}</Descriptions.Item>
          <Descriptions.Item label="结束时间">{formatDateTime(order.endTime)}</Descriptions.Item>
          <Descriptions.Item label="枪口">枪 {order.gunNo || '—'}</Descriptions.Item>
          <Descriptions.Item label="失败原因">{order.failReason ?? '—'}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="费用构成" variant="borderless" className="order-info-card">
        <div className="order-fee-summary-grid">
          <div>
            <span>电费</span>
            <strong>{formatAmount(order.electricityFee)}</strong>
          </div>
          <div>
            <span>服务费</span>
            <strong>{formatAmount(order.serviceFee)}</strong>
          </div>
          <div>
            <span>计损电量</span>
            <strong>{formatNumber(order.lossEnergy, 3)}</strong>
            <small>kWh</small>
          </div>
          <div>
            <span>费率模型</span>
            <strong className="mono">{order.rateModelNo ?? '—'}</strong>
            <small>{amountSourceLabel(order.amountSource)}</small>
          </div>
        </div>
        <div className="order-fee-section-title">
          <div>
            <h3>分时费用明细</h3>
            <p>按尖峰平谷展示订单费率快照内的电量、单价与费用。</p>
          </div>
          <span>{feeSegments.length} 个时段</span>
        </div>
        <Table<OrderFeeSegment>
          rowKey="id"
          dataSource={feeSegments}
          pagination={false}
          scroll={{ x: 1040 }}
          locale={{ emptyText: <Empty description="暂无分时费用明细" /> }}
          columns={[
            {
              title: '时段',
              dataIndex: 'period',
              width: 90,
              render: (period: number) => {
                const meta = periodLabel(period)
                return (
                  <Tag bordered={false} color={meta.color}>
                    {meta.label}
                  </Tag>
                )
              }
            },
            {
              title: '用电量（kWh）',
              dataIndex: 'energy',
              width: 115,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number) => formatNumber(value, 3)
            },
            {
              title: '计损电量（kWh）',
              dataIndex: 'lossEnergy',
              width: 125,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number) => formatNumber(value, 3)
            },
            {
              title: '电费单价（¥/kWh）',
              dataIndex: 'electricityPrice',
              width: 130,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number) => formatNumber(value, 5)
            },
            {
              title: '服务费单价（¥/kWh）',
              dataIndex: 'servicePrice',
              width: 140,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number) => formatNumber(value, 5)
            },
            {
              title: '电费（¥）',
              dataIndex: 'electricityFee',
              width: 110,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number) => formatAmount(value)
            },
            {
              title: '服务费（¥）',
              dataIndex: 'serviceFee',
              width: 110,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number) => formatAmount(value)
            },
            {
              title: '小计（¥）',
              dataIndex: 'totalAmount',
              width: 110,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number) => formatAmount(value)
            },
            {
              title: '来源',
              dataIndex: 'source',
              width: 110,
              render: (value: string | null) => amountSourceLabel(value)
            }
          ]}
        />
      </Card>

      <Replay orderNo={order.orderNo} />

      <Card title="关联账单" variant="borderless" className="order-info-card">
        <Table<BillView>
          rowKey="id"
          dataSource={bills}
          pagination={false}
          scroll={{ x: 1400 }}
          locale={{ emptyText: <Empty description="暂无关联账单" /> }}
          columns={[
            { title: '来源', dataIndex: 'sourceType', width: 110 },
            {
              title: '来源交易号',
              dataIndex: 'sourceTransactionNo',
              width: 230,
              className: 'mono'
            },
            {
              title: '电量（kWh）',
              dataIndex: 'energy',
              width: 110,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number | null) => formatNumber(value, 3)
            },
            {
              title: '电费（¥）',
              dataIndex: 'electricityFee',
              width: 110,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number | null) => formatAmount(value)
            },
            {
              title: '服务费（¥）',
              dataIndex: 'serviceFee',
              width: 110,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number | null) => formatAmount(value)
            },
            {
              title: '金额（¥）',
              dataIndex: 'amount',
              width: 110,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number | null) => formatAmount(value)
            },
            {
              title: '计损电量（kWh）',
              dataIndex: 'lossEnergy',
              width: 130,
              align: 'right',
              className: 'numeric-cell',
              render: (value: number | null) => formatNumber(value, 3)
            },
            {
              title: '费率模型',
              dataIndex: 'rateModelNo',
              width: 110,
              className: 'mono',
              render: (value: string | null) => value || '—'
            },
            { title: '币种', dataIndex: 'currency', width: 80 },
            {
              title: '校验',
              width: 120,
              render: (_, row) => (
                <span className={row.accepted ? 'status-success' : 'status-danger'}>
                  {row.accepted ? '通过' : row.rejectReason || '未通过'}
                </span>
              )
            },
            {
              title: '接收时间',
              dataIndex: 'receivedAt',
              width: 170,
              render: (value: string | null) => formatDateTime(value)
            }
          ]}
        />
      </Card>
    </>
  )
}
