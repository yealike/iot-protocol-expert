import { useQuery } from '@tanstack/react-query'
import { Button, Card, Col, Empty, Progress, Row, Skeleton } from 'antd'
import { Link } from 'react-router-dom'
import {
  AccountBookOutlined,
  AppstoreOutlined,
  CheckCircleOutlined,
  ClusterOutlined,
  DatabaseOutlined,
  EnvironmentOutlined,
  FileDoneOutlined,
  InfoCircleOutlined,
  ReloadOutlined,
  SwapOutlined,
  SyncOutlined,
  ThunderboltOutlined,
  WarningOutlined
} from '@ant-design/icons'
import { api } from '../api/client'
import Chart from '../components/Chart'
import { MetricCard } from '../components/tags'

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

  if (overview.isLoading) return <Skeleton active paragraph={{ rows: 10 }} />
  if (overview.isError || !overview.data) {
    return (
      <Empty
        description={overview.error instanceof Error ? overview.error.message : '总览加载失败'}
      />
    )
  }

  const d = overview.data
  const b = business.data
  const normal = Math.max(0, d.deviceOnline - d.deviceFault)
  const offline = Math.max(0, d.deviceTotal - d.deviceOnline)
  const onlinePercent = d.deviceTotal ? Math.round((d.deviceOnline / d.deviceTotal) * 100) : 0
  const refreshing = overview.isFetching || business.isFetching

  return (
    <div className="overview-page">
      <div className="page-header overview-page-header">
        <div>
          <div className="overview-title-line">
            <h1>运营总览</h1>
            <span className="overview-live-badge">
              <i /> 实时监测
            </span>
          </div>
          <p>聚合充电业务与设备运行数据，快速掌握今日运营表现。</p>
        </div>
        <div className="overview-header-actions">
          <span>{b?.date ?? '—'} · 北京时间</span>
          <Button
            icon={<ReloadOutlined />}
            loading={refreshing}
            onClick={() => Promise.all([overview.refetch(), business.refetch()])}
          >
            刷新数据
          </Button>
        </div>
      </div>

      <div className="metric-grid overview-primary-grid">
        <MetricCard
          icon={<ThunderboltOutlined />}
          label="今日充电量"
          value={b ? Number(b.chargedEnergy).toFixed(2) : '—'}
          suffix="kWh"
        />
        <MetricCard
          icon={<AccountBookOutlined />}
          label="今日结算金额（含估算）"
          value={b ? Number(b.settledAmount).toFixed(2) : '—'}
          suffix="元"
        />
        <MetricCard
          icon={<SyncOutlined />}
          label="进行中订单"
          value={b?.activeOrderCount ?? '—'}
          suffix="笔"
        />
        <MetricCard
          icon={<CheckCircleOutlined />}
          label="设备在线率"
          value={d.deviceTotal ? onlinePercent : '—'}
          suffix={d.deviceTotal ? '%' : undefined}
        />
      </div>

      <Row gutter={[20, 20]} className="overview-main-grid">
        <Col xs={24} xl={16}>
          <Card
            title="近 7 日业务趋势"
            variant="borderless"
            className="overview-panel overview-trend-panel"
            extra={<span className="muted">按订单结束日统计</span>}
          >
            {b ? (
              <Chart
                height={300}
                option={{
                  animationDuration: 500,
                  grid: { left: 58, right: 58, top: 48, bottom: 36 },
                  tooltip: {
                    trigger: 'axis',
                    backgroundColor: 'rgba(24, 34, 48, 0.94)',
                    borderWidth: 0,
                    textStyle: { color: '#fff' }
                  },
                  legend: {
                    top: 4,
                    right: 8,
                    itemWidth: 10,
                    itemHeight: 10,
                    data: ['结算电量', '结算金额'],
                    textStyle: { color: '#718096' }
                  },
                  xAxis: {
                    type: 'category',
                    data: b.daily.map((x) => x.date.slice(5)),
                    axisLine: { lineStyle: { color: '#dfe6ef' } },
                    axisTick: { show: false },
                    axisLabel: { color: '#8491a3' }
                  },
                  yAxis: [
                    {
                      type: 'value',
                      name: 'kWh',
                      nameTextStyle: { color: '#96a2b2' },
                      axisLabel: { color: '#96a2b2' },
                      splitLine: { lineStyle: { color: '#edf1f6' } }
                    },
                    {
                      type: 'value',
                      name: '元',
                      nameTextStyle: { color: '#96a2b2' },
                      axisLabel: { color: '#96a2b2' },
                      splitLine: { show: false }
                    }
                  ],
                  series: [
                    {
                      name: '结算电量',
                      type: 'bar',
                      barMaxWidth: 28,
                      data: b.daily.map((x) => x.energy),
                      itemStyle: { color: '#6b8ef0', borderRadius: [5, 5, 0, 0] }
                    },
                    {
                      name: '结算金额',
                      type: 'line',
                      yAxisIndex: 1,
                      smooth: true,
                      symbolSize: 7,
                      showSymbol: true,
                      data: b.daily.map((x) => x.amount),
                      lineStyle: { color: '#22a785', width: 2.5 },
                      itemStyle: { color: '#22a785', borderColor: '#fff', borderWidth: 2 },
                      areaStyle: { color: 'rgba(34, 167, 133, 0.06)' }
                    }
                  ]
                }}
              />
            ) : (
              <Empty description={business.isError ? '统计数据暂不可用' : '加载中'} />
            )}
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card
            title="设备运行状态"
            variant="borderless"
            className="overview-panel overview-health-panel"
            extra={<Link to="/devices">设备管理</Link>}
          >
            <div className="device-health-summary">
              <Progress
                type="circle"
                size={142}
                percent={onlinePercent}
                format={() => (d.deviceTotal ? `${onlinePercent}%` : '—')}
                strokeColor="#3563e9"
                trailColor="#edf1f7"
              />
              <div className="device-health-copy">
                <strong>{d.deviceOnline}</strong>
                <span>在线设备 / 共 {d.deviceTotal} 台</span>
                <small>设备数据每 15 秒自动更新</small>
              </div>
            </div>
            <div className="device-status-list">
              <div>
                <span><i className="status-dot normal" />在线正常</span>
                <strong>{normal}<small> 台</small></strong>
              </div>
              <div>
                <span><i className="status-dot fault" />故障设备</span>
                <strong className={d.deviceFault ? 'danger-text' : ''}>{d.deviceFault}<small> 台</small></strong>
              </div>
              <div>
                <span><i className="status-dot offline" />离线设备</span>
                <strong>{offline}<small> 台</small></strong>
              </div>
            </div>
          </Card>
        </Col>
      </Row>

      <Row gutter={[20, 20]}>
        <Col xs={24} xl={16}>
          <Card
            title="今日业务明细"
            variant="borderless"
            className="overview-panel"
            extra={<Link to="/orders">查看订单</Link>}
          >
            <div className="business-detail-grid">
              <div>
                <span className="business-detail-icon blue"><SwapOutlined /></span>
                <div><span>今日放电量</span><strong>{b ? Number(b.dischargedEnergy).toFixed(2) : '—'}<small> kWh</small></strong></div>
              </div>
              <div>
                <span className="business-detail-icon cyan"><DatabaseOutlined /></span>
                <div><span>结算订单充电量</span><strong>{b ? Number(b.settledEnergy).toFixed(2) : '—'}<small> kWh</small></strong></div>
              </div>
              <div>
                <span className="business-detail-icon green"><FileDoneOutlined /></span>
                <div><span>今日结算订单</span><strong>{b?.settledOrderCount ?? '—'}<small> 笔</small></strong></div>
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card title="运营资源" variant="borderless" className="overview-panel">
            <div className="resource-list">
              <Link to="/products">
                <span className="resource-icon"><AppstoreOutlined /></span>
                <span>充电产品<small>已接入产品模板</small></span>
                <strong>{d.productCount}<small> 个</small></strong>
              </Link>
              <Link to="/devices">
                <span className="resource-icon"><EnvironmentOutlined /></span>
                <span>运营站点<small>当前授权范围</small></span>
                <strong>{d.stationCount}<small> 个</small></strong>
              </Link>
              <Link to="/devices">
                <span className="resource-icon"><ClusterOutlined /></span>
                <span>设备总量<small>充电桩设备档案</small></span>
                <strong>{d.deviceTotal}<small> 台</small></strong>
              </Link>
            </div>
          </Card>
        </Col>
      </Row>

      <div className="overview-note">
        <InfoCircleOutlined />
        <span>
          今日充电量按自然日聚合；结算电量按订单结束日汇总，跨日订单计入结束日。金额可能包含平台估算，不代表实收。
          {business.isError ? ' 业务统计加载失败。' : ''}
        </span>
        {d.deviceFault > 0 ? <WarningOutlined className="overview-note-warning" /> : null}
      </div>
    </div>
  )
}
