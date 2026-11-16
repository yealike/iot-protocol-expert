import { useState } from 'react'
import { Button, Card, Tree } from 'antd'
interface TraceNode {
  key: string
  title: string
  children: TraceNode[]
}
interface Span {
  spanId: string
  parentSpanId?: string
  name?: string
  [key: string]: unknown
}
function hex(value: string) {
  if (/^[0-9a-f]{16}$/.test(value)) return value
  try {
    return Array.from(atob(value), (c) => c.charCodeAt(0).toString(16).padStart(2, '0')).join('')
  } catch {
    return value
  }
}
export default function TracePanel({
  trace,
  onScore
}: {
  trace: Record<string, unknown>
  onScore: (spanId: string) => void
}) {
  const [selected, setSelected] = useState<Span>()
  const spans: Span[] = []
  const visit = (value: unknown) => {
    if (Array.isArray(value)) {
      value.forEach(visit)
      return
    }
    if (value && typeof value === 'object') {
      const item = value as Record<string, unknown>
      if (typeof item.spanId === 'string' && typeof item.name === 'string')
        spans.push({
          ...item,
          spanId: hex(item.spanId),
          parentSpanId: typeof item.parentSpanId === 'string' ? hex(item.parentSpanId) : undefined
        })
      else Object.values(item).forEach(visit)
    }
  }
  visit(trace)
  const ids = new Set(spans.map((s) => s.spanId))
  const nodes = (parent?: string, seen = new Set<string>()): TraceNode[] =>
    spans
      .filter((s) =>
        parent ? s.parentSpanId === parent : !s.parentSpanId || !ids.has(s.parentSpanId)
      )
      .filter((s) => !seen.has(s.spanId))
      .map((s) => ({
        key: s.spanId,
        title: `${s.name} · ${s.spanId}`,
        children: nodes(s.spanId, new Set([...seen, s.spanId]))
      }))
  return (
    <>
      <Tree
        treeData={nodes()}
        onSelect={(keys) => setSelected(spans.find((s) => s.spanId === String(keys[0])))}
      />
      {selected && (
        <Card
          title={selected.name}
          extra={<Button onClick={() => onScore(selected.spanId)}>为此 Span 评分</Button>}
        >
          <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(selected, null, 2)}</pre>
        </Card>
      )}
      {!spans.length && (
        <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(trace, null, 2)}</pre>
      )}
    </>
  )
}
