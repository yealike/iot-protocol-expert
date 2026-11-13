import type {
  AiAgent,
  AiConversation,
  AiConversationDetail,
  AiRun,
  AiStreamEvent,
  AlarmView,
  BusinessOverview,
  CurrentUser,
  ManagedUser,
  ManagedRole,
  UserChange,
  DeviceDetail,
  DeviceRow,
  DeviceTypeDescriptor,
  Overview,
  OrderView,
  OrderDetail,
  OrderTelemetry,
  PageResult,
  ProductDetail,
  ProtocolDocument,
  ProductRow,
  SeriesView
} from './types'

/**
 * 统一请求层。
 *
 * 约定：所有请求带 session cookie；非 2xx 一律抛 ApiError（携带后端 problem detail 的 message），
 * 页面层只负责展示，不做 try/catch 拍平。
 */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string
  ) {
    super(message)
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    credentials: 'include',
    headers: init.body
      ? { 'Content-Type': 'application/json', 'X-OPS-Client': '1', ...(init.headers ?? {}) }
      : init.headers,
    ...init
  })
  if (response.status === 401) {
    throw new ApiError(401, '未登录或会话已过期')
  }
  if (!response.ok) {
    let message = `请求失败 HTTP ${response.status}`
    try {
      const body = await response.json()
      message = body.message ?? body.detail ?? body.title ?? message
    } catch {
      /* 非 JSON 响应保持默认文案 */
    }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const json = (body: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(body) })

