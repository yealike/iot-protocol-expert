import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Dropdown,
  Empty,
  Input,
  Modal,
  Skeleton,
  Space,
  Tabs,
  Tag,
  Typography
} from 'antd'
import {
  ArrowLeftOutlined,
  EditOutlined,
  FileTextOutlined,
  LinkOutlined,
  MoreOutlined,
  PoweroffOutlined,
  ReloadOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api } from '../api/client'
import { productProtocol } from '../api/protocol'
import { DeviceStateTag, EventLevelTag } from '../components/tags'
import type {
  ChannelView,
  PropertyValue,
  ProtocolMapping,
  ProtocolMappingInput
} from '../api/types'
import { formatDateTime } from '../utils/dateTime'

function channelProperty(channel: ChannelView, propertyId: string): PropertyValue | undefined {
  return channel.properties.find((property) => property.propertyId === propertyId)
}

function platformGunNoFor(sn: string, gunNo: string): string {
  return /^\d{8}$/.test(sn) ? `${sn}${gunNo}` : gunNo
}

export default function DeviceDetail() {
  const { id } = useParams()
  const deviceId = Number(id)
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [selectedGunNo, setSelectedGunNo] = useState(searchParams.get('gun') ?? '')
  const [mappingOpen, setMappingOpen] = useState(false)
  const [mappingDraft, setMappingDraft] = useState<ProtocolMapping[]>([])

  const detail = useQuery({
    queryKey: ['device', deviceId],
    queryFn: () => api.device(deviceId),
    enabled: Number.isFinite(deviceId),
    refetchInterval: 10_000
  })

  const action = useMutation({
    mutationFn: async (kind: 'deploy' | 'undeploy' | 'enable' | 'disable') => {
      if (kind === 'deploy') return api.deployDevice(deviceId)
      if (kind === 'undeploy') return api.undeployDevice(deviceId)
      return api.setDeviceEnabled(deviceId, kind === 'enable')
    },
    onSuccess: () => {
      message.success('设备状态已更新')
      void queryClient.invalidateQueries({ queryKey: ['device', deviceId] })
      void queryClient.invalidateQueries({ queryKey: ['devices'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const saveMappings = useMutation({
    mutationFn: (mappings: ProtocolMappingInput[]) =>
      api.saveProtocolMappings(deviceId, mappings),
    onSuccess: () => {
      setMappingOpen(false)
      message.success('协议接入映射已保存，请重启 protocol-gateway 后生效')
      void queryClient.invalidateQueries({ queryKey: ['device', deviceId] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const channels = detail.data?.channels ?? []
  const deviceProperties = detail.data?.properties ?? []
  const events = detail.data?.events ?? []
  const commands = detail.data?.commands ?? []
  const mappings = detail.data?.protocolMappings ?? []

  useEffect(() => {
    if (!channels.length) return
    if (!selectedGunNo || !channels.some((channel) => channel.gunNo === selectedGunNo)) {
      setSelectedGunNo(channels[0].gunNo)
    }
  }, [channels, selectedGunNo])

  if (detail.isLoading) return <Skeleton active paragraph={{ rows: 12 }} />
  if (detail.isError || !detail.data) {
    return (
      <Empty description={detail.error instanceof Error ? detail.error.message : '设备不存在'} />
    )
  }

  const { device } = detail.data
  const selectedChannel =
    channels.find((channel) => channel.gunNo === selectedGunNo) ?? channels[0]
  const selectedMapping = mappings.find(
    (mapping) => mapping.gunNo === selectedChannel?.gunNo
  )
  const selectedPlatformGunNo =
    selectedChannel?.platformGunNo ??
    selectedMapping?.platformGunNo ??
    (selectedChannel ? platformGunNoFor(device.deviceSn, selectedChannel.gunNo) : '—')

  const openMappingEditor = () => {
    setMappingDraft(
      channels.map((channel) => {
        const existing = mappings.find((mapping) => mapping.gunNo === channel.gunNo)
        return (
          existing ?? {
            gunNo: channel.gunNo,
            platformGunNo:
              channel.platformGunNo ??
              platformGunNoFor(device.deviceSn, channel.gunNo),
            thirdPartyDeviceId: '',
            protocolConnectorNo: channel.protocolConnectorNo,
            enabled: true
          }
        )
      })
    )
    setMappingOpen(true)
  }

  const renderProperties = (items: PropertyValue[]) => (
    <div className="property-grid device-property-grid">
      {items.map((property) => (
        <div
          className="property-tile"
          key={`${property.channelNo}-${property.propertyId}`}
        >
          <div className="property-name">{property.name}</div>
          <div className="property-value">
            {property.display ?? '—'}
            {property.unit ? <small>{property.unit}</small> : null}
          </div>
          <div className="property-time">{formatDateTime(property.updatedAt, '尚无数据')}</div>
        </div>
      ))}
    </div>
  )

  const moreItems = [
    ...(device.state === 'NOT_ACTIVE'
      ? [
          {
            key: 'deploy',
            icon: <ThunderboltOutlined />,
            label: '激活设备',
            onClick: () => action.mutate('deploy')
          }
        ]
      : [
          {
            key: 'undeploy',
            icon: <PoweroffOutlined />,
            label: '注销设备',
            onClick: () => action.mutate('undeploy')
          }
        ])
  ]

  return (
    <>
      <section className="device-detail-header">
        <div className="device-detail-heading">
          <Link to="/devices" className="back-link">
            <ArrowLeftOutlined /> 返回设备列表
          </Link>
          <div className="device-title-line">
            <Typography.Title
              level={2}
              className="device-sn-title mono"
              copyable={{ text: device.deviceSn }}
            >
              {device.deviceSn}
            </Typography.Title>
            <DeviceStateTag state={device.state} />
          </div>
          <p>{device.name}</p>
        </div>
        <Space wrap className="device-header-actions">
          <Button
            type="primary"
            icon={<FileTextOutlined />}
            onClick={() => navigate(`/orders?deviceId=${deviceId}`)}
          >
            查看订单
          </Button>
          <Button
            icon={<PoweroffOutlined />}
            danger={device.enabled}
            onClick={() => action.mutate(device.enabled ? 'disable' : 'enable')}
          >
            {device.enabled ? '停用设备' : '启用设备'}
          </Button>
          <Dropdown menu={{ items: moreItems }} trigger={['click']}>
            <Button icon={<MoreOutlined />}>更多</Button>
          </Dropdown>
        </Space>
        <div className="device-header-meta">
          <div>
            <span>所属产品</span>
            <Link to={`/products/${device.productId}`}>{device.productName ?? '—'}</Link>
          </div>
          <div>
            <span>设备协议</span>
            <strong>{productProtocol(device)}</strong>
          </div>
          <div>
            <span>枪口数量</span>
            <strong>{channels.length}</strong>
          </div>
          <div>
            <span>最近上报</span>
            <strong>{formatDateTime(device.lastSeen, '暂无数据')}</strong>
          </div>
        </div>
      </section>

      <div className="device-section-heading">
        <div>
          <h2>枪口实时数据</h2>
          <p>选择枪口查看完整运行属性，枪口编号由设备 SN 和两位枪号组成。</p>
        </div>
        <Button
          icon={<ReloadOutlined />}
          loading={detail.isFetching}
          onClick={() => detail.refetch()}
        >
          刷新
        </Button>
      </div>

      {channels.length ? (
        <>
          <div className="gun-switcher" role="tablist" aria-label="选择充电枪">
            {channels.map((channel) => {
              const gunState = channelProperty(channel, 'gunState')
              const gunNo = channel.gunNo
              return (
                <button
                  key={gunNo}
                  type="button"
                  role="tab"
                  aria-selected={gunNo === selectedGunNo}
                  className={gunNo === selectedGunNo ? 'active' : undefined}
                  onClick={() => setSelectedGunNo(gunNo)}
                >
                  <span className="gun-switcher-title">枪 {gunNo}</span>
                  <span className="mono">
                    {channel.platformGunNo ?? platformGunNoFor(device.deviceSn, gunNo)}
                  </span>
                  <small>{gunState?.display ?? (channel.enabled ? '启用' : '停用')}</small>
                </button>
              )
            })}
          </div>

          <Card
            variant="borderless"
            className="gun-detail-card"
            title={
              <div className="gun-card-title">
                <span>
                  充电枪 {selectedChannel?.gunNo}
                  <Tag bordered={false} color={selectedChannel?.enabled ? 'green' : 'default'}>
                    {selectedChannel?.enabled ? '已启用' : '已停用'}
                  </Tag>
                </span>
                <Typography.Text
                  className="mono platform-gun-no"
                  copyable={{ text: selectedPlatformGunNo }}
                >
                  {selectedPlatformGunNo}
                </Typography.Text>
              </div>
            }
          >
            {selectedChannel?.properties.length ? (
              renderProperties(selectedChannel.properties)
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该枪口暂无实时数据" />
            )}
          </Card>

          {deviceProperties.length > 0 ? (
            <Card
              variant="borderless"
              className="device-property-card"
              title="设备级运行数据"
            >
              {renderProperties(deviceProperties)}
            </Card>
          ) : null}
        </>
      ) : (
        <Card variant="borderless">
          <Empty description="暂无枪口" />
        </Card>
      )}

      <Card
        variant="borderless"
        className="mapping-card"
        title={
          <Space>
            <LinkOutlined />
            协议接入映射
          </Space>
        }
        extra={
          <Button icon={<EditOutlined />} onClick={openMappingEditor}>
            编辑映射
          </Button>
        }
      >
        <Alert
          type="warning"
          showIcon
          className="mapping-restart-alert"
          message="映射变更保存后，需要重启 protocol-gateway 才会重新加载。"
        />
        <div className="mapping-table">
          <div className="mapping-table-head">
            <span>枪口</span>
            <span>平台枪号</span>
            <span>第三方设备号</span>
            <span>第三方枪号</span>
            <span>状态</span>
          </div>
          {channels.map((channel) => {
            const mapping = mappings.find((item) => item.gunNo === channel.gunNo)
            return (
              <div className="mapping-table-row" key={channel.gunNo}>
                <strong>枪 {channel.gunNo}</strong>
                <span className="mono">
                  {channel.platformGunNo ??
                    mapping?.platformGunNo ??
                    platformGunNoFor(device.deviceSn, channel.gunNo)}
                </span>
                <span className="mono">{mapping?.thirdPartyDeviceId || '—'}</span>
                <span className="mono">
                  {channel.protocolConnectorNo ?? mapping?.protocolConnectorNo ?? '—'}
                </span>
                <Tag
                  bordered={false}
                  color={
                    mapping?.enabled && mapping.thirdPartyDeviceId
                      ? 'green'
                      : mapping?.thirdPartyDeviceId
                        ? 'gold'
                        : 'default'
                  }
                >
                  {mapping?.enabled && mapping.thirdPartyDeviceId
                    ? '已配置'
                    : mapping?.thirdPartyDeviceId
                      ? '已停用'
                      : '未配置'}
                </Tag>
              </div>
            )
          })}
        </div>
      </Card>

      <Card variant="borderless" className="device-record-card">
        <Tabs
          items={[
            {
              key: 'events',
              label: `事件（${events.length}）`,
              children: events.length ? (
                <div className="device-event-list">
                  {events.map((event) => (
                    <div className="device-event-row" key={event.id}>
                      <EventLevelTag level={event.level} />
                      <div>
                        <strong>{event.eventName}</strong>
                        <small>
                          {formatDateTime(event.occurredAt)}
                          {event.channelNo ? ` · 枪 ${event.channelNo}` : ''}
                        </small>
                      </div>
                      <code>{event.content ? JSON.stringify(event.content) : '—'}</code>
                    </div>
                  ))}
                </div>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无设备事件" />
              )
            },
            {
              key: 'commands',
              label: `指令（${commands.length}）`,
              children: commands.length ? (
                <div className="command-list">
                  {commands.map((command) => (
                    <div className="command-row" key={command.id}>
                      <div>
                        <strong>{command.commandType}</strong>
                        <small>{formatDateTime(command.createdAt)}</small>
                      </div>
                      <Tag bordered={false}>{command.status}</Tag>
                      <span>{command.operator || '系统'}</span>
                      <code>{command.responsePayload || command.requestPayload || '—'}</code>
                    </div>
                  ))}
                </div>
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无指令记录" />
              )
            }
          ]}
        />
      </Card>

      <Modal
        title="编辑协议接入映射"
        width={760}
        open={mappingOpen}
        confirmLoading={saveMappings.isPending}
        okText="保存映射"
        onOk={() =>
          saveMappings.mutate(
            mappingDraft.map((mapping) => ({
              gunNo: mapping.gunNo,
              thirdPartyDeviceId: mapping.thirdPartyDeviceId?.trim() || null,
              protocolConnectorNo: mapping.protocolConnectorNo?.trim() || null,
              enabled: mapping.enabled
            }))
          )
        }
        onCancel={() => !saveMappings.isPending && setMappingOpen(false)}
      >
        <Alert
          type="warning"
          showIcon
          className="mapping-restart-alert"
          message="保存后请重启 protocol-gateway，运行时才会使用新的第三方设备映射。"
        />
        <div className="mapping-editor mapping-editor-detail">
          {mappingDraft.map((mapping, index) => (
            <div className="mapping-row" key={mapping.gunNo}>
              <div className="mapping-identity">
                <span className="mapping-gun">枪 {mapping.gunNo}</span>
                <span className="mono">
                  {mapping.platformGunNo ??
                    platformGunNoFor(device.deviceSn, mapping.gunNo)}
                </span>
              </div>
              <label>
                <span>第三方设备号</span>
                <Input
                  value={mapping.thirdPartyDeviceId ?? ''}
                  placeholder="云快充桩号 / OCPP chargePointId"
                  onChange={(event) => {
                    const next = [...mappingDraft]
                    next[index] = {
                      ...mapping,
                      thirdPartyDeviceId: event.target.value
                    }
                    setMappingDraft(next)
                  }}
                />
              </label>
              <label>
                <span>第三方枪号</span>
                <Input
                  value={mapping.protocolConnectorNo ?? ''}
                  placeholder="例如 01"
                  onChange={(event) => {
                    const next = [...mappingDraft]
                    next[index] = {
                      ...mapping,
                      protocolConnectorNo: event.target.value
                    }
                    setMappingDraft(next)
                  }}
                />
              </label>
            </div>
          ))}
        </div>
      </Modal>
    </>
  )
}
