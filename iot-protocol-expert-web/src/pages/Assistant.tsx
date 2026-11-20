import { useEffect, useMemo, useRef, useState } from 'react'
import { Alert, App, Button, Drawer, Grid, Input, Select, Space, Tag, Typography } from 'antd'
import {
  ArrowRightOutlined,
  HistoryOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined
} from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api, openAiEventStream } from '../api/client'
import type { AiMessage, AiStreamEvent } from '../api/types'
import { formatDateTime } from '../utils/dateTime'

const { Text, Paragraph } = Typography

const STATUS_LABEL: Record<string, string> = {
  QUEUED: '排队中',
  DISPATCHING: '正在调度',
  RUNNING: '执行中',
  CANCEL_REQUESTED: '正在停止',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消'
}

const ACTIVE_RUN_STATUSES = new Set(['QUEUED', 'DISPATCHING', 'RUNNING', 'CANCEL_REQUESTED'])
const TERMINAL_RUN_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const LAST_AGENT_STORAGE_KEY = 'ipe-ai-last-agent'
const EXAMPLE_PROMPTS = [
  {
    title: '分析设备离线原因',
    prompt: '请结合设备状态、最近上报和协议知识，分析设备离线的可能原因，并给出排查顺序。'
  },
  {
    title: '解读充电停止原因',
    prompt: '请结合订单与设备事实，解读这次充电停止的原因，并区分平台、设备和车辆侧因素。'
  },
  {
    title: '查询协议字段含义',
    prompt: '请解释这个协议字段的含义、取值范围和常见异常情况，并标注对应的协议依据。'
  }
]

interface ProgressStep {
  id: string
  text: string
  color?: string
}

