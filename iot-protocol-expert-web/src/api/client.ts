import type {
  AiAgentCatalogItem,
  AiAgentCompileResult,
  AiAgentComponent,
  AiAgentCreate,
  AiAgentDetail,
  AiAgentUpdate,
  AiConversation,
  AiConversationDetail,
  AiCredential,
  AiCredentialCreate,
  AiGatewayModel,
  AiGatewayModelCreate,
  AiGatewayProvider,
  AiGatewayProviderCreate,
  AiMcpComponent,
  AiMcpCreate,
  AiMcpRelease,
  AiMcpTestResult,
  AiMcpToolCatalog,
  AiMcpToolExecutionCategory,
  AiMcpToolPolicy,
  AiMcpUpdate,
  AiModelCreate,
  AiModelProfile,
  AiModelTestResult,
  AiModelUpdate,
  AiPublication,
  AiPublishReceipt,
  AiRegistryComponent,
  AiRegistryVersionDetail,
  AiRollbackReceipt,
  AiRun,
  AiSkillComponent,
  AiSkillCreate,
  AiSkillDraftFile,
  AiSkillFileInfo,
  AiSkillRelease,
  AiSkillUpdate,
  AiSkillValidation,
  AiStreamEvent,
  BusinessOverview,
  CurrentUser,
  ManagedUser,
  ManagedRole,
  QuotaGrantRequest,
  QuotaSnapshot,
  QuotaSubject,
  UsageItem,
  UserChange,
  DeviceDetail,
  DeviceSaveRequest,
  DeviceRow,
  Overview,
  OrderView,
  OrderDetail,
  OrderTelemetry,
  PageResult,
  ProductDetail,
  ProtocolMappingInput,
  ProtocolDocument,
  ProductRow,
  RateModelSave,
  RateModelView
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
    message: string,
    public code?: string,
    public traceId?: string
  ) {
    super(message)
  }
}

type RequestClient = 'ops' | 'ai'

