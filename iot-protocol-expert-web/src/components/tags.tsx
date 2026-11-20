import { Tag } from 'antd'
import type { ReactNode } from 'react'
import type { DeviceState, EventLevel } from '../api/types'

const DEVICE_STATE: Record<DeviceState, { color: string; text: string }> = {
  ONLINE: { color: 'green', text: '在线' },
  OFFLINE: { color: 'default', text: '离线' },
  FAULT: { color: 'red', text: '故障' },
  NOT_ACTIVE: { color: 'gold', text: '未激活' }
}

export function DeviceStateTag({ state }: { state: DeviceState }) {
  const meta = DEVICE_STATE[state] ?? { color: 'default', text: state }
  return (
    <Tag color={meta.color} bordered={false}>
      {meta.text}
    </Tag>
  )
}

const LEVEL_COLOR: Record<EventLevel, string> = {
  INFO: 'blue',
  WARN: 'gold',
  MAJOR: 'orange',
  CRITICAL: 'red'
}

export function EventLevelTag({ level }: { level: EventLevel }) {
  return (
    <Tag color={LEVEL_COLOR[level] ?? 'default'} bordered={false}>
      {level}
    </Tag>
  )
}

export function MetricCard({
  label,
  value,
  suffix,
  tone,
  icon
}: {
  label: string
  value: string | number
  suffix?: string
  tone?: 'warn' | 'danger'
  icon?: ReactNode
}) {
  return (
    <div className={`metric-card${tone ? ` ${tone}` : ''}`}>
      <div className="metric-card-top">
        <div className="label">{label}</div>
        {icon ? <span className="metric-icon">{icon}</span> : null}
      </div>
      <div className="value">
        {value}
        {suffix ? <small>{suffix}</small> : null}
      </div>
    </div>
  )
}
