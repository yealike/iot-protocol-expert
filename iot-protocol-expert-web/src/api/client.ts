import type {
  AlarmView,
  BusinessOverview,
  CurrentUser,
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
      ? { 'Content-Type': 'application/json', ...(init.headers ?? {}) }
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
    request<unknown>(`/api/ops/alarms/${id}/close`, json({ note }))
}
