import { Alert, Tag, Typography } from 'antd'
import type { AiPublicationState, AiPublishReceipt } from '../../api/types'

const { Text } = Typography

export const PUBLICATION_HISTORY_KEY = 'ipe-ai-publication-history'

export interface PublicationHistoryItem {
  id: string
  resourceType: string
  version?: string
  createdAt: string
}

export function stateColor(state: string): string {
  switch (state) {
    case 'PUBLISHED':
    case 'ACTIVE':
    case 'READY':
    case 'HEALTHY':
      return 'green'
    case 'FAILED':
    case 'REVOKED':
    case 'DOWN':
      return 'red'
    case 'VALIDATING':
    case 'PREPARING':
    case 'REGISTERING':
    case 'STAGED':
      return 'processing'
    case 'RETIRED':
    case 'UNKNOWN':
      return 'default'
    default:
      return 'blue'
  }
}

export function StateTag({ state }: { state: string }) {
  return <Tag color={stateColor(state)}>{state || 'UNKNOWN'}</Tag>
}

export function HealthTag({ healthy }: { healthy?: boolean | null }) {
  if (healthy == null) return <Tag>未知</Tag>
  return <Tag color={healthy ? 'green' : 'red'}>{healthy ? '健康' : '异常'}</Tag>
}

export function enabledTag(enabled: boolean) {
  return <Tag color={enabled ? 'green' : 'default'}>{enabled ? '启用' : '停用'}</Tag>
}

export function ErrorAlert({ error }: { error: unknown }) {
  return error ? <Alert type="error" showIcon message={(error as Error).message} /> : null
}

export function jsonText(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2)
}

export function parseJsonObject(value?: string): Record<string, unknown> {
  if (!value || !value.trim()) return {}
  const parsed = JSON.parse(value) as unknown
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('必须是 JSON 对象')
  }
  return parsed as Record<string, unknown>
}

export function rememberPublication(
  receipt: AiPublishReceipt,
  resourceType: string,
  version?: string
): string {
  if (typeof window === 'undefined') return receipt.publicationId
  try {
    const current = readPublicationHistory().filter((item) => item.id !== receipt.publicationId)
    const next: PublicationHistoryItem[] = [
      {
        id: receipt.publicationId,
        resourceType,
        version: receipt.version ?? version,
        createdAt: new Date().toISOString()
      },
      ...current
    ].slice(0, 20)
    window.localStorage.setItem(PUBLICATION_HISTORY_KEY, JSON.stringify(next))
  } catch {
    /* 本地发布记录仅用于便捷跳转，不可用时不阻断发布结果。 */
  }
  return receipt.publicationId
}

export function readPublicationHistory(): PublicationHistoryItem[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(PUBLICATION_HISTORY_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? (parsed as PublicationHistoryItem[]) : []
  } catch {
    return []
  }
}

export function PublicationStateText({ state }: { state: AiPublicationState | string }) {
  return <Text>{state}</Text>
}
