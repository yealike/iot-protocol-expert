import { useQuery } from '@tanstack/react-query'
import { Card, Col, Empty, Progress, Row, Skeleton, Table, Tag } from 'antd'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { AlarmLevelTag, AlarmStateTag, MetricCard } from '../components/tags'
import type { AlarmView } from '../api/types'
import Chart from '../components/Chart'

export default function Overview() {
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: api.overview,
    refetchInterval: 15000
  })
  const business = useQuery({
    queryKey: ['overview-business'],
    queryFn: api.businessOverview,
    refetchInterval: 30000
  })
  if (overview.isLoading) return <Skeleton active paragraph={{ rows: 8 }} />
  if (overview.isError || !overview.data)
    return (
      <Empty
        description={overview.error instanceof Error ? overview.error.message : '总览加载失败'}
      />
    )
  const d = overview.data
  const b = business.data
  const normal = Math.max(0, d.deviceOnline - d.deviceFault)
  return (
    <>
      <div className="overview-hero">
        <div>
          <div className="overview-eyebrow">OPERATIONS CENTER / 运营中心</div>
          <h1>充电运营总览</h1>
          <p>掌握设备运行状态与充电业务动态</p>
        </div>
        <div className="overview-live">
          <span /> 数据每 15 秒刷新 <small>{b?.date ?? '—'} · 北京时间</small>
        </div>
      </div>
      <div className="overview-section-title">
        <div>
          <h2>今日业务</h2>
          <span>按北京时间统计，数据以平台记录为准</span>
        </div>
        <Link to="/orders">查看订单 →</Link>
      </div>
      <div className="metric-grid">
        <MetricCard label="今日充电量" value="—" suffix="kWh" />
        <MetricCard label="今日放电量" value="—" suffix="kWh" />
        <MetricCard
          label="今日结算订单充电量"
          value={b ? Number(b.settledEnergy).toFixed(2) : '—'}
          suffix="kWh"
        />
        <MetricCard
          label="今日已结算金额（含估算）"
          value={b ? Number(b.settledAmount).toFixed(2) : '—'}
          suffix="元"
        />
        <MetricCard label="今日结算订单" value={b?.settledOrderCount ?? '—'} suffix="笔" />
        <MetricCard label="进行中订单" value={b?.activeOrderCount ?? '—'} suffix="笔" />
      </div>
      <p className="overview-note">
        今日结算订单充电量按订单结束时间汇总，跨日订单的全部电量计入结束日；今日实际充放电量尚无可按自然日切分的计量数据。已结算金额可能包含平台估算，不代表实收。
        {business.isError ? ' 业务统计加载失败。' : ''}
      </p>
      <Card
        title="近 7 日结算趋势"
        bordered={false}
        className="overview-panel"
        style={{ marginTop: 20 }}
        extra={<span className="muted">按订单结束日统计 · 金额含估算</span>}
      >
        {b ? (
          <Chart
            height={230}
            option={{
              grid: { left: 55, right: 55, top: 35, bottom: 32 },
              tooltip: { trigger: 'axis' },
              legend: { data: ['结算电量', '结算金额'], textStyle: { color: '#718096' } },
              xAxis: { type: 'category', data: b.daily.map((x) => x.date.slice(5)) },
              yAxis: [
                { type: 'value', name: 'kWh' },
                { type: 'value', name: '元' }
              ],
              series: [
                {
                  name: '结算电量',
                  type: 'bar',
                  barMaxWidth: 26,
                  data: b.daily.map((x) => x.energy),
                  itemStyle: { color: '#4676b8' }
                },
                {
                  name: '结算金额',
                  type: 'line',
                  yAxisIndex: 1,
                  showSymbol: true,
                  data: b.daily.map((x) => x.amount),
                  lineStyle: { color: '#5b9b92' },
                  itemStyle: { color: '#5b9b92' }
                }
              ]
            }}
          />
        ) : (
          <Empty description={business.isError ? '统计数据暂不可用' : '加载中'} />
        )}
      </Card>
      <div className="overview-section-title">
        <div>
          <h2>设备运行</h2>
          <span>设备数、在线状态与待处理事项</span>
        </div>
        <Link to="/devices">设备管理 →</Link>
      </div>
      <div className="metric-grid">
        <MetricCard label="设备总数" value={d.deviceTotal} suffix="台" />
        <MetricCard label="在线正常" value={normal} suffix="台" />
        <MetricCard
          label="故障设备"
          value={d.deviceFault}
          suffix="台"
          tone={d.deviceFault ? 'danger' : undefined}
        />
        <MetricCard
          label="未关闭告警"
          value={d.alarmOpen}
          suffix="条"
          tone={d.alarmOpen ? 'warn' : undefined}
        />
        <MetricCard label="运营站点" value={d.stationCount} suffix="个" />
      </div>
      <Row gutter={[16, 16]} style={{ marginTop: 20 }}>
        <Col xs={24} xl={8}>
          <Card title="连接状态" bordered={false} className="overview-panel">
            <div className="availability">
              <Progress
                type="dashboard"
                percent={d.deviceTotal ? Math.round((d.deviceOnline / d.deviceTotal) * 100) : 0}
                format={() =>
                  d.deviceTotal ? `${Math.round((d.deviceOnline / d.deviceTotal) * 100)}%` : '—'
                }
                strokeColor="#4676b8"
              />
              <div>
                <strong>{d.deviceOnline}</strong>
                <span>在线设备 / {d.deviceTotal} 台</span>
                <small>含在线故障设备</small>
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} xl={16}>
          <Card
            title="待处理告警"
            bordered={false}
            className="overview-panel"
            extra={<Link to="/alarms">查看全部 →</Link>}
          >
            <Table<AlarmView>
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={d.latestAlarms}
              locale={{ emptyText: '暂无未关闭告警' }}
              scroll={{ x: 500 }}
              columns={[
                {
                  title: '级别',
                  dataIndex: 'level',
                  render: (v: AlarmView['level']) => <AlarmLevelTag level={v} />
                },
                { title: '告警', dataIndex: 'title' },
                {
                  title: '设备',
                  dataIndex: 'deviceName',
                  render: (v: string | null, row) => (
                    <Link to={`/devices/${row.deviceId}`}>{v ?? row.deviceCode ?? '—'}</Link>
                  )
                },
                {
                  title: '状态',
                  dataIndex: 'state',
                  render: (v: AlarmView['state']) => <AlarmStateTag state={v} />
                },
                { title: '最近发生', dataIndex: 'lastAt' }
              ]}
            />
          </Card>
        </Col>
      </Row>
      <div className="overview-bottom">
        <Tag color="green">运行监控</Tag> 数据以设备最近上报及订单记录为准 ·{' '}
        <Link to="/products">查看产品与物模型 →</Link>
      </div>
    </>
  )
}
