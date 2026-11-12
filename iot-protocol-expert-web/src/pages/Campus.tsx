import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Badge, Button, Card, Empty, Space, Tag } from 'antd'
import { CompressOutlined, ReloadOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { createCampus, type CampusHandle, type DeviceFeed } from '../three/campus'
import { DeviceStateTag } from '../components/tags'
import type { DeviceRow } from '../api/types'

const MODEL_BY_TYPE: Record<string, string> = {
  PV: 'PV',
  WIND: 'Wind',
  STORAGE: 'Storage',
  CHARGER: 'Charger',
  LOCK: 'Lock',
  GATE: 'GateIn',
  CAMERA: 'CameraIn',
  METER: 'Storage'
}

function numberOf(highlights: Record<string, string>, key: string): number | undefined {
  const raw = highlights[key]
  if (!raw) return undefined
  const match = raw.match(/-?\d+(\.\d+)?/)
  return match ? Number(match[0]) : undefined
}

function toFeed(device: DeviceRow): DeviceFeed {
  const model = device.sceneAnchor?.model ?? MODEL_BY_TYPE[device.deviceType] ?? device.deviceType
  const highlights = device.highlights ?? {}
  const lines = Object.values(highlights).slice(0, 2)
  return {
    model,
    deviceId: device.id,
    label: device.name,
    online: device.state === 'ONLINE' || device.state === 'FAULT',
    alarm: device.state === 'FAULT',
    lines,
    power: numberOf(highlights, 'activePower'),
    soc: numberOf(highlights, 'soc'),
    windSpeed: numberOf(highlights, 'windSpeed'),
    lowered: highlights['state'] === '降下' || highlights['state'] === 'lowered',
    open: highlights['state'] === '开启' || highlights['state'] === 'open',
    state: highlights['state']
  }
}

export default function Campus() {
  const holder = useRef<HTMLDivElement>(null)
  const handle = useRef<CampusHandle | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const devices = useQuery({
    queryKey: ['campus-devices'],
    queryFn: () => api.queryDevices({ size: 200, sort: 'deviceType,asc' }),
    refetchInterval: 10_000
  })

  const feeds = useMemo(() => (devices.data?.content ?? []).map(toFeed), [devices.data])

  useEffect(() => {
    let cancelled = false
    const element = holder.current
    if (!element) return
    createCampus(element, { modelUrl: '/assets/energy-campus.glb' })
      .then((instance) => {
        if (cancelled) {
          instance.dispose()
          return
        }
        handle.current = instance
        instance.onSelect(setSelected)
        instance.setDevices(feeds)
      })
      .catch((reason: unknown) => {
        setError(reason instanceof Error ? reason.message : '3D 场景加载失败')
      })
    return () => {
      cancelled = true
      handle.current?.dispose()
      handle.current = null
    }
    // 只在挂载时初始化；数据更新由下面的 effect 推送
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (handle.current) {
      handle.current.setDevices(feeds)
    }
  }, [feeds])

  const rows = devices.data?.content ?? []
  const selectedDevice = rows.find((row) => {
    const model = row.sceneAnchor?.model ?? MODEL_BY_TYPE[row.deviceType] ?? row.deviceType
    return model === selected
  })

  return (
    <>
      <div className="page-header">
        <div>
          <h1>园区沙盘</h1>
          <p>光储充放一体化的 3D 运行视图，点击设备查看实时状态</p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => devices.refetch()}>
            刷新数据
          </Button>
          <Button icon={<CompressOutlined />} onClick={() => handle.current?.resetView()}>
            重置视角
          </Button>
        </Space>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 320px', gap: 16 }}>
        <div className="campus-frame" ref={holder}>
          {error ? (
            <div style={{ display: 'grid', placeItems: 'center', height: '100%' }}>
              <Empty description={error} />
            </div>
          ) : null}
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <Card size="small" title="场景设备" bordered={false}>
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              {rows.map((row) => (
                <div
                  key={row.id}
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 8,
                    cursor: 'pointer'
                  }}
                  onClick={() => {
                    const model =
                      row.sceneAnchor?.model ?? MODEL_BY_TYPE[row.deviceType] ?? row.deviceType
                    setSelected(model)
                    handle.current?.focus(model)
                  }}
                >
                  <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <Badge
                      status={
                        row.state === 'FAULT'
                          ? 'error'
                          : row.state === 'ONLINE'
                            ? 'success'
                            : 'default'
                      }
                    />
                    <span style={{ fontSize: 13 }}>{row.name}</span>
                  </span>
                  <span className="muted mono" style={{ fontSize: 11 }}>
                    {row.deviceType}
                  </span>
                </div>
              ))}
            </Space>
          </Card>

          <Card size="small" title="选中设备" bordered={false}>
            {selectedDevice ? (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                <div style={{ fontWeight: 600 }}>
                  {selectedDevice.name} <DeviceStateTag state={selectedDevice.state} />
                </div>
                <div className="mono muted" style={{ fontSize: 11 }}>
                  {selectedDevice.deviceCode} · {selectedDevice.protocol}
                </div>
                <div className="chip-row" style={{ marginTop: 0 }}>
                  {Object.entries(selectedDevice.highlights ?? {}).map(([key, value]) => (
                    <span className="chip" key={key}>
                      {value}
                    </span>
                  ))}
                </div>
                <Link to={`/devices/${selectedDevice.id}`}>
                  <Button block size="small" type="primary" ghost>
                    打开设备详情
                  </Button>
                </Link>
              </Space>
            ) : (
              <Space direction="vertical" size={6}>
                <span className="muted" style={{ fontSize: 12 }}>
                  点击沙盘中的设备查看详情
                </span>
                <Tag bordered={false} color="green">
                  绿色：在线
                </Tag>
                <Tag bordered={false} color="red">
                  红色：故障/告警
                </Tag>
                <Tag bordered={false}>灰色：离线</Tag>
              </Space>
            )}
          </Card>
        </div>
      </div>
    </>
  )
}
