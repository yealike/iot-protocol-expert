import { useQuery } from '@tanstack/react-query'
import { Card, Col, Empty, Row, Skeleton, Table, Tag } from 'antd'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { AlarmLevelTag, AlarmStateTag, MetricCard } from '../components/tags'
import Chart from '../components/Chart'
import type { AlarmView } from '../api/types'

export default function Overview() {
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: api.overview,
    refetchInterval: 15_000
  })

  if (overview.isLoading) {
    return <Skeleton active paragraph={{ rows: 8 }} />
  }
  if (overview.isError || !overview.data) {
    return (
      <Empty
        description={overview.error instanceof Error ? overview.error.message : '总览加载失败'}
      />
    )
  }
  const data = overview.data

  const chartOption = {
    grid: { left: 90, right: 24, top: 20, bottom: 28 },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(38,70,102,0.5)' } } },
    yAxis: {
      type: 'category',
      data: data.byType.map((item) => item.label),
      axisLine: { lineStyle: { color: '#2a4260' } }
    },
    series: [
      {
        type: 'bar',
        stack: 'device',
        name: '在线',
        data: data.byType.map((item) => item.online),
        itemStyle: { color: '#38d39f' },
        barWidth: 16
      },
      {
        type: 'bar',
        stack: 'device',
        name: '离线',
        data: data.byType.map((item) => item.offline),
        itemStyle: { color: '#3c5670' }
      },
      {
        type: 'bar',
        stack: 'device',
        name: '故障',
        data: data.byType.map((item) => item.fault),
        itemStyle: { color: '#ff5d5d' }
      }
    ]
  }

  const alarmColumns = [
    {
      title: '级别',
      dataIndex: 'level',
      width: 90,
      render: (level: AlarmView['level']) => <AlarmLevelTag level={level} />
    },
    { title: '告警', dataIndex: 'title' },
    { title: '设备', dataIndex: 'deviceName', width: 200 },
    {
      title: '状态',
      dataIndex: 'state',
      width: 100,
      render: (state: AlarmView['state']) => <AlarmStateTag state={state} />
    },
    { title: '最近发生', dataIndex: 'lastAt', width: 170 }
  ]

  return (
    <>
      <div className="page-header">
        <div>
          <h1>园区总览</h1>
          <p>光储充放全类型设备运行态势 · 每 15 秒刷新</p>
        </div>
        <Tag color="green" bordered={false}>
          设备 {data.deviceTotal} 台 / 产品 {data.productCount} 个
        </Tag>
      </div>

      <div className="metric-grid">
        <MetricCard label="设备总数" value={data.deviceTotal} suffix="台" />
        <MetricCard label="在线设备" value={data.deviceOnline} suffix="台" />
        <MetricCard
          label="故障设备"
          value={data.deviceFault}
          suffix="台"
          tone={data.deviceFault > 0 ? 'danger' : undefined}
        />
        <MetricCard
          label="未关闭告警"
          value={data.alarmOpen}
          suffix="条"
          tone={data.alarmOpen > 0 ? 'warn' : undefined}
        />
        <MetricCard label="站点" value={data.stationCount} suffix="个" />
      </div>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        <Col xs={24} xl={12}>
          <Card title="设备类型分布" size="small" bordered={false}>
            <Chart option={chartOption} height={300} />
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card title="最新告警" size="small" bordered={false}>
            <Table
              size="small"
              rowKey="id"
              pagination={false}
              columns={alarmColumns}
              dataSource={data.latestAlarms}
              locale={{ emptyText: '暂无未关闭告警' }}
              scroll={{ x: 620 }}
            />
            <div style={{ marginTop: 12, textAlign: 'right' }}>
              <Link to="/alarms">查看全部告警 →</Link>
            </div>
          </Card>
        </Col>
      </Row>
    </>
  )
}
