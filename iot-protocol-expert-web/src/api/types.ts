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

export interface DeviceRow {
  id: number
  /** 平台设备 SN，同时作为 8 位桩号。 */
  deviceSn: string
  name: string
  productId: number
  productName: string | null
  protocol: string
  protocolEnglishName: string | null
  protocolVersion: string | null
  state: DeviceState
  enabled: boolean
  keyMetrics: Record<string, string | number> | null
  lastSeen: string | null
  gunCount: number
  matchedGunNo: string | null
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
  gunNo: string
  platformGunNo: string | null
  protocolConnectorNo: string | null
  name: string
  enabled: boolean
  properties: PropertyValue[]
}

export interface ProtocolMapping {
  gunNo: string
  platformGunNo: string | null
  thirdPartyDeviceId: string | null
  protocolConnectorNo: string | null
  enabled: boolean
}

export interface ProtocolMappingInput {
  gunNo: string
  thirdPartyDeviceId: string | null
  protocolConnectorNo: string | null
  enabled: boolean
}

export interface DeviceSaveRequest {
  sn?: string
  name: string
  productId: number
  enabled: boolean
  protocolMappings: ProtocolMappingInput[]
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
  channels: ChannelView[]
  properties: PropertyValue[]
  events: EventView[]
  commands: CommandView[]
  protocolMappings?: ProtocolMapping[] | null
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
  protocol: string
  protocolDocumentId: number | null
  protocolEnglishName: string | null
  protocolVersion: string | null
  transport: string
  vendor: string | null
  model: string | null
  connectorCount: number
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

// ---------------------------------------------------------------- 总览

export interface Overview {
  deviceTotal: number
  deviceOnline: number
  deviceFault: number
  productCount: number
}

export interface CurrentUser {
  id?: string
  permissions?: string[]
  authenticated: boolean
  username?: string
  roles?: string[]
}

export interface OrderView {
  id: number
  orderNo: string
  transactionNo: string | null
  deviceId: number
  deviceSn: string | null
  gunNo: string
  platformGunNo: string | null
  protocol: string
  status: string
  meterStartWh: number | null
  startTime: string | null
  endTime: string | null
  durationSeconds: number | null
  energy: number | null
  electricityFee: number | null
  serviceFee: number | null
  amount: number | null
  lossEnergy: number | null
  amountSource: string
  rateModelNo: string | null
  rateSnapshot: string | null
  stopReason: number
  failReason: string | null
  createdAt: string | null
}
export interface BillView {
  id: number
  sourceType: string
  sourceTransactionNo: string
  energy: number | null
  electricityFee: number | null
  serviceFee: number | null
  amount: number | null
  lossEnergy: number | null
  rateModelNo: string | null
  currency: string
  amountType: string
  accepted: boolean
  rejectReason: string | null
  receivedAt: string | null
}
export interface OrderFeeSegment {
  id: number
  orderId: number
  period: number
  energy: number
  lossEnergy: number
  electricityPrice: number
  servicePrice: number
  electricityFee: number
  serviceFee: number
  totalAmount: number
  source: string | null
  createdAt: string | null
}
export interface OrderDetail {
  order: OrderView
  bills: BillView[]
  feeSegments: OrderFeeSegment[]
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
  activeOrderCount: number
  daily: { date: string; energy: number; amount: number; orders: number }[]
}

export interface RateModelView {
  modelNo: string
  sharpEnergy: number
  sharpService: number
  peakEnergy: number
  peakService: number
  flatEnergy: number
  flatService: number
  valleyEnergy: number
  valleyService: number
  lossRatio: number
  segments: number[]
  active: boolean
  updatedAt: string | null
}

export type RateModelSave = RateModelView

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

export interface AiModelOptions {
  temperature?: number | null
  maxTokens?: number | null
  contextWindow?: number | null
}

export interface AiAgentBudget {
  maxIterations?: number | null
  maxToolCalls?: number | null
  runTimeoutMs?: number | null
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
  options?: AiModelOptions | null
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
  baseUrl?: string
  modelName?: string
  credentialId?: string | null
  options?: AiModelOptions | null
}

export interface AiModelUpdate {
  expectedRevision: number
  displayName: string
  description?: string
  baseUrl?: string
  modelName?: string
  credentialId?: string | null
  options?: AiModelOptions | null
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
  usage?: AiRunUsage | null
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

export interface AiMcpNativeRegistryRef {
  serverName?: string | null
  namespace?: string | null
  version?: string | null
}

export interface AiMcpDraft {
  transport?: string | null
  contextMode?: string | null
  timeoutMs?: number | null
  credentialId?: string | null
  endpoint?: string | null
}

export interface AiMcpComponent {
  id: string
  key: string
  displayName: string
  description?: string | null
  origin: AiComponentOrigin | string
  nativeRegistryRef?: AiMcpNativeRegistryRef | null
  draft?: AiMcpDraft | null
  publishedVersion?: string | null
  enabled: boolean
  revision: number
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface AiMcpEndpointPolicy {
  endpoint?: string | null
  authority?: string | null
  contextMode?: string | null
  path?: string | null
  query?: string | null
  serviceName?: string | null
  timeoutMs?: number | null
  authMode?: string | null
}

export interface AiMcpRelease {
  id: string
  componentId?: string
  componentKey?: string
  version: string
  transport?: string
  endpointPolicy?: AiMcpEndpointPolicy | null
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
  nativeRegistryRef?: AiMcpNativeRegistryRef | null
  draft?: AiMcpDraft | null
}

export interface AiMcpUpdate {
  expectedRevision: number
  displayName: string
  description?: string
  nativeRegistryRef?: AiMcpNativeRegistryRef | null
  draft?: AiMcpDraft | null
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
  bundle?: AiSkillBundle | null
  bundleDigest?: string
  state: AiReleaseState | string
  createdAt: string
}

export interface AiSkillBundle {
  key: string
  version: string
  name: string
  description?: string | null
  digest?: string | null
  files: AiSkillBundleFile[]
}

export interface AiSkillBundleFile {
  path: string
  content: string
  digest: string
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
    license?: string | null
    compatibility?: string | null
    allowedTools?: string | null
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
  modelOptions?: AiModelOptions | null
  budget?: AiAgentBudget | null
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
  modelOptions?: AiModelOptions | null
  budget?: AiAgentBudget | null
  mcpBindings?: AiAgentMcpBindingInput[]
  skillBindings?: AiAgentSkillBindingInput[]
}

export interface AiAgentUpdate {
  expectedRevision: number
  displayName: string
  description?: string
  modelProfileId?: string | null
  systemPrompt: string
  modelOptions?: AiModelOptions | null
  budget?: AiAgentBudget | null
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

export interface AiAgentManifest {
  schemaVersion: string
  agentKey: string
  version: string
  displayName: string
  systemPrompt: string
  model: {
    profileKey: string
    providerType?: string | null
    baseUrl?: string | null
    modelName?: string | null
    credentialRef?: string | null
    secretRevision?: number | null
    options?: AiModelOptions | null
  }
  skills: Array<{
    key: string
    version: string
    digest?: string | null
    loadMode: string
    order: number
  }>
  mcp: Array<{
    key: string
    version: string
    required: boolean
    catalogDigest?: string | null
    tools: Array<{
      name: string
      alias: string
      schemaDigest?: string | null
    }>
  }>
  budget: AiAgentBudget
  executionPolicy: {
    scriptExecution: boolean
    unknownTool: string
  }
}

export interface AiAgentRelease {
  id: string
  agentId?: string
  agentKey?: string
  version: string
  manifest?: AiAgentManifest | null
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
  manifest?: AiAgentManifest | null
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
  healthVerified?: boolean
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
  usage?: AiRunUsage | null
}

export interface AiRunUsage {
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  modelCalls?: number
  toolCalls?: number
  durationMs?: number
}

export interface AiToolCallEvidence {
  text: string
  truncated: boolean
}

export interface AiToolCall {
  id: string
  runId: string
  conversationId: string
  toolName: string
  status: string
  decision: string
  denyReason?: string
  argsSummary?: AiToolCallEvidence | null
  resultSummary?: AiToolCallEvidence | null
  durationMs?: number
  traceId?: string
  createdAt: string
  finishedAt?: string
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
  usage?: AiRunUsage | null
  startedAt?: string
  finishedAt?: string
  createdAt: string
  toolCalls?: AiToolCall[]
}

export interface AiConversationDetail {
  conversation: AiConversation
  messages: AiMessage[]
  currentRun: AiRun | null
  latestRun?: AiRun | null
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
  metadata: { key: string; value: string }[]
}

export interface AiRegistryVersionDetail {
  releaseState?: string
  componentType: string
  componentKey: string
  componentVersion?: string
  releaseId?: string
  serviceName?: string
  desiredState?: string
  observedState?: string
  instances: AiRegistryInstance[]
  checkedAt?: string
  lastError?: string
  healthy?: boolean
}

export interface ManagedUser {
  id: number
  username: string
  enabled: boolean
  roles: string[]
  permissions: string[]
}
export interface UserChange {
  username: string
  password?: string
  enabled: boolean
  roles: string[]
}

export interface ManagedRole {
  code: string
  name: string
  permissions: string[]
}

export interface QuotaPolicyState {
  accountId: string
  policyKey: string
  mode: QuotaGrantRequest['mode']
  state: string
  limitUnits: number
  durationSeconds?: number | null
  usedUnits: number
  heldUnits: number
  remainingUnits: number
  availableUnits: number
  effective: boolean
  expiredWindow: boolean
  activationState: string
  startAt?: string | null
  endAt?: string | null
  debtUnits: number
}

export interface QuotaSnapshot {
  scopeKind: string
  subjectId: string
  availableUnits: number
  policies: QuotaPolicyState[]
  pendingReconciliation: number
}

export interface QuotaSubject {
  id: string
  code: string
  name?: string | null
  state: string
  context?: string | null
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

export interface QuotaGrantResult {
  accountId: string
  revision?: number | null
  mode: string
  policyKey?: string
  replayed: boolean
}

export interface OperationResult {
  ok: boolean
  status: string
}

export interface AiRunCancelResult {
  ok: boolean
  status?: string
  note?: string
}

export interface UsageList {
  items: UsageItem[]
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
  inputTokens: number | null
  outputTokens: number | null
  quotaUnits: number | null
  quality: string | null
}
