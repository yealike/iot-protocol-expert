import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Row,
  Segmented,
  Select,
  Skeleton,
  Space,
  Table,
  Tabs,
  Tag
} from 'antd'
import { useParams } from 'react-router-dom'
import { api } from '../api/client'
import { AlarmLevelTag, AlarmStateTag, DeviceStateTag } from '../components/tags'
import Chart from '../components/Chart'
import type { PropertyValue } from '../api/types'

export default function DeviceDetail() {
  const { id } = useParams()
  const deviceId = Number(id)
  const { message } = App.useApp()
  const queryClient = useQueryClient()

  const detail = useQuery({
    queryKey: ['device', deviceId],
    queryFn: () => api.device(deviceId),
    enabled: Number.isFinite(deviceId),
    refetchInterval: 10_000
  })

  const [channel, setChannel] = useState<string | undefined>()
  const [trendProperty, setTrendProperty] = useState<string | undefined>()

  const series = useQuery({
    queryKey: ['series', deviceId, trendProperty, channel],
    queryFn: () =>
      api.deviceSeries(deviceId, trendProperty!, channel ? { channelNo: channel } : {}),
    enabled: Boolean(trendProperty)
  })

  const action = useMutation({
    mutationFn: async (kind: 'deploy' | 'undeploy' | 'enable' | 'disable') => {
      if (kind === 'deploy') return api.deployDevice(deviceId)
      if (kind === 'undeploy') return api.undeployDevice(deviceId)
      return api.setDeviceEnabled(deviceId, kind === 'enable')
    },
    onSuccess: () => {
      message.success('操作已提交')
      queryClient.invalidateQueries({ queryKey: ['device', deviceId] })
      queryClient.invalidateQueries({ queryKey: ['overview'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const allProperties = useMemo(() => {
    if (!detail.data) return [] as PropertyValue[]
    const channelProps = detail.data.channels.flatMap((item) => item.properties)
    return [...detail.data.properties, ...channelProps]
  }, [detail.data])

  const numericProperties = useMemo(
    () =>
      allProperties.filter(
        (item) =>
          item.valueType !== 'string' && item.valueType !== 'enum' && item.valueType !== 'bool'
      ),
    [allProperties]
  )

  if (detail.isLoading) return <Skeleton active paragraph={{ rows: 10 }} />
  if (detail.isError || !detail.data) {
    return (
      <Empty description={detail.error instanceof Error ? detail.error.message : '设备不存在'} />
    )
  }

  const { device, channels, properties, events, alarms, commands, thingModel } = detail.data

  const seriesOption = {
    grid: { left: 56, right: 24, top: 24, bottom: 40 },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: (series.data?.points ?? []).map((point) => point.time.slice(11)),
      axisLabel: { color: '#7d93ac' }
    },
    yAxis: {
      type: 'value',
      name: series.data?.unit ?? '',
      splitLine: { lineStyle: { color: 'rgba(38,70,102,0.5)' } },
      axisLabel: { color: '#7d93ac' }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        showSymbol: false,
        areaStyle: { color: 'rgba(56,211,159,0.16)' },
        lineStyle: { color: '#38d39f', width: 2 },
        data: (series.data?.points ?? []).map((point) => point.number)
      }
    ]
  }

  const renderPropertyTiles = (items: PropertyValue[]) => (
    <div className="property-grid">
      {items.map((property) => (
        <div className="property-tile" key={`${property.channelNo}-${property.propertyId}`}>
          <div className="name">
            {property.name}
            {property.channelNo ? <span className="mono"> · {property.channelNo}</span> : null}
          </div>
          <div className="value">
            {property.display ?? '—'}
            {property.unit ? <small>{property.unit}</small> : null}
          </div>
          <div className="time">{property.updatedAt ?? '尚无数据'}</div>
        </div>
      ))}
    </div>
  )

  return (
    <>
      <div className="page-header">
        <div>
          <h1>
            {device.name} <DeviceStateTag state={device.state} />
          </h1>
          <p>
            <span className="mono">{device.deviceCode}</span> · {device.deviceTypeLabel} ·{' '}
            {device.protocol} · {device.stationName ?? '未分配站点'}
          </p>
        </div>
        <Space>
          <Button onClick={() => action.mutate('enable')} disabled={device.enabled}>
            启用
          </Button>
          <Button onClick={() => action.mutate('disable')} disabled={!device.enabled} danger>
            停用
          </Button>
          <Button type="primary" onClick={() => action.mutate('deploy')}>
            激活
          </Button>
          <Button onClick={() => action.mutate('undeploy')}>注销</Button>
        </Space>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={9}>
          <Card title="档案" size="small" bordered={false}>
            <Descriptions column={1} size="small" colon={false}>
              <Descriptions.Item label="产品">{device.productName}</Descriptions.Item>
              <Descriptions.Item label="协议编号">
                <span className="mono">{device.protocolDeviceId}</span>
              </Descriptions.Item>
              <Descriptions.Item label="允许工作">
                {device.allowWork ? '是' : '否'}
              </Descriptions.Item>
              <Descriptions.Item label="允许模拟">
                {device.allowSimulate ? '是' : '否'}
              </Descriptions.Item>
              <Descriptions.Item label="最近上报">{device.lastSeen ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="上线时间">{device.onlineAt ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="激活时间">{device.registryTime ?? '—'}</Descriptions.Item>
            </Descriptions>
          </Card>
          <Card title="标签" size="small" bordered={false} style={{ marginTop: 16 }}>
            <div className="chip-row" style={{ marginTop: 0 }}>
              {device.tags.length === 0 ? <span className="muted">无标签</span> : null}
              {device.tags.map((tag) => (
                <span className="chip" key={tag.key}>
                  {tag.key} = {tag.value ?? '—'}
                </span>
              ))}
            </div>
          </Card>
        </Col>
        <Col xs={24} xl={15}>
          <Card
            title="实时数据"
            size="small"
            bordered={false}
            extra={
              <Segmented
                size="small"
                options={[
                  { label: '全部', value: 'all' },
                  ...channels.map((item) => ({ label: item.name, value: item.channelNo }))
                ]}
                value={channel ?? 'all'}
                onChange={(value) => setChannel(value === 'all' ? undefined : String(value))}
              />
            }
          >
            {renderPropertyTiles(channel ? properties : properties)}
            {channels
              .filter((item) => !channel || item.channelNo === channel)
              .map((item) => (
                <div key={item.channelNo} style={{ marginTop: 16 }}>
                  <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
                    {item.name}（{item.channelNo} · {item.channelType}）
                  </div>
                  {renderPropertyTiles(item.properties)}
                </div>
              ))}
          </Card>

          <Card title="趋势" size="small" bordered={false} style={{ marginTop: 16 }}>
            <Space style={{ marginBottom: 12 }}>
              <Select
                placeholder="选择数值属性"
                style={{ width: 220 }}
                value={trendProperty}
                options={numericProperties.map((item) => ({
                  label: `${item.name}${item.channelNo ? ` · ${item.channelNo}` : ''}`,
                  value: item.propertyId
                }))}
                onChange={setTrendProperty}
              />
              <span className="muted" style={{ fontSize: 12 }}>
                默认展示最近 24 小时
              </span>
            </Space>
            {trendProperty ? (
              <Chart option={seriesOption} height={260} />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="选择一个属性查看趋势" />
            )}
          </Card>
        </Col>
      </Row>

      <Card bordered={false} style={{ marginTop: 16 }}>
        <Tabs
          items={[
            {
              key: 'model',
              label: `物模型（${thingModel.properties.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={thingModel.properties}
                  columns={[
                    { title: '属性', dataIndex: 'name' },
                    {
                      title: '标识',
                      dataIndex: 'id',
                      render: (value: string) => <span className="mono">{value}</span>
                    },
                    {
                      title: '类型',
                      dataIndex: 'valueType',
                      render: (type: { type: string; unit?: string }) => (
                        <Tag bordered={false}>
                          {type.type}
                          {type.unit ? ` · ${type.unit}` : ''}
                        </Tag>
                      )
                    },
                    {
                      title: '通道',
                      dataIndex: 'channel',
                      render: (value?: boolean) => (value ? '是' : '否')
                    },
                    {
                      title: '只读',
                      dataIndex: 'readOnly',
                      render: (value?: boolean) => (value ? '是' : '否')
                    }
                  ]}
                />
              )
            },
            {
              key: 'events',
              label: `事件（${events.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  dataSource={events}
                  pagination={{ pageSize: 10 }}
                  columns={[
                    { title: '时间', dataIndex: 'occurredAt', width: 170 },
                    { title: '事件', dataIndex: 'eventName' },
                    {
                      title: '级别',
                      dataIndex: 'level',
                      width: 100,
                      render: (level) => <AlarmLevelTag level={level} />
                    },
                    { title: '通道', dataIndex: 'channelNo', width: 90 },
                    {
                      title: '载荷',
                      dataIndex: 'content',
                      render: (content) => (
                        <span className="mono" style={{ fontSize: 11 }}>
                          {content ? JSON.stringify(content) : '—'}
                        </span>
                      )
                    }
                  ]}
                />
              )
            },
            {
              key: 'alarms',
              label: `告警（${alarms.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  dataSource={alarms}
                  pagination={false}
                  columns={[
                    {
                      title: '级别',
                      dataIndex: 'level',
                      width: 100,
                      render: (level) => <AlarmLevelTag level={level} />
                    },
                    { title: '告警', dataIndex: 'title' },
                    {
                      title: '状态',
                      dataIndex: 'state',
                      width: 100,
                      render: (state) => <AlarmStateTag state={state} />
                    },
                    { title: '最近发生', dataIndex: 'lastAt', width: 170 }
                  ]}
                />
              )
            },
            {
              key: 'commands',
              label: `指令（${commands.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  dataSource={commands}
                  pagination={false}
                  columns={[
                    { title: '时间', dataIndex: 'createdAt', width: 170 },
                    { title: '指令', dataIndex: 'commandType' },
                    { title: '状态', dataIndex: 'status', width: 130 },
                    { title: '操作人', dataIndex: 'operator', width: 110 },
                    { title: '响应', dataIndex: 'responsePayload', ellipsis: true }
                  ]}
                />
              )
            }
          ]}
        />
      </Card>
    </>
  )
}
