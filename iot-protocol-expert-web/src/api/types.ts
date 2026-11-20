/** 运维平台 API 契约。字段与后端 DeviceDtos 一一对应。 */

export interface PageResult<T> {
  content: T[]
  page: number
  size: number
  total: number
  totalPages: number
}

export type DeviceState = 'NOT_ACTIVE' | 'OFFLINE' | 'ONLINE' | 'FAULT'
export type EventLevel = 'INFO' | 'WARN' | 'MAJOR' | 'CRITICAL'

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
  level: EventLevel
  channelNo: string | null
  content: unknown
  occurredAt: string | null
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
  level: EventLevel
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
}

export interface Overview {
  deviceTotal: number
  deviceOnline: number
  deviceFault: number
  stationCount: number
  productCount: number
  byType: DeviceTypeSummary[]
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
  chargedEnergy: number
  dischargedEnergy: number
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

export type AiComponentOrigin = 'NATIVE_REGISTRY' | 'REMOTE'
export type AiReleaseState = 'STAGED' | 'ACTIVE' | 'RETIRED' | 'REVOKED' | 'FAILED'
export type AiPublicationState = 'VALIDATING' | 'PREPARING' | 'REGISTERING' | 'PUBLISHED' | 'FAILED'

export interface AiCompileIssue {
  code: string
  message: string
  field?: string | null
}

export interface AiPublishReceipt {
  releaseId?: string
  publicationId: string
  version?: string
  digest?: string
  catalogDigest?: string
  manifestDigest?: string
}

export interface AiRollbackReceipt {
  releaseId: string
  publicationId: string
  version: string
  generation: number
  state: AiPublicationState | string
}

export interface AiPublication {
  id: string
  resourceType: 'MODEL' | 'MCP' | 'SKILL' | 'AGENT' | string
  resourceId: string
  version: string
  draftRevision: number
  state: AiPublicationState | string
  phase: string
  generation: number
  idempotencyKey?: string
  errorCode?: string | null
  errorMessage?: string | null
  attempts: number
  nextAttemptAt?: string | null
  deadlineAt?: string | null
  createdAt: string
  updatedAt: string
  traceId?: string | null
}

export interface AiCredential {
  id: string
  key: string
  displayName: string
  currentSecretRevision: number
  enabled: boolean
  revision: number
  createdBy?: string
  createdAt: string
  updatedAt: string
  masked?: string
  keyId?: string
  secretRevision?: number
}

export interface AiCredentialCreate {
  key: string
  displayName: string
  secret: string
}

export interface AiModelProfile {
  id: string
  key: string
  displayName: string
  description?: string | null
  providerType: string
  baseUrl: string
  modelName: string
  credentialId?: string | null
  credentialKey?: string | null
  options: Record<string, unknown>
  enabled: boolean
  revision: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface AiModelCreate {
  key: string
  displayName: string
  description?: string
  options?: Record<string, unknown>
}

export interface AiModelUpdate {
  expectedRevision: number
  displayName: string
  description?: string
  options?: Record<string, unknown>
  enabled: boolean
}

export interface AiGatewayProvider {
  id: string
  code: string
  name: string
  baseUrl: string
  state: string
  credentialConfigured: boolean
}

export interface AiGatewayProviderCreate {
  code: string
  name: string
  baseUrl: string
  apiKey: string
}

export interface AiGatewayModel {
  id: string
  key: string
  displayName: string
  providerId: string
  upstreamModel: string
  contextWindow?: number | null
  maxOutputTokens?: number | null
  countingMode: string
  state: string
}

export interface AiGatewayModelCreate {
  key: string
  displayName: string
  providerCode: string
  upstreamModel: string
  contextWindow?: number | null
  maxOutputTokens?: number | null
  countingMode?: string
}

export interface AiModelTestResult {
  success: boolean
  model?: string
  latencyMs?: number
  usage?: unknown
  note?: string
  errorCode?: string
  message?: string
}

export interface AiMcpToolDescriptor {
  name: string
  description?: string | null
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  annotations?: Record<string, unknown>
  schemaDigest?: string
  readOnly?: boolean
}

export type AiMcpToolExecutionCategory = 'READ_ONLY' | 'WRITE' | 'UNKNOWN'

export interface AiMcpToolPolicy {
  mcpId: string
  toolName: string
  executionCategory: AiMcpToolExecutionCategory
  reviewedBy: string
  reviewedAt: string
}

export interface AiMcpComponent {
  id: string
  key: string
  displayName: string
  description?: string | null
  origin: AiComponentOrigin | string
  nativeRegistryRef: Record<string, unknown>
  draft: Record<string, unknown>
  publishedVersion?: string | null
  enabled: boolean
  revision: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface AiMcpRelease {
  id: string
  componentId?: string
  componentKey?: string
  version: string
  transport?: string
  endpointPolicy?: Record<string, unknown>
  credentialId?: string | null
  tools: AiMcpToolDescriptor[]
  catalogDigest?: string
  contentDigest?: string
  state: AiReleaseState | string
  createdAt: string
}

export interface AiMcpCreate {
  key: string
  displayName: string
  description?: string
  origin: AiComponentOrigin
  nativeRegistryRef: Record<string, unknown>
  draft: Record<string, unknown>
}

export interface AiMcpUpdate {
  expectedRevision: number
  displayName: string
  description?: string
  nativeRegistryRef: Record<string, unknown>
  draft: Record<string, unknown>
  enabled: boolean
}

export interface AiMcpTestResult {
  endpoint?: string
  serverName?: string
  serverVersion?: string
  protocolVersion?: string
  tools?: AiMcpToolDescriptor[]
  latencyMs?: number
  warnings?: string[]
  success: boolean
  reportId?: string
  configDigest?: string
  testedAt?: string
  errorCode?: string
  message?: string
}

export interface AiMcpToolCatalog {
  source: 'TEST' | 'PUBLISHED' | 'NONE' | string
  reportId?: string
  success?: boolean
  testedAt?: string
  tools?: AiMcpToolDescriptor[]
  warnings?: string[]
  publishedVersion?: string | null
  publishedReleaseId?: string
  publishedTools?: AiMcpToolDescriptor[]
  catalogDigest?: string
}

export interface AiSkillComponent {
  id: string
  key: string
  displayName: string
  description?: string | null
  draftRevision: number
  publishedVersion?: string | null
  enabled: boolean
  revision: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface AiSkillRelease {
  id: string
  skillId?: string
  componentKey?: string
  version: string
  name?: string
  description?: string
  bundle?: Record<string, unknown>
  bundleDigest?: string
  state: AiReleaseState | string
  createdAt: string
}

export interface AiSkillCreate {
  key: string
  displayName: string
  description?: string
}

export interface AiSkillUpdate {
  expectedRevision: number
  displayName: string
  description?: string
  enabled: boolean
}

export interface AiSkillFileInfo {
  path: string
  digest: string
  size: number
  entry: boolean
  updatedAt?: string
}

export interface AiSkillDraftFile {
  id: string
  skillId: string
  path: string
  content: string
  digest: string
  updatedAt: string
}

export interface AiSkillValidation {
  valid: boolean
  errors: AiCompileIssue[]
  warnings: AiCompileIssue[]
  metadata?: {
    name: string
    description: string
    metadata?: Record<string, string>
  } | null
  draftRevision: number
  fileCount: number
  totalBytes: number
}

export interface AiAgentComponent {
  id: string
  key: string
  displayName: string
  description?: string | null
  modelProfileId?: string | null
  systemPrompt: string
  modelOptions: Record<string, unknown>
  budget: {
    maxIterations?: number
    maxToolCalls?: number
    runTimeoutMs?: number
  }
  publishedVersion?: string | null
  enabled: boolean
  revision: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface AiAgentCreate {
  key: string
  displayName: string
  description?: string
  modelProfileId?: string | null
  systemPrompt: string
  modelOptions?: Record<string, unknown>
  budget?: Record<string, unknown>
  mcpBindings?: AiAgentMcpBindingInput[]
  skillBindings?: AiAgentSkillBindingInput[]
}

export interface AiAgentUpdate {
  expectedRevision: number
  displayName: string
  description?: string
  modelProfileId?: string | null
  systemPrompt: string
  modelOptions?: Record<string, unknown>
  budget?: Record<string, unknown>
  enabled: boolean
  mcpBindings?: AiAgentMcpBindingInput[]
  skillBindings?: AiAgentSkillBindingInput[]
}

export interface AiAgentMcpBinding {
  mcpReleaseId?: string
  componentKey: string
  version: string
  toolName: string
  required: boolean
  executionOrder: number
  descriptor?: AiMcpToolDescriptor | null
}

export interface AiAgentSkillBinding {
  skillReleaseId?: string
  componentKey: string
  version: string
  order: number
  loadMode: 'AUTO' | 'ALWAYS'
  digest?: string
  name?: string
  description?: string
}

export interface AiAgentMcpBindingInput {
  mcpReleaseId?: string
  componentKey: string
  version: string
  toolName: string
  required: boolean
  executionOrder: number
}

export interface AiAgentSkillBindingInput {
  skillReleaseId?: string
  componentKey: string
  version: string
  order: number
  loadMode: 'AUTO' | 'ALWAYS'
}

export interface AiAgentBindingsUpdate {
  expectedRevision: number
  mcpBindings: AiAgentMcpBindingInput[]
  skillBindings: AiAgentSkillBindingInput[]
}

export interface AiAgentRelease {
  id: string
  agentId?: string
  agentKey?: string
  version: string
  manifest?: Record<string, unknown>
  manifestDigest?: string
  state: AiReleaseState | string
  generation: number
  createdAt: string
}

export interface AiAgentDetail {
  agent: AiAgentComponent
  mcpBindings?: AiAgentMcpBinding[]
  skillBindings?: AiAgentSkillBinding[]
  releases?: AiAgentRelease[]
}

export interface AiAgentCompileResult {
  valid: boolean
  manifest?: Record<string, unknown>
  manifestDigest?: string
  errors: AiCompileIssue[]
  warnings: AiCompileIssue[]
}

export interface AiAgentCatalogItem {
  agentKey: string
  version: string
  name: string
  description: string
  status: string
  toolCount: number
  skillCount: number
  skills: string[]
  healthy: boolean
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
  manifestDigest?: string
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
  agentVersion?: string
  agentReleaseId?: string
  status: string
  priority: number
  traceId?: string
  warnings?: string[]
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
  eventId?: string
  runId?: string
  createdAt?: string
  data: Record<string, unknown>
}

export interface AiRegistryComponent {
  id: string
  resourceType: 'MCP' | 'SKILL' | 'AGENT' | string
  releaseId: string
  serviceName: string
  providerId: string
  desiredState: string
  observedState: string
  ownerNode?: string | null
  lastSuccessAt?: string | null
  lastError?: string | null
  healthy?: boolean
  componentKey?: string
  componentVersion?: string
}

export interface AiRegistryInstance {
  ip: string
  port: number
  metadata: Record<string, string>
}

export interface AiRegistryVersionDetail {
  componentType: string
  componentKey: string
  componentVersion?: string
  releaseId?: string
  serviceName?: string
  desiredState?: string
  observedState?: string
  instances: AiRegistryInstance[]
  checkedAt?: string
  error?: string
  healthy?: boolean
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

export interface ManagedRole {
  code: string
  name: string
  permissions: string[]
}

export interface TenantInfo {
  id: string
  code: string
  name: string
  state: string
}

export interface ProjectInfo {
  id: string
  tenantId: string
  code: string
  name: string
  state: string
}

export interface InvestigationTask {
  id: string
  tenantId: string
  projectId: string
  ownerId: string
  title: string
  goal: string
  scope: string
  status: string
  version: number
  createdAt: string
  updatedAt: string
}

export interface InvestigationEvidence {
  id: string
  tenantId: string
  projectId: string
  sourceType: string
  sourceId: string
  sourceVersion?: string | null
  contentHash: string
  summary: string
  rawRef?: string | null
  scope: string
  collectedAt: string
  expiresAt?: string | null
  metadata: string
  createdAt: string
}

export interface InvestigationEvent {
  id: string
  taskId: string
  seq: number
  type: string
  payload: string
  actorId?: string | null
  createdAt: string
}

export interface QuotaPolicyState {
  accountId: string
  policyKey: string
  mode: string
  state: string
  limitUnits: number
  usedUnits: number
  heldUnits: number
  remainingUnits: number
  activationState: string
  startAt?: string | null
  endAt?: string | null
  debtUnits: number
}

export interface QuotaSnapshot {
  scopeKind: string
  subjectId: string
  policies: QuotaPolicyState[]
  pendingReconciliation: number
}

export interface QuotaGrantRequest {
  scopeKind: 'USER' | 'SERVICE_ACCOUNT'
  subjectId: string
  mode: 'TOTAL' | 'ON_FIRST_USE_WINDOW'
  limitUnits: number
  durationSeconds?: number | null
  policyKey?: string
  reason?: string
}

export interface UsageItem {
  callId: string
  logicalCallId: string
  attemptNo: number
  model: string
  provider: string
  projectId?: string | null
  runId?: string | null
  state: string
  createdAt: string
  inputTokens: number
  outputTokens: number
  quotaUnits: number
  quality: string
}