async function request<T>(
  path: string,
  init: RequestInit = {},
  client: RequestClient = 'ops'
): Promise<T> {
  const { headers: initialHeaders, body, ...rest } = init
  const headers = new Headers(initialHeaders)
  if (body != null && !(body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (client === 'ai') {
    headers.set('X-AI-Client', 'web')
  } else if (body != null && !headers.has('X-OPS-Client')) {
    headers.set('X-OPS-Client', '1')
  }
  const response = await fetch(path, {
    ...rest,
    body,
    credentials: 'include',
    headers
  })
  if (response.status === 401) {
    throw new ApiError(401, '未登录或会话已过期')
  }
  if (!response.ok) {
    let message = `请求失败 HTTP ${response.status}`
    let code: string | undefined
    let traceId: string | undefined
    try {
      const errorBody = (await response.json()) as {
        error?: string
        message?: string
        detail?: string
        title?: string
        traceId?: string
      }
      message = errorBody.message ?? errorBody.detail ?? errorBody.title ?? message
      code = errorBody.error
      traceId = errorBody.traceId
    } catch {
      /* 非 JSON 响应保持默认文案 */
    }
    throw new ApiError(response.status, message, code, traceId)
  }
  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const json = (body: unknown): RequestInit => ({ method: 'POST', body: JSON.stringify(body) })

const AI_API = '/api/ai/v1'

export const aiRequest = <T>(path: string, init: RequestInit = {}) =>
  request<T>(`${AI_API}${path}`, init, 'ai')

const aiJson = (body: unknown, method: 'POST' | 'PUT' | 'DELETE' = 'POST'): RequestInit => ({
  method,
  body: JSON.stringify(body)
})

export const api = {
  // ---------------------------------------------------------------- 会话

  roles: () => request<ManagedRole[]>('/api/ops/users/roles'),
  updateRole: (code: string, permissions: string[]) =>
    request<ManagedRole>(`/api/ops/users/roles/${code}`, {
      method: 'PUT',
      body: JSON.stringify({ permissions })
    }),
  users: () => request<ManagedUser[]>('/api/ops/users'),
  createUser: (value: UserChange) => request<ManagedUser>('/api/ops/users', json(value)),
  updateUser: (id: number, value: UserChange) =>
    request<ManagedUser>(`/api/ops/users/${id}`, { method: 'PUT', body: JSON.stringify(value) }),
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

  // ---------------------------------------------------------------- 设备

  queryDevices: (query: Record<string, unknown>) =>
    request<PageResult<DeviceRow>>('/api/ops/devices/query', json(query)),
  lookupDevice: (sn: string) =>
    request<DeviceRow>(`/api/ops/devices/lookup?sn=${encodeURIComponent(sn)}`),
  device: (id: number) => request<DeviceDetail>(`/api/ops/devices/${id}`),
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
  saveDevice: (body: DeviceSaveRequest) => request<DeviceRow>('/api/ops/devices', json(body)),
  saveProtocolMappings: (id: number, mappings: ProtocolMappingInput[]) =>
    request<DeviceDetail>(`/api/ops/devices/${id}/protocol-mappings`, {
      method: 'PUT',
      body: JSON.stringify({ mappings })
    }),

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
    return response.json() as Promise<{ id: number; linkedProducts: number }>
  },
  archiveProtocolDocument: (id: number) =>
    request<void>(`/api/ops/protocol-documents/${id}/archive`, { method: 'POST' }),

  // ---------------------------------------------------------------- 费率

  rateModels: () => request<RateModelView[]>('/api/ops/rate-models'),
  saveRateModel: (body: RateModelSave) =>
    request<RateModelView>('/api/ops/rate-models', json(body)),

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

  // ---------------------------------------------------------------- AI 助手

  aiKnowledgeDocuments: () =>
    aiRequest<
      Array<{
        id: string
        title: string
        protocolCode: string
        protocolVersion: string
        status: string
        chunkCount: number
        updatedAt: string
      }>
    >('/knowledge/documents'),
  aiKnowledgePreview: (id: string) =>
    aiRequest<
      Array<{
        chunkNo: number
        sectionPath: string | null
        pageNo: number | null
        content: string
      }>
    >(`/knowledge/documents/${encodeURIComponent(id)}/preview`),
  aiKnowledgeIndex: () =>
    aiRequest<{ total: number; indexed: number; skipped: number; failed: number }>(
      '/knowledge/index',
      aiJson({ publish: false })
    ),
  aiKnowledgeAction: (id: string, action: 'publish' | 'archive') =>
    aiRequest<unknown>(`/knowledge/documents/${encodeURIComponent(id)}/${action}`, {
      method: 'POST'
    }),

  // ---------------------------------------------------------------- AI 管理：模型与凭据

  aiModels: () => aiRequest<AiModelProfile[]>('/models'),
  aiCreateModel: (body: AiModelCreate) => aiRequest<AiModelProfile>('/models', aiJson(body)),
  aiUpdateModel: (id: string, body: AiModelUpdate) =>
    aiRequest<AiModelProfile>(`/models/${encodeURIComponent(id)}`, aiJson(body, 'PUT')),
  aiTestModel: (id: string) =>
    aiRequest<AiModelTestResult>(`/models/${encodeURIComponent(id)}/test`, {
      method: 'POST'
    }),
  aiCredentials: () => aiRequest<AiCredential[]>('/credentials'),
  aiCreateCredential: (body: AiCredentialCreate) =>
    aiRequest<AiCredential>('/credentials', aiJson(body)),
  aiRotateCredential: (id: string, secret: string) =>
    aiRequest<AiCredential>(`/credentials/${encodeURIComponent(id)}`, aiJson({ secret }, 'PUT')),

  aiGatewayProviders: () => aiRequest<AiGatewayProvider[]>('/model-catalog/providers'),
  aiCreateGatewayProvider: (body: AiGatewayProviderCreate) =>
    aiRequest<AiGatewayProvider>('/model-catalog/providers', aiJson(body)),
  aiGatewayModels: () => aiRequest<AiGatewayModel[]>('/model-catalog/models'),
  aiCreateGatewayModel: (body: AiGatewayModelCreate) =>
    aiRequest<AiGatewayModel>('/model-catalog/models', aiJson(body)),

  // ---------------------------------------------------------------- AI 管理：MCP

  aiMcps: () => aiRequest<AiMcpComponent[]>('/mcps'),
  aiCreateMcp: (body: AiMcpCreate) => aiRequest<AiMcpComponent>('/mcps', aiJson(body)),
  aiUpdateMcp: (id: string, body: AiMcpUpdate) =>
    aiRequest<AiMcpComponent>(`/mcps/${encodeURIComponent(id)}`, aiJson(body, 'PUT')),
  aiDeleteMcp: (id: string) =>
    aiRequest<void>(`/mcps/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  aiTestMcp: (id: string) =>
    aiRequest<AiMcpTestResult>(`/mcps/${encodeURIComponent(id)}/test`, { method: 'POST' }),
  aiMcpTools: (id: string) => aiRequest<AiMcpToolCatalog>(`/mcps/${encodeURIComponent(id)}/tools`),
  aiMcpToolPolicies: (id: string) =>
    aiRequest<AiMcpToolPolicy[]>(`/mcps/${encodeURIComponent(id)}/tool-policies`),
  aiReviewMcpTool: (
    id: string,
    body: { toolName: string; executionCategory: AiMcpToolExecutionCategory }
  ) =>
    aiRequest<AiMcpToolPolicy>(
      `/mcps/${encodeURIComponent(id)}/tool-policies`,
      aiJson(body, 'PUT')
    ),
  aiMcpReleases: (id: string) =>
    aiRequest<AiMcpRelease[]>(`/mcps/${encodeURIComponent(id)}/releases`),
  aiPublishMcp: (
    id: string,
    body: {
      version?: string
      reportId: string
      expectedRevision: number
      idempotencyKey: string
    }
  ) => aiRequest<AiPublishReceipt>(`/mcps/${encodeURIComponent(id)}/publish`, aiJson(body)),

  // ---------------------------------------------------------------- AI 管理：Skill

  aiSkills: () => aiRequest<AiSkillComponent[]>('/skills'),
  aiCreateSkill: (body: AiSkillCreate) => aiRequest<AiSkillComponent>('/skills', aiJson(body)),
  aiUpdateSkill: (id: string, body: AiSkillUpdate) =>
    aiRequest<AiSkillComponent>(`/skills/${encodeURIComponent(id)}`, aiJson(body, 'PUT')),
  aiDeleteSkill: (id: string) =>
    aiRequest<void>(`/skills/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  aiSkillFiles: (id: string) =>
    aiRequest<AiSkillFileInfo[]>(`/skills/${encodeURIComponent(id)}/files`),
  aiSkillFile: (id: string, path: string) =>
    aiRequest<AiSkillDraftFile>(
      `/skills/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`
    ),
  aiSaveSkillFile: (id: string, path: string, expectedRevision: number, content: string) =>
    aiRequest<AiSkillDraftFile>(
      `/skills/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`,
      aiJson({ content, expectedRevision }, 'PUT')
    ),
  aiDeleteSkillFile: (id: string, path: string, expectedRevision: number) =>
    aiRequest<void>(
      `/skills/${encodeURIComponent(id)}/file?path=${encodeURIComponent(
        path
      )}&expectedRevision=${expectedRevision}`,
      { method: 'DELETE' }
    ),
  aiValidateSkill: (id: string) =>
    aiRequest<AiSkillValidation>(`/skills/${encodeURIComponent(id)}/validate`, {
      method: 'POST'
    }),
  aiSkillReleases: (id: string) =>
    aiRequest<AiSkillRelease[]>(`/skills/${encodeURIComponent(id)}/releases`),
  aiPublishSkill: (
    id: string,
    body: {
      version?: string
      expectedRevision: number
      idempotencyKey: string
    }
  ) => aiRequest<AiPublishReceipt>(`/skills/${encodeURIComponent(id)}/publish`, aiJson(body)),

  // ---------------------------------------------------------------- AI 管理：Agent

  aiAgents: () => aiRequest<AiAgentComponent[]>('/agents'),
  aiAgent: (id: string) => aiRequest<AiAgentDetail>(`/agents/${encodeURIComponent(id)}`),
  aiCreateAgent: (body: AiAgentCreate) => aiRequest<AiAgentComponent>('/agents', aiJson(body)),
  aiUpdateAgent: (id: string, body: AiAgentUpdate) =>
    aiRequest<AiAgentComponent>(`/agents/${encodeURIComponent(id)}`, aiJson(body, 'PUT')),
  aiDeleteAgent: (id: string) =>
    aiRequest<void>(`/agents/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  aiValidateAgent: (id: string) =>
    aiRequest<AiAgentCompileResult>(`/agents/${encodeURIComponent(id)}/validate`, {
      method: 'POST'
    }),
  aiPublishAgent: (
    id: string,
    body: {
      version?: string
      expectedRevision: number
      idempotencyKey: string
    }
  ) => aiRequest<AiPublishReceipt>(`/agents/${encodeURIComponent(id)}/publish`, aiJson(body)),
  aiRollbackAgent: (
    id: string,
    body: { targetVersion: string; expectedRevision: number; idempotencyKey: string }
  ) => aiRequest<AiRollbackReceipt>(`/agents/${encodeURIComponent(id)}/rollback`, aiJson(body)),

  // ---------------------------------------------------------------- AI 管理：发布与注册

  aiPublication: (id: string) =>
    aiRequest<AiPublication>(`/publications/${encodeURIComponent(id)}`),
  aiRetryPublication: (id: string) =>
    aiRequest<AiPublication>(`/publications/${encodeURIComponent(id)}/retry`, {
      method: 'POST'
    }),
  aiRegistryComponents: () => aiRequest<AiRegistryComponent[]>('/registry/components'),
  aiRegistryVersionDetail: (type: string, key: string, version: string) =>
    aiRequest<AiRegistryVersionDetail>(
      `/registry/components/${encodeURIComponent(type)}/${encodeURIComponent(
        key
      )}/versions/${encodeURIComponent(version)}`
    ),

  // ---------------------------------------------------------------- 对话

  aiAgentCatalog: () => aiRequest<AiAgentCatalogItem[]>('/agent-catalog'),

  aiConversations: (page = 0, size = 30) =>
    aiRequest<PageResult<AiConversation>>(`/conversations?page=${page}&size=${size}`),

  aiCreateConversation: (agentKey: string, version?: string, title?: string) =>
    aiRequest<AiConversation>('/conversations', aiJson({ agentKey, version, title })),

  aiConversation: (id: string) =>
    aiRequest<AiConversationDetail>(`/conversations/${encodeURIComponent(id)}`),

  aiSubmitRun: (conversationId: string, content: string, idempotencyKey: string) =>
    aiRequest<AiRun>(
      `/conversations/${encodeURIComponent(conversationId)}/runs`,
      aiJson({ content, idempotencyKey })
    ),

  aiCancelRun: (runId: string) =>
    aiRequest<{ ok?: boolean; status?: string; note?: string }>(
      `/runs/${encodeURIComponent(runId)}/cancel`,
      {
        method: 'POST'
      }
    ),
  myQuota: () => request<QuotaSnapshot>('/api/control/v1/me/quota'),
  myUsage: (limit = 100) => request<UsageItem[]>(`/api/control/v1/me/usage?limit=${limit}`),
  quotaSubjects: (scopeKind: QuotaGrantRequest['scopeKind'], query: string) =>
    request<QuotaSubject[]>(
      `/api/control/v1/admin/quota-subjects?${new URLSearchParams({ scopeKind, query })}`
    ),
  adminQuotaAccount: (scopeKind: string, subjectId: string) =>
    request<QuotaSnapshot>(
      `/api/control/v1/admin/quota-accounts?scopeKind=${encodeURIComponent(
        scopeKind
      )}&subjectId=${encodeURIComponent(subjectId)}`
    ),
  grantQuota: (body: QuotaGrantRequest, idempotencyKey: string) =>
    request<Record<string, unknown>>('/api/control/v1/admin/quota-grants', {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify(body)
    })
}

/**
 * SSE 订阅：浏览器 EventSource 不支持自定义头与断点参数，这里用 fetch + ReadableStream 解析。
 * 返回的函数用于中止连接；重连时带上最后收到的 cursor 即可补齐缺失事件。
 */
export function openAiEventStream(
  conversationId: string,
  afterCursor: number,
  onEvent: (event: AiStreamEvent) => void,
  onState?: (state: 'open' | 'reconnecting') => void,
  onError?: (error: ApiError) => void
): () => void {
  const controller = new AbortController()
  let cursor = Math.max(0, afterCursor)
  const run = async () => {
    while (!controller.signal.aborted) {
      const connection = new AbortController()
      const abortConnection = () => connection.abort()
      controller.signal.addEventListener('abort', abortConnection, { once: true })
      let idleTimer = window.setTimeout(abortConnection, 25_000)
      try {
        const response = await fetch(
          `${AI_API}/conversations/${encodeURIComponent(
            conversationId
          )}/events?afterCursor=${cursor}`,
          {
            credentials: 'include',
            signal: connection.signal,
            headers: {
              Accept: 'text/event-stream',
              'X-AI-Client': 'web'
            }
          }
        )
        if (
          !response.ok ||
          !response.body ||
          !response.headers.get('Content-Type')?.includes('text/event-stream')
        ) {
          if (response.status === 410) {
            onError?.(new ApiError(410, '事件游标已过期，请刷新会话快照'))
            return
          }
          if (response.status === 401 || response.status === 403 || response.status === 404) {
            onError?.(new ApiError(response.status, '事件连接不可用'))
            return
          }
          throw new Error('事件连接不可用')
        }
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        let connected = false
        while (!controller.signal.aborted) {
          const { done, value } = await reader.read()
          if (done || controller.signal.aborted) break
          window.clearTimeout(idleTimer)
          idleTimer = window.setTimeout(abortConnection, 25_000)
          if (!connected) {
            connected = true
            onState?.('open')
          }
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
        window.clearTimeout(idleTimer)
        controller.signal.removeEventListener('abort', abortConnection)
        connection.abort()
      }
      if (!controller.signal.aborted) {
        onState?.('reconnecting')
        await new Promise((resolve) => window.setTimeout(resolve, 1500))
      }
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
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  })
  if (dataLines.length === 0) {
    return null
  }
  let envelope: Record<string, unknown> = {}
  try {
    envelope = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
  } catch {
    envelope = { raw: dataLines.join('\n') }
  }
  if (!cursor && typeof envelope.cursor === 'number') {
    cursor = envelope.cursor
  }
  const eventType =
    type !== 'message' ? type : typeof envelope.type === 'string' ? envelope.type : 'message'
  const payload =
    envelope.payload && typeof envelope.payload === 'object' && !Array.isArray(envelope.payload)
      ? (envelope.payload as Record<string, unknown>)
      : envelope
  const runId =
    typeof envelope.runId === 'string'
      ? envelope.runId
      : typeof payload.runId === 'string'
        ? payload.runId
        : undefined
  return {
    cursor,
    type: eventType,
    eventId: typeof envelope.eventId === 'string' ? envelope.eventId : undefined,
    runId,
    createdAt: typeof envelope.createdAt === 'string' ? envelope.createdAt : undefined,
    data: payload
  }
}
