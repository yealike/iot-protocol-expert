/** 运维平台 API 契约。字段与后端 DeviceDtos / AlarmView 一一对应。 */

export interface PageResult<T> {
  content: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export type DeviceState = 'NOT_ACTIVE' | 'OFFLINE' | 'ONLINE' | 'FAULT'
export type AlarmLevel = 'INFO' | 'WARN' | 'MAJOR' | 'CRITICAL'
export type AlarmState = 'OPEN' | 'ACK' | 'CLOSED'

export type DeviceTypeCode = 'CHARGER'

export interface TagValue {
  key: string
  value: string | null
  valueType: string
}

export interface SceneAnchor {
  model: string | null
  position: number[] | null
  scale: number | null
}

export interface DeviceRow {
  id: number
  deviceCode: string
  name: string
  productId: number
  productName: string | null
  deviceType: DeviceTypeCode
  deviceTypeLabel: string
  protocol: string
  protocolName: string | null
  protocolNameEn: string | null
  protocolVersion: string | null
  protocolDeviceId: string
  stationId: number | null
  stationName: string | null
  state: DeviceState
  enabled: boolean
  allowWork: boolean
  allowSimulate: boolean
  lastSeen: string | null
  onlineAt: string | null
  registryTime: string | null
  sceneAnchor: SceneAnchor | null
  tags: TagValue[]
  highlights: Record<string, string>
}

export interface PropertyValue {
  propertyId: string
  name: string
  channelNo: string
  valueType: string
  value: string | number | null
  display: string | null
  unit: string | null
  updatedAt: string | null
  writable: boolean
  channelScoped: boolean
}

export interface ChannelView {
  channelNo: string
  channelType: string
  name: string
  enabled: boolean
  properties: PropertyValue[]
}

export interface EventView {
  id: number
  eventId: string
  eventName: string
  level: AlarmLevel
  channelNo: string | null
  content: unknown
  occurredAt: string | null
}

export interface AlarmView {
  id: number
  deviceId: number
  deviceCode: string | null
  deviceName: string | null
  channelNo: string | null
  level: AlarmLevel
  title: string
  state: AlarmState
  firstAt: string | null
  lastAt: string | null
  handledBy: string | null
  handledAt: string | null
  handlingNote: string | null
}

export interface CommandView {
  id: number
  commandType: string
  status: string
  requestPayload: string | null
  responsePayload: string | null
  operator: string | null
  createdAt: string | null
  sentAt: string | null
  ackAt: string | null
}

export interface DeviceDetail {
  device: DeviceRow
  thingModel: ThingModel
  channels: ChannelView[]
  properties: PropertyValue[]
  events: EventView[]
  alarms: AlarmView[]
  commands: CommandView[]
}

// ---------------------------------------------------------------- 物模型

export interface ValueType {
  type: string
  unit?: string
  min?: number
  max?: number
  scale?: number
  maxLength?: number
  format?: string
  elements?: { value: string; text: string }[]
  properties?: PropertyMetadata[]
  elementType?: ValueType
}

export interface PropertyMetadata {
  id: string
  name: string
  valueType: ValueType
  channel?: boolean
  readOnly?: boolean
  description?: string
}

export interface FunctionMetadata {
  id: string
  name: string
  async: boolean
  inputs: PropertyMetadata[]
  output?: ValueType
  description?: string
}

export interface EventMetadata {
  id: string
  name: string
  level: AlarmLevel
  valueType: ValueType
  description?: string
}

export interface TagMetadata {
  id: string
  name: string
  valueType: ValueType
  description?: string
}

export interface ThingModel {
  properties: PropertyMetadata[]
  functions: FunctionMetadata[]
  events: EventMetadata[]
  tags: TagMetadata[]
}

// ---------------------------------------------------------------- 产品

export interface ProductRow {
  id: number
  productCode: string
  name: string
  categoryCode: string
  deviceType: DeviceTypeCode
  deviceTypeLabel: string
  protocol: string
  protocolDocumentId: number | null
  protocolName: string | null
  protocolNameEn: string | null
  protocolVersion: string | null
  transport: string
  vendor: string | null
  model: string | null
  sceneModel: string | null
  description: string | null
  state: string
  deviceCount: number
  propertyCount: number
  functionCount: number
  eventCount: number
}

export interface ProductDetail {
  product: ProductRow
  metadata: ThingModel
  configuration: Record<string, unknown>
}

export interface DeviceTypeDescriptor {
  deviceType: DeviceTypeCode
  label: string
  categoryCode: string
  sceneModel: string | null
  defaultProtocol: string
  channelType: string | null
  icon: string
  templateProductId: number | null
  templateProductCode: string | null
  templateMetadata: ThingModel | null
  total?: number
  online?: number
  fault?: number
  offline?: number
}

// ---------------------------------------------------------------- 总览

export interface DeviceTypeSummary {
  deviceType: DeviceTypeCode
  label: string
  total: number
  online: number
  fault: number
  offline: number
  alarmOpen: number
}

export interface Overview {
  deviceTotal: number
  deviceOnline: number
  deviceFault: number
  alarmOpen: number
  stationCount: number
  productCount: number
  byType: DeviceTypeSummary[]
  latestAlarms: AlarmView[]
}

export interface SeriesView {
  deviceId: number
  propertyId: string
  channelNo: string | null
  name: string
  unit: string | null
  points: { time: string; number: number | null; text: string | null }[]
}

export interface CurrentUser {
  id?: string
  permissions?: string[]
  allStations?: boolean
  authenticated: boolean
  username?: string
  roles?: string[]
}

export interface OrderView {
  id: number
  orderNo: string
  deviceId: number
  protocolDeviceId: string
  gunNo: string
  stationId: number | null
  protocol: string
  status: string
  startTime: string | null
  endTime: string | null
  energy: number | null
  amount: number | null
  amountSource: string
  failReason: string | null
  stopReason: number
  createdAt: string | null
}
export interface BillView {
  id: number
  sourceType: string
  sourceTransactionNo: string
  energy: number | null
  amount: number | null
  currency: string
  amountType: string
  accepted: boolean
  rejectReason: string | null
  receivedAt: string | null
}
export interface OrderDetail {
  order: OrderView
  bills: BillView[]
}

export interface TelemetryPoint {
  time: string
  timeSource: 'DEVICE' | 'RECEIVED'
  voltage: number | null
  current: number | null
  power: number | null
  gunTemperature: number | null
  batteryTemperature: number | null
  soc: number | null
}
export interface OrderTelemetry {
  points: TelemetryPoint[]
  truncated: boolean
}

export interface BusinessOverview {
  date: string
  timezone: string
  settledOrderCount: number
  settledEnergy: number
  settledAmount: number
  activeOrderCount: number
  daily: { date: string; energy: number; amount: number; orders: number }[]
}

export interface ProtocolDocument {
  id: number
  protocolCode: string
  protocolNameEn: string
  documentVersion: string
  title: string
  originalFilename: string
  fileSize: number
  status: string
  uploadedBy: string
  createdAt: string
}

// ---------------------------------------------------------------- AI 助手

export interface AiAgent {
  agentKey: string
  version: string
  name: string
  kind: string
  description: string
  tools: string[]
}

export interface AiCitation {
  documentId: string
  sourceDocumentId?: string | null
  title: string
  protocolVersion?: string
  sectionPath?: string
  pageNo?: number | string
}

export interface AiConversation {
  id: string
  userId: string
  title: string
  agentKey: string
  agentVersion: string
  status: string
  eventCursor: number
  createdAt: string
  updatedAt: string
  lastMessageAt?: string
}

export interface AiMessage {
  id: string
  conversationId: string
  runId?: string
  role: 'user' | 'assistant'
  content: string
  citations: AiCitation[]
  status: string
  seq: number
  createdAt: string
}

export interface AiToolCall {
  id: string
  toolName: string
  status: string
  decision: string
  denyReason?: string
  durationMs?: number
  createdAt: string
}

export interface AiRun {
  id: string
  conversationId: string
  question: string
  agentKey: string
  status: string
  priority: number
  errorCode?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
  toolCalls?: AiToolCall[]
}

export interface AiConversationDetail {
  conversation: AiConversation
  messages: AiMessage[]
  currentRun: AiRun | null
}

export interface AiStreamEvent {
  cursor: number
  type: string
  data: Record<string, unknown>
}

export interface ManagedUser {
  id: number
  username: string
  enabled: boolean
  allStations: boolean
  roles: string[]
  permissions: string[]
  stationIds: number[]
}
export interface UserChange {
  username: string
  password?: string
  enabled: boolean
  allStations: boolean
  roles: string[]
  stationIds: number[]
}

export interface ManagedRole { code: string; name: string; permissions: string[] }