export const api = {
  // ---------------------------------------------------------------- 会话

  roles: () => request<ManagedRole[]>('/api/ops/users/roles'),
  updateRole: (code: string, permissions: string[]) => request<ManagedRole>(`/api/ops/users/roles/${code}`, { method: 'PUT', body: JSON.stringify({ permissions }) }),
  users: () => request<ManagedUser[]>('/api/ops/users'),
  stations: () => request<Array<{ id: number; name: string }>>('/api/ops/stations'),
  createUser: (value: UserChange) => request<ManagedUser>('/api/ops/users', json(value)),
  updateUser: (id: number, value: UserChange) => request<ManagedUser>(`/api/ops/users/${id}`, { method: 'PUT', body: JSON.stringify(value) }),
  me: () => request<CurrentUser>('/api/ops/auth/me'),

  login: async (username: string, password: string) => {
    const body = new URLSearchParams({ username, password })
    const response = await fetch('/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body
    })
    if (!response.ok && response.status !== 302) {
      throw new ApiError(response.status, '账号或密码不正确')
    }
    const me = await api.me()
    if (!me.authenticated) {
      throw new ApiError(401, '账号或密码不正确')
    }
    return me
  },

  logout: async () => {
    await fetch('/logout', { method: 'POST', credentials: 'include' })
  },

  // ---------------------------------------------------------------- 总览

  overview: () => request<Overview>('/api/ops/overview'),
  businessOverview: () => request<BusinessOverview>('/api/ops/overview/business'),
  deviceTypes: () => request<DeviceTypeDescriptor[]>('/api/ops/overview/device-types'),

  // ---------------------------------------------------------------- 设备

  queryDevices: (query: Record<string, unknown>) =>
    request<PageResult<DeviceRow>>('/api/ops/devices/query', json(query)),
  device: (id: number) => request<DeviceDetail>(`/api/ops/devices/${id}`),
  deviceSeries: (id: number, propertyId: string, params: Record<string, string> = {}) => {
    const search = new URLSearchParams(params).toString()
    return request<SeriesView>(
      `/api/ops/devices/${id}/properties/${propertyId}/series${search ? `?${search}` : ''}`
    )
  },
  writeProperty: (id: number, body: { propertyId: string; channelNo?: string; value: unknown }) =>
    request<unknown>(`/api/ops/devices/${id}/properties`, json(body)),
  deployDevice: (id: number) =>
    request<DeviceRow>(`/api/ops/devices/${id}/deploy`, { method: 'POST' }),
  undeployDevice: (id: number) =>
    request<DeviceRow>(`/api/ops/devices/${id}/undeploy`, { method: 'POST' }),
  setDeviceEnabled: (id: number, value: boolean) =>
    request<DeviceRow>(`/api/ops/devices/${id}/enabled?value=${value}`, { method: 'POST' }),
  setDeviceWorkAllowance: (id: number, value: boolean) =>
    request<DeviceRow>(`/api/ops/devices/${id}/work-allowance?allowWork=${value}`, {
      method: 'POST'
    }),
  saveDevice: (body: unknown) => request<DeviceRow>('/api/ops/devices', json(body)),

  // ---------------------------------------------------------------- 产品

  queryProducts: (params: Record<string, string | number | undefined> = {}) => {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') search.set(key, String(value))
    })
    return request<PageResult<ProductRow>>(`/api/ops/products?${search.toString()}`)
  },
  product: (id: number) => request<ProductDetail>(`/api/ops/products/${id}`),
  saveProduct: (body: unknown, id?: number) =>
    id
      ? request<ProductRow>(`/api/ops/products/${id}`, {
          method: 'PUT',
          body: JSON.stringify(body)
        })
      : request<ProductRow>('/api/ops/products', json(body)),

  deleteProduct: (id: number) =>
    request<{ deleted: boolean }>(`/api/ops/products/${id}`, { method: 'DELETE' }),

  protocolDocuments: (protocol = '') =>
    request<ProtocolDocument[]>(
      `/api/ops/protocol-documents?protocol=${encodeURIComponent(protocol)}`
    ),
  uploadProtocolDocument: async (body: FormData) => {
    const response = await fetch('/api/ops/protocol-documents', {
      method: 'POST',
      credentials: 'include',
      body
    })
    if (!response.ok) {
      const error = await response.json().catch(() => ({}))
      throw new ApiError(response.status, error.message ?? `上传失败 HTTP ${response.status}`)
    }
    return response.json() as Promise<{ id: number }>
  },
  archiveProtocolDocument: (id: number) =>
    request<void>(`/api/ops/protocol-documents/${id}/archive`, { method: 'POST' }),

  // ---------------------------------------------------------------- 订单

  queryOrders: (params: Record<string, string | number | undefined> = {}) => {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') search.set(key, String(value))
    })
    return request<PageResult<OrderView>>(`/api/ops/orders/page?${search}`)
  },
  orderTelemetry: (orderNo: string) =>
    request<OrderTelemetry>(`/api/ops/orders/${encodeURIComponent(orderNo)}/telemetry`),
  order: (orderNo: string) =>
    request<OrderDetail>(`/api/ops/orders/${encodeURIComponent(orderNo)}`),

  // ---------------------------------------------------------------- 告警

  queryAlarms: (params: Record<string, string | number | undefined> = {}) => {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== '') search.set(key, String(value))
    })
    return request<PageResult<AlarmView>>(`/api/ops/alarms?${search.toString()}`)
  },
  ackAlarm: (id: number, note?: string) =>
    request<unknown>(`/api/ops/alarms/${id}/ack`, json({ note })),
  closeAlarm: (id: number, note?: string) =>
    request<unknown>(`/api/ops/alarms/${id}/close`, json({ note })),

  // ---------------------------------------------------------------- AI 助手

  aiAgents: () => request<AiAgent[]>('/api/ai/agents'),

  aiKnowledgeDocuments: () => request<Array<{ id: string; title: string; protocolCode: string; protocolVersion: string; status: string; chunkCount: number; updatedAt: string }>>('/api/ai/knowledge/documents'),
  aiKnowledgePreview: (id: string) => request<Array<{ chunkNo: number; sectionPath: string | null; pageNo: number | null; content: string }>>(`/api/ai/knowledge/documents/${id}/preview`),
  aiKnowledgeIndex: () => request<{ total: number; indexed: number; skipped: number; failed: number }>('/api/ai/knowledge/index', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-AI-Client': 'assistant' }, body: JSON.stringify({ publish: false })
  }),
  aiKnowledgeAction: (id: string, action: 'publish' | 'archive') => request<unknown>(`/api/ai/knowledge/documents/${id}/${action}`, {
    method: 'POST', headers: { 'X-AI-Client': 'assistant' }
  }),

  aiConversations: (page = 0, size = 30) =>
    request<PageResult<AiConversation>>(`/api/ai/conversations?page=${page}&size=${size}`),

  aiCreateConversation: (agentKey: string, title?: string) =>
    request<AiConversation>('/api/ai/conversations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-AI-Client': 'assistant' },
      body: JSON.stringify({ agentKey, title })
    }),

  aiConversation: (id: string) =>
    request<AiConversationDetail>(`/api/ai/conversations/${id}`),

  aiSubmitRun: (conversationId: string, question: string, idempotencyKey: string, priority = 100) =>
    request<AiRun>(`/api/ai/conversations/${conversationId}/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-AI-Client': 'assistant' },
      body: JSON.stringify({ question, idempotencyKey, priority })
    }),

  aiPauseRun: (runId: string) =>
    request<unknown>(`/api/ai/runs/${runId}/pause`, {
      method: 'POST',
      headers: { 'X-AI-Client': 'assistant' }
    }),

  aiResumeRun: (runId: string) =>
    request<unknown>(`/api/ai/runs/${runId}/resume`, {
      method: 'POST',
      headers: { 'X-AI-Client': 'assistant' }
    }),

  aiCancelRun: (runId: string) =>
    request<unknown>(`/api/ai/runs/${runId}/cancel`, {
      method: 'POST',
      headers: { 'X-AI-Client': 'assistant' }
    })
}

/**
 * SSE 订阅：浏览器 EventSource 不支持自定义头与断点参数，这里用 fetch + ReadableStream 解析。
 * 返回的函数用于中止连接；重连时带上最后收到的 cursor 即可补齐缺失事件。
 */
export function openAiEventStream(
  conversationId: string,
  after: number,
  onEvent: (event: AiStreamEvent) => void,
  onState?: (state: 'open' | 'closed') => void
): () => void {
  const controller = new AbortController()
  let cursor = after
  const run = async () => {
    while (!controller.signal.aborted) {
      try {
        const response = await fetch(
          `/api/ai/conversations/${conversationId}/events?after=${cursor}`,
          { credentials: 'include', signal: controller.signal, headers: { Accept: 'text/event-stream' } }
        )
        if (!response.ok || !response.body) {
          if (response.status === 401 || response.status === 403 || response.status === 404) return
          throw new Error('事件连接不可用')
        }
        onState?.('open')
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        while (!controller.signal.aborted) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          let separator = /\r?\n\r?\n/.exec(buffer)
          while (separator && separator.index !== undefined) {
            const raw = buffer.slice(0, separator.index)
            buffer = buffer.slice(separator.index + separator[0].length)
            const parsed = parseSseBlock(raw)
            if (parsed && (parsed.cursor === 0 || parsed.cursor > cursor)) {
              if (parsed.cursor > 0) cursor = parsed.cursor
              onEvent(parsed)
            }
            separator = /\r?\n\r?\n/.exec(buffer)
          }
        }
      } catch {
        // 断线后使用最后确认的游标重放。
      } finally {
        onState?.('closed')
      }
      if (!controller.signal.aborted) await new Promise((resolve) => setTimeout(resolve, 1500))
    }
  }
  void run()
  return () => controller.abort()
}

function parseSseBlock(raw: string): AiStreamEvent | null {
  let cursor = 0
  let type = 'message'
  const dataLines: string[] = []
  raw.split(/\r?\n/).forEach((line) => {
    if (line.startsWith('id:')) {
      cursor = Number(line.slice(3).trim()) || 0
    } else if (line.startsWith('event:')) {
      type = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim())
    }
  })
  if (dataLines.length === 0) {
    return null
  }
  let data: Record<string, unknown> = {}
  try {
    data = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
  } catch {
    data = { raw: dataLines.join('\n') }
  }
  return { cursor, type, data }
}