export default function Assistant() {
  const { message, modal } = App.useApp()
  const queryClient = useQueryClient()
  const screens = Grid.useBreakpoint()
  const compact = screens.md === false
  const [historyOpen, setHistoryOpen] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [targetAgentKey, setTargetAgentKey] = useState<string | null>(readLastAgent)
  const [input, setInput] = useState('')
  const [streamText, setStreamText] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [progress, setProgress] = useState<ProgressStep[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const [activeRunId, setActiveRunId] = useState<string | null>(null)
  const cursorRef = useRef(0)
  const activeRunIdRef = useRef<string | null>(null)
  const stopStreamRef = useRef<(() => void) | null>(null)
  const streamConversationRef = useRef<string | null>(null)
  const messagesRef = useRef<HTMLDivElement | null>(null)

  const agents = useQuery({ queryKey: ['ai-agent-catalog'], queryFn: api.aiAgentCatalog })
  const conversations = useQuery({
    queryKey: ['ai-conversations'],
    queryFn: () => api.aiConversations()
  })
  const detail = useQuery({
    queryKey: ['ai-conversation', selectedId],
    queryFn: () => api.aiConversation(selectedId as string),
    enabled: Boolean(selectedId)
  })

  const targetAgent = useMemo(
    () => agents.data?.find((agent) => agent.agentKey === targetAgentKey) ?? null,
    [agents.data, targetAgentKey]
  )
  const conversation = detail.data?.conversation ?? null
  const currentRun = detail.data?.currentRun ?? null
  const currentCatalog = useMemo(
    () =>
      conversation
        ? ((agents.data ?? []).find(
            (agent) =>
              agent.agentKey === conversation.agentKey &&
              agent.version === conversation.agentVersion
          ) ?? null)
        : null,
    [agents.data, conversation]
  )
  const currentAgentOptions = useMemo(() => {
    const options = (agents.data ?? []).map((agent) => ({
      value: agent.agentKey,
      disabled: !agent.healthy,
      label: (
        <Space size={4}>
          <span>{agent.name}</span>
          <Tag color={agent.healthy ? 'green' : 'red'} bordered={false}>
            v{agent.version}
          </Tag>
          {!agent.healthy ? <Tag color="red">不可用</Tag> : null}
        </Space>
      )
    }))
    if (conversation && !options.some((option) => option.value === conversation.agentKey)) {
      options.unshift({
        value: conversation.agentKey,
        disabled: true,
        label: (
          <Space size={4}>
            <span>{conversation.agentKey}</span>
            <Tag bordered={false}>v{conversation.agentVersion}</Tag>
            <Tag>历史版本</Tag>
          </Space>
        )
      })
    }
    return options
  }, [agents.data, conversation])

  const busy =
    streaming ||
    Boolean(activeRunId) ||
    Boolean(currentRun && ACTIVE_RUN_STATUSES.has(currentRun.status))
  const cancelRunId = currentRun?.id ?? activeRunId
  const canSubmit = Boolean(selectedId || targetAgent?.healthy)
  const canCancel =
    Boolean(cancelRunId) &&
    !TERMINAL_RUN_STATUSES.has(currentRun?.status ?? '') &&
    currentRun?.status !== 'CANCEL_REQUESTED'

  useEffect(() => {
    if (!agents.data?.length) return
    if (
      targetAgentKey &&
      agents.data.some((agent) => agent.agentKey === targetAgentKey && agent.healthy)
    ) {
      return
    }
    if (selectedId) return
    const remembered = readLastAgent()
    const candidate =
      agents.data.find((agent) => agent.agentKey === remembered && agent.healthy) ??
      agents.data.find((agent) => agent.healthy) ??
      agents.data[0]
    setTargetAgentKey(candidate.agentKey)
  }, [agents.data, selectedId, targetAgentKey])

  const refreshConversation = () => {
    if (selectedId) {
      void queryClient.invalidateQueries({ queryKey: ['ai-conversation', selectedId] })
    }
    void queryClient.invalidateQueries({ queryKey: ['ai-conversations'] })
  }

  const setActiveRun = (runId: string | null) => {
    activeRunIdRef.current = runId
    setActiveRunId(runId)
  }

  const appendProgress = (text: string, color = 'blue') => {
    setProgress((prev) => [...prev, { id: crypto.randomUUID(), text, color }].slice(-20))
  }

  const eventRunId = (event: AiStreamEvent) => {
    if (event.runId) return event.runId
    const value = event.data.runId
    return typeof value === 'string' ? value : undefined
  }

  const handleEvent = (event: AiStreamEvent) => {
    if (event.cursor > 0) cursorRef.current = event.cursor
    const data = event.data
    const runId = eventRunId(event)
    const active = activeRunIdRef.current
    if (runId && active && runId !== active) return
    if (runId && !active && !['run.queued', 'run.started'].includes(event.type)) {
      return
    }

    switch (event.type) {
      case 'run.queued':
        if (runId) setActiveRun(runId)
        setStreaming(true)
        appendProgress('任务已进入队列')
        refreshConversation()
        break
      case 'run.started':
        if (runId) setActiveRun(runId)
        setStreaming(true)
        setStreamText('')
        setProgress([])
        appendProgress('Agent 开始执行')
        refreshConversation()
        break
      case 'assistant.delta': {
        const delta =
          typeof data.delta === 'string'
            ? data.delta
            : typeof data.text === 'string'
              ? data.text
              : ''
        if (delta) setStreamText((prev) => prev + delta)
        setStreaming(true)
        break
      }
      case 'tool.started': {
        const tool = eventToolLabel(data)
        appendProgress(tool ? `调用工具 ${tool}` : '调用工具')
        setStreaming(true)
        break
      }
      case 'tool.completed': {
        const tool = eventToolLabel(data)
        const state = data.state ? ` · ${String(data.state)}` : ''
        appendProgress(tool ? `工具完成 ${tool}${state}` : `工具完成${state}`, 'green')
        break
      }
      case 'tool.failed': {
        const tool = eventToolLabel(data)
        const error = String(data.message ?? data.errorMessage ?? data.errorCode ?? '工具调用失败')
        appendProgress(tool ? `工具失败 ${tool}` : '工具调用失败', 'red')
        setNotice(error)
        break
      }
      case 'skill.loaded': {
        const skill = String(data.skillKey ?? data.key ?? data.name ?? '')
        const version = data.version ? `@${String(data.version)}` : ''
        appendProgress(skill ? `加载 Skill ${skill}${version}` : '加载 Skill', 'purple')
        break
      }
      case 'run.warning':
        setNotice(String(data.message ?? data.warning ?? '执行受限'))
        appendProgress('执行出现警告', 'orange')
        break
      case 'run.cancel_requested':
        setNotice('正在停止执行；已发出的外部请求不保证远端未执行。')
        refreshConversation()
        break
      case 'run.cancelled':
        setStreaming(false)
        setActiveRun(null)
        setNotice('任务已取消；已发出的外部请求不保证远端未执行。')
        refreshConversation()
        break
      case 'run.failed':
        setStreaming(false)
        setActiveRun(null)
        setNotice(String(data.message ?? data.errorMessage ?? data.errorCode ?? '执行失败'))
        appendProgress('执行失败', 'red')
        refreshConversation()
        break
      case 'run.timed_out':
        setStreaming(false)
        setActiveRun(null)
        setNotice(String(data.message ?? data.errorMessage ?? '执行超时'))
        appendProgress('执行超时', 'red')
        refreshConversation()
        break
      case 'run.completed':
        setStreaming(false)
        setActiveRun(null)
        setStreamText('')
        appendProgress('执行完成', 'green')
        window.setTimeout(refreshConversation, 250)
        break
      case 'stream.ready':
        break
      default:
        if (event.type.startsWith('run.')) refreshConversation()
        break
    }
  }

  useEffect(() => {
    setStreamText('')
    setProgress([])
    setNotice(null)
    setStreaming(false)
    setActiveRun(null)
    cursorRef.current = 0
    streamConversationRef.current = null
    stopStreamRef.current?.()
    stopStreamRef.current = null
    return () => {
      stopStreamRef.current?.()
      stopStreamRef.current = null
      streamConversationRef.current = null
    }
  }, [selectedId])

  useEffect(() => {
    if (!selectedId || !detail.data || streamConversationRef.current === selectedId) {
      return
    }
    setActiveRun(detail.data.currentRun?.id ?? null)
    const initialCursor = detail.data.currentRun ? 0 : detail.data.conversation.eventCursor
    cursorRef.current = initialCursor
    streamConversationRef.current = selectedId
    stopStreamRef.current = openAiEventStream(
      selectedId,
      initialCursor,
      handleEvent,
      undefined,
      (error) => {
        setNotice(error.message)
        if (error.status === 410) refreshConversation()
      }
    )
  }, [detail.data, selectedId])

  const creatingConversation = useRef(false)
  const createConversation = async (agentKey: string) => {
    if (creatingConversation.current) return null
    const agent = agents.data?.find((item) => item.agentKey === agentKey)
    if (!agent) {
      message.error('Agent 不在当前可选目录中')
      return null
    }
    if (!agent.healthy) {
      message.error('该 Agent 当前没有健康运行实例')
      return null
    }
    try {
      creatingConversation.current = true
      const created = await api.aiCreateConversation(agentKey, agent.version)
      rememberAgent(agentKey)
      setSelectedId(created.id)
      void queryClient.invalidateQueries({ queryKey: ['ai-conversations'] })
      return created
    } catch (error) {
      message.error((error as Error).message)
      return null
    } finally {
      creatingConversation.current = false
    }
  }

  const handleAgentChange = (agentKey: string) => {
    if (conversation && selectedId && conversation.agentKey !== agentKey) {
      modal.confirm({
        title: '切换 Agent 将新建会话',
        content: '当前会话已固定 Agent 与发布版本，不会在同一历史中静默切换系统指令或工具权限。',
        okText: '新建会话',
        cancelText: '留在当前会话',
        onOk: async () => {
          const created = await createConversation(agentKey)
          if (created) setTargetAgentKey(agentKey)
        },
        onCancel: () => setTargetAgentKey(conversation.agentKey)
      })
      return
    }
    rememberAgent(agentKey)
    setTargetAgentKey(agentKey)
  }

  const send = async () => {
    const content = input.trim()
    if (!content || busy) return
    try {
      setSubmitting(true)
      let conversationId = selectedId
      if (!conversationId) {
        if (!targetAgentKey) throw new Error('请先选择 Agent')
        const created = await createConversation(targetAgentKey)
        if (!created) return
        conversationId = created.id
      }
      setNotice(null)
      setStreamText('')
      setProgress([])
      setStreaming(true)
      const run = await api.aiSubmitRun(conversationId, content, crypto.randomUUID())
      setActiveRun(run.id)
      setInput('')
      appendProgress('任务已提交')
      window.setTimeout(refreshConversation, 300)
    } catch (error) {
      setStreaming(false)
      message.error((error as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  const cancelRun = async () => {
    if (!cancelRunId) return
    try {
      setCancelling(true)
      const result = await api.aiCancelRun(cancelRunId)
      setNotice(
        result.note ??
          (result.status && TERMINAL_RUN_STATUSES.has(result.status)
            ? `任务状态：${STATUS_LABEL[result.status] ?? result.status}`
            : '已请求停止；等待运行实例确认。')
      )
      window.setTimeout(refreshConversation, 500)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setCancelling(false)
    }
  }

  const messages = detail.data?.messages ?? []

  useEffect(() => {
    const element = messagesRef.current
    if (element) element.scrollTop = element.scrollHeight
  }, [messages, streamText, progress, selectedId])

  const sidebar = (
    <aside className="assistant-sidebar">
      <div className="assistant-panel-head">
        <h2>会话历史</h2>
        <Button
          size="small"
          type="primary"
          icon={<PlusOutlined />}
          disabled={!targetAgent?.healthy}
          onClick={() => {
            const hasChatRequest = Boolean(
              detail.data?.messages.some((item) => item.role === 'user') ||
              detail.data?.conversation.lastMessageAt ||
              detail.data?.currentRun
            )
            if (selectedId) {
              if (!detail.data) {
                message.info('正在读取当前会话，请稍后再试')
                return
              }
              if (!hasChatRequest) {
                message.info('当前会话尚未发起聊天请求，已保留当前会话')
                return
              }
            }
            if (targetAgentKey) void createConversation(targetAgentKey)
          }}
        >
          新建
        </Button>
      </div>
      <div className="assistant-agent-bar">
        <Select
          value={targetAgentKey ?? undefined}
          style={{ width: '100%' }}
          placeholder="选择已发布 Agent"
          loading={agents.isLoading}
          options={currentAgentOptions}
          popupMatchSelectWidth={380}
          onChange={handleAgentChange}
        />
        {targetAgent ? (
          <div className="assistant-agent-meta">
            <Tag color="blue" bordered={false}>
              v{targetAgent.version}
            </Tag>
            <Tag color={targetAgent.healthy ? 'green' : 'red'} bordered={false}>
              {targetAgent.healthy ? '健康' : '无健康实例'}
            </Tag>
            <Text type="secondary">
              {targetAgent.healthy
                ? `${targetAgent.toolCount} 工具 · ${targetAgent.skillCount} 技能`
                : '注册中心未发现该版本健康实例'}
            </Text>
          </div>
        ) : null}
        {agents.error ? (
          <Alert type="error" showIcon message={(agents.error as Error).message} />
        ) : null}
      </div>
      <div className="assistant-history">
        {conversations.isLoading ? (
          <div className="assistant-empty">正在加载会话…</div>
        ) : conversations.data?.content?.length ? (
          conversations.data.content.map((item) => (
            <button
              key={item.id}
              type="button"
              className={`assistant-history-item${item.id === selectedId ? ' active' : ''}`}
              onClick={() => {
                setTargetAgentKey(item.agentKey)
                setSelectedId(item.id)
                setHistoryOpen(false)
              }}
            >
              <span className="title">{item.title}</span>
              <span className="meta">
                <span className="agent">
                  {item.agentKey} · v{item.agentVersion}
                </span>
                <span>{formatDateTime(item.updatedAt)}</span>
              </span>
            </button>
          ))
        ) : (
          <div className="assistant-empty">
            <RobotOutlined style={{ fontSize: 34, color: '#9bb0c5' }} />
            <strong>开始你的第一次对话</strong>
            <span>选择目录中的 Agent 后点击“新建”。会话会固定所选 Agent 的发布版本。</span>
          </div>
        )}
      </div>
    </aside>
  )

  return (
    <div className="assistant-shell">
      {!compact ? sidebar : null}
      <section className="assistant-main">
        <div className="assistant-panel-head">
          {compact ? (
            <Button
              type="text"
              icon={<HistoryOutlined />}
              aria-label="打开会话历史"
              onClick={() => setHistoryOpen(true)}
            />
          ) : null}
          <div className="assistant-heading">
            <h3>{conversation?.title ?? 'AI 助手'}</h3>
            {conversation ? (
              <Space size={[4, 4]} wrap>
                <Tag className="mono" bordered={false}>
                  {conversation.agentKey}
                </Tag>
                <Tag color="blue" bordered={false}>
                  v{conversation.agentVersion}
                </Tag>
                {currentCatalog ? (
                  <>
                    <Tag color={currentCatalog.healthy ? 'green' : 'red'} bordered={false}>
                      {currentCatalog.healthy ? '健康' : '无健康实例'}
                    </Tag>
                    <Text type="secondary">
                      {currentCatalog.toolCount} 工具 · {currentCatalog.skillCount} 技能
                    </Text>
                  </>
                ) : (
                  <Tag color="orange" bordered={false}>
                    固定历史版本
                  </Tag>
                )}
              </Space>
            ) : null}
          </div>
          <Space size={6} wrap>
            {currentRun ? <Tag>{STATUS_LABEL[currentRun.status] ?? currentRun.status}</Tag> : null}
            <Button
              size="small"
              icon={<ReloadOutlined />}
              onClick={refreshConversation}
              disabled={!selectedId}
            >
              刷新
            </Button>
            <Button
              size="small"
              danger
              icon={<StopOutlined />}
              loading={cancelling}
              onClick={cancelRun}
              disabled={!canCancel}
            >
              停止
            </Button>
          </Space>
        </div>

        <div className="assistant-body">
          {notice ? (
            <Alert
              className="assistant-alert"
              type="warning"
              showIcon
              message={notice}
              closable
              onClose={() => setNotice(null)}
            />
          ) : null}
          {detail.error ? (
            <Alert
              className="assistant-alert"
              type="error"
              showIcon
              message={(detail.error as Error).message}
            />
          ) : null}

          <div className="assistant-messages" ref={messagesRef}>
            {messages.length === 0 ? (
              <div className="assistant-empty assistant-welcome">
                <span className="assistant-welcome-icon">
                  <RobotOutlined />
                </span>
                <strong>{conversation ? '今天需要排查什么？' : '从一次智能诊断开始'}</strong>
                <span>
                  {conversation
                    ? '描述你遇到的问题，助手会结合协议知识与设备事实进行分析。'
                    : '选择 Agent，或直接输入问题创建会话。助手只使用当前发布版本授权的工具与 Skill。'}
                </span>
                <div className="assistant-suggestions">
                  {EXAMPLE_PROMPTS.map((example) => (
                    <button
                      type="button"
                      key={example.title}
                      onClick={() => setInput(example.prompt)}
                    >
                      <span>{example.title}</span>
                      <ArrowRightOutlined />
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((item) => <MessageBubble key={item.id} message={item} />)
            )}
            {streamText ? (
              <MessageBubble message={streamPreview(selectedId, streamText)} streaming />
            ) : null}
            {progress.length > 0 && busy ? (
              <div className="assistant-progress">
                {progress.slice(-8).map((step) => (
                  <Tag key={step.id} color={step.color}>
                    {step.text}
                  </Tag>
                ))}
              </div>
            ) : null}
          </div>

          <div className="assistant-composer">
            <div className="assistant-input-row">
              <Input.TextArea
                value={input}
                onChange={(event) => setInput(event.target.value)}
                placeholder="例如：结合协议与设备事实，分析这次离线或停止原因。（Enter 发送，Shift + Enter 换行）"
                autoSize={{ minRows: 2, maxRows: 5 }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault()
                    void send()
                  }
                }}
              />
              <Button
                className="assistant-send"
                type="primary"
                icon={<SendOutlined />}
                loading={submitting}
                disabled={busy || !input.trim() || !canSubmit}
                onClick={send}
              >
                发送
              </Button>
            </div>
            <div className="assistant-composer-footer">
              <Text type="secondary" style={{ fontSize: 12 }}>
                Enter 发送 · Shift + Enter 换行 · 切换 Agent 需新建会话
              </Text>
            </div>
          </div>
        </div>
      </section>
      {compact ? (
        <Drawer
          className="assistant-history-drawer"
          title="会话与 Agent"
          placement="left"
          width={320}
          open={historyOpen}
          onClose={() => setHistoryOpen(false)}
          styles={{ body: { padding: 0 } }}
        >
          {sidebar}
        </Drawer>
      ) : null}
    </div>
  )
}

function streamPreview(conversationId: string | null, text: string): AiMessage {
  return {
    id: 'streaming',
    conversationId: conversationId ?? '',
    role: 'assistant',
    content: text,
    citations: [],
    status: 'STREAMING',
    seq: 0,
    createdAt: new Date().toISOString()
  }
}

function eventToolLabel(data: Record<string, unknown>): string {
  const alias = typeof data.toolAlias === 'string' ? data.toolAlias : ''
  const original = typeof data.originalName === 'string' ? data.originalName : ''
  if (alias && original && original !== alias) return `${alias} (${original})`
  if (alias) return alias
  return String(data.tool ?? data.toolName ?? data.name ?? '')
}

function readLastAgent(): string | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage.getItem(LAST_AGENT_STORAGE_KEY)
  } catch {
    return null
  }
}

function rememberAgent(agentKey: string) {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(LAST_AGENT_STORAGE_KEY, agentKey)
  } catch {
    /* 本地偏好不可用时仍允许正常选择 Agent。 */
  }
}

function MessageBubble({ message, streaming }: { message: AiMessage; streaming?: boolean }) {
  const isUser = message.role === 'user'
  return (
    <div className={`assistant-message${isUser ? ' user' : ''}`}>
      <div className="assistant-message-bubble">
        <div className="assistant-message-author">
          {isUser ? '我' : '助手'}
          {streaming ? ' · 生成中' : ''}
        </div>
        {isUser ? (
          <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
            {message.content}
          </Paragraph>
        ) : (
          <div className="assistant-markdown">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                a: ({ children, href }) => (
                  <a href={href} target="_blank" rel="noreferrer">
                    {children}
                  </a>
                )
              }}
            >
              {message.content}
            </ReactMarkdown>
          </div>
        )}
        {message.citations.length > 0 ? (
          <div className="assistant-citations">
            <Text type="secondary" style={{ fontSize: 12 }}>
              引用
            </Text>
            <ul>
              {message.citations.map((citation, index) => (
                <li key={`${citation.documentId}-${index}`}>
                  {citation.sourceDocumentId && /^\d+$/.test(citation.sourceDocumentId) ? (
                    <a
                      href={`/api/ops/protocol-documents/${citation.sourceDocumentId}/view${
                        citation.pageNo ? `#page=${citation.pageNo}` : ''
                      }`}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {citation.title}
                    </a>
                  ) : (
                    citation.title
                  )}
                  {citation.protocolVersion ? ` · ${citation.protocolVersion}` : ''}
                  {citation.sectionPath ? ` · ${citation.sectionPath}` : ''}
                  {citation.pageNo ? ` · 第 ${citation.pageNo} 页` : ' · 页码不可用'}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
    </div>
  )
}
