import { Tag } from 'antd'
import type { AlarmLevel, AlarmState, DeviceState } from '../api/types'

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

const LEVEL_COLOR: Record<AlarmLevel, string> = {
  INFO: 'blue',
  WARN: 'gold',
  MAJOR: 'orange',
  CRITICAL: 'red'
}

export function AlarmLevelTag({ level }: { level: AlarmLevel }) {
  return (
    <Tag color={LEVEL_COLOR[level] ?? 'default'} bordered={false}>
      {level}
    </Tag>
  )
}

const ALARM_STATE: Record<AlarmState, { color: string; text: string }> = {
  OPEN: { color: 'red', text: '待处理' },
  ACK: { color: 'gold', text: '已确认' },
  CLOSED: { color: 'default', text: '已关闭' }
}

export function AlarmStateTag({ state }: { state: AlarmState }) {
  const meta = ALARM_STATE[state] ?? { color: 'default', text: state }
  return (
    <Tag color={meta.color} bordered={false}>
      {meta.text}
    </Tag>
  )
}

export function MetricCard({
  label,
  value,
  suffix,
  tone
}: {
  label: string
  value: string | number
  suffix?: string
  tone?: 'warn' | 'danger'
}) {
  return (
    <div className={`metric-card${tone ? ` ${tone}` : ''}`}>
      <div className="label">{label}</div>
      <div className="value">
        {value}
        {suffix ? <small>{suffix}</small> : null}
      </div>
    </div>
  )
}
