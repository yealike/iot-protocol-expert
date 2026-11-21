import {
  memo,
  useCallback,
  useDeferredValue,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState
} from 'react'
import { Alert, App, Button, Drawer, Grid, Input, Select, Space, Tag, Typography } from 'antd'
import {
  ArrowRightOutlined,
  DownOutlined,
  HistoryOutlined,
  LoadingOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SendOutlined,
  StopOutlined,
  WalletOutlined
} from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api, openAiEventStream } from '../api/client'
import type {
  AiAgentCatalogItem,
  AiConversationDetail,
  AiMessage,
  AiRun,
  AiRunUsage,
  AiStreamEvent
} from '../api/types'
import { formatDateTime } from '../utils/dateTime'
import { answerTokens, formatUnits, MY_QUOTA_KEY, MY_USAGE_KEY, quotaStatus } from '../utils/quota'
import { QuotaOverview } from './Usage'

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
const QUOTA_ERROR_CODES = new Set(['QUOTA_EXHAUSTED', 'QUOTA_TOTAL_EXHAUSTED'])
const RUN_STATUS_ORDER = ['QUEUED', 'DISPATCHING', 'RUNNING', 'CANCEL_REQUESTED']
const ACTIVE_RUN_DESCRIPTION: Record<string, string> = {
  QUEUED: '等待可用的执行资源',
  DISPATCHING: '正在连接 Runtime 并校验 Agent 版本',
  RUNNING: '正在分析问题，等待输出',
  CANCEL_REQUESTED: '正在等待执行服务确认停止'
}
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

type RunProgress = Pick<AiRun, 'id' | 'status' | 'errorMessage' | 'errorCode'> &
  Partial<Pick<AiRun, 'createdAt' | 'usage'>>
type StreamState = 'connecting' | 'open' | 'reconnecting' | 'unavailable'

export default function Assistant() {
  const { message, modal } = App.useApp()
  const queryClient = useQueryClient()
  const screens = Grid.useBreakpoint()
  const compact = screens.md === false
  const [historyOpen, setHistoryOpen] = useState(false)
  const [quotaOpen, setQuotaOpen] = useState(false)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [targetAgentKey, setTargetAgentKey] = useState<string | null>(readLastAgent)
  const [input, setInput] = useState('')
  const [preview, setPreview] = useState<{ runId: string; text: string } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [progress, setProgress] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [liveRun, setLiveRun] = useState<RunProgress | null>(null)
  const [pendingMessage, setPendingMessage] = useState<AiMessage | null>(null)
  const [streamState, setStreamState] = useState<StreamState>('connecting')
  const [streamRevision, setStreamRevision] = useState(0)
  const [following, setFollowing] = useState(true)
  const selectedIdRef = useRef<string | null>(null)
  const streamRunIdRef = useRef<string | null>(null)
  const streamCursorRef = useRef<number | null>(null)
  const resumeFromSnapshotRef = useRef(false)
  const followRef = useRef(true)
  const messagesRef = useRef<HTMLDivElement | null>(null)

  const agents = useQuery({ queryKey: ['ai-agent-catalog'], queryFn: api.aiAgentCatalog })
  const conversations = useQuery({
    queryKey: ['ai-conversations'],
    queryFn: () => api.aiConversations()
  })
  const detail = useQuery({
    queryKey: ['ai-conversation', selectedId],
    queryFn: () => api.aiConversation(selectedId as string),
    enabled: Boolean(selectedId),
    refetchInterval: ({ state }) => {
      const run = preferRun(liveRun, state.data?.currentRun ?? state.data?.latestRun ?? null)
      return run && ACTIVE_RUN_STATUSES.has(run.status) ? 2000 : false
    }
  })

  const targetAgent = useMemo(
    () => agents.data?.find((agent) => agent.agentKey === targetAgentKey) ?? null,
    [agents.data, targetAgentKey]
  )
  const conversation = detail.data?.conversation ?? null
  const snapshotRun = detail.data?.currentRun ?? detail.data?.latestRun ?? null
  const currentRun = preferRun(liveRun, snapshotRun)
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

  const running = Boolean(currentRun && ACTIVE_RUN_STATUSES.has(currentRun.status))
  const busy = submitting || running
  const cancelRunId = running ? currentRun?.id : undefined
  const canSubmit = Boolean(selectedId || targetAgent?.healthy)
  const canCancel = Boolean(cancelRunId) && currentRun?.status !== 'CANCEL_REQUESTED'

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

  const refreshConversation = (id = selectedIdRef.current) => {
    if (id) void queryClient.invalidateQueries({ queryKey: ['ai-conversation', id] })
    void queryClient.invalidateQueries({ queryKey: ['ai-conversations'] })
  }

  const selectConversation = (id: string) => {
    if (id === selectedIdRef.current) return
    selectedIdRef.current = id
    streamRunIdRef.current = null
    streamCursorRef.current = null
    resumeFromSnapshotRef.current = false
    setSelectedId(id)
    setLiveRun(null)
    setPreview(null)
    setPendingMessage(null)
    setProgress(null)
    setNotice(null)
    setStreamState('connecting')
    followRef.current = true
    setFollowing(true)
  }

  const snapshotReady = Boolean(detail.data)
  useEffect(() => {
    if (!selectedId || !snapshotReady) return
    const data = queryClient.getQueryData<AiConversationDetail>(['ai-conversation', selectedId])
    if (!data) return
    const cursor = data.conversation.eventCursor
    const active = data.currentRun
    streamRunIdRef.current = active?.id ?? streamRunIdRef.current
    const after = resumeFromSnapshotRef.current
      ? cursor
      : (streamCursorRef.current ?? (active ? 0 : cursor))
    resumeFromSnapshotRef.current = false
    setStreamState('connecting')

    const refresh = () => {
      void queryClient.invalidateQueries({ queryKey: ['ai-conversation', selectedId] })
    }
    const updateRun = (
      id: string,
      status: string,
      errorCode?: string,
      errorMessage?: string,
      usage?: AiRunUsage
    ) => {
      setLiveRun((previous) =>
        preferRun(previous?.id === id ? previous : null, {
          id,
          status,
          errorCode,
          errorMessage,
          usage,
          createdAt: previous?.id === id ? previous.createdAt : undefined
        })
      )
    }
    const handleEvent = ({ type, data, runId, cursor: eventCursor }: AiStreamEvent) => {
      if (selectedIdRef.current !== selectedId) return
      if (eventCursor > 0) streamCursorRef.current = eventCursor
      if (type === 'stream.ready') {
        refresh()
        return
      }
      const id = runId ?? streamRunIdRef.current
      if (!id) return
      if (type === 'run.queued' && id !== streamRunIdRef.current && eventCursor > cursor) {
        streamRunIdRef.current = id
        setLiveRun(null)
        setPreview(null)
        setProgress(null)
        setNotice(null)
      }
      if (id !== streamRunIdRef.current) return

      switch (type) {
        case 'run.queued':
          updateRun(id, 'QUEUED')
          setPendingMessage((previous) =>
            previous && !previous.runId ? { ...previous, runId: id } : previous
          )
          refresh()
          break
        case 'run.dispatching':
          updateRun(id, 'DISPATCHING')
          break
        case 'run.started':
          updateRun(id, 'RUNNING')
          break
        case 'assistant.delta': {
          const delta = data.delta ?? data.text
          if (typeof delta === 'string' && delta) {
            setPreview((previous) => ({
              runId: id,
              text: (previous?.runId === id ? previous.text : '') + delta
            }))
          }
          break
        }
        case 'tool.started':
          setProgress(`正在调用工具 ${eventToolLabel(data)}`.trim())
          break
        case 'tool.completed':
          setProgress(null)
          break
        case 'tool.failed':
          setProgress(null)
          setNotice(String(data.message ?? data.errorMessage ?? '工具调用失败'))
          break
        case 'skill.loaded':
          setProgress(`已加载技能 ${String(data.skillKey ?? data.key ?? data.name ?? '')}`.trim())
          break
        case 'run.warning':
          setNotice(String(data.message ?? data.warning ?? '执行受限'))
          break
        case 'run.cancel_requested':
          updateRun(id, 'CANCEL_REQUESTED')
          refresh()
          break
        case 'run.completed':
        case 'run.cancelled':
        case 'run.failed':
        case 'run.timed_out':
          updateRun(
            id,
            type === 'run.completed'
              ? 'COMPLETED'
              : type === 'run.cancelled'
                ? 'CANCELLED'
                : 'FAILED',
            typeof data.errorCode === 'string' ? data.errorCode : undefined,
            typeof data.message === 'string'
              ? data.message
              : typeof data.errorMessage === 'string'
                ? data.errorMessage
                : undefined,
            typeof data.usage === 'object' && data.usage !== null
              ? (data.usage as AiRunUsage)
              : undefined
          )
          refresh()
          void queryClient.invalidateQueries({ queryKey: ['ai-conversations'] })
          break
      }
    }

    return openAiEventStream(
      selectedId,
      after,
      handleEvent,
      (state) => {
        if (selectedIdRef.current === selectedId) setStreamState(state)
      },
      (error) => {
        if (selectedIdRef.current !== selectedId) return
        if (error.status === 410) resumeFromSnapshotRef.current = true
        setStreamState('unavailable')
        refresh()
      }
    )
  }, [queryClient, selectedId, snapshotReady, streamRevision])

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
      selectConversation(created.id)
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

  const submitQuestion = async (content: string, { keepDraft = false } = {}) => {
    if (!content || busy || !canSubmit) return
    let conversationId = selectedId
    try {
      setSubmitting(true)
      if (!conversationId) {
        if (!targetAgentKey) throw new Error('请先选择 Agent')
        const created = await createConversation(targetAgentKey)
        if (!created) return
        conversationId = created.id
      }
      setNotice(null)
      setPreview(null)
      setLiveRun(null)
      setProgress(null)
      setFollowing(true)
      followRef.current = true
      const requestId = crypto.randomUUID()
      setPendingMessage({
        id: requestId,
        conversationId,
        role: 'user',
        content,
        citations: [],
        status: 'PENDING',
        seq: 0,
        createdAt: new Date().toISOString()
      })
      const run = await api.aiSubmitRun(conversationId, content, requestId)
      if (selectedIdRef.current === conversationId) {
        streamRunIdRef.current = run.id
        setLiveRun((previous) => ({
          ...(preferRun(previous?.id === run.id ? previous : null, run) ?? run),
          createdAt: run.createdAt
        }))
        setPendingMessage((previous) => (previous ? { ...previous, runId: run.id } : null))
        if (!keepDraft) setInput((draft) => (draft.trim() === content ? '' : draft))
      }
      refreshConversation(conversationId)
    } catch (error) {
      if (conversationId === selectedIdRef.current) setPendingMessage(null)
      message.error((error as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  const send = () => submitQuestion(input.trim())

  const cancelRun = async () => {
    if (!cancelRunId) return
    const conversationId = selectedId
    try {
      setCancelling(true)
      const result = await api.aiCancelRun(cancelRunId)
      if (selectedIdRef.current === conversationId && result.status === 'CANCEL_REQUESTED') {
        setLiveRun({ id: cancelRunId, status: result.status, createdAt: currentRun?.createdAt })
        setNotice(result.note ?? null)
      }
      refreshConversation(conversationId)
    } catch (error) {
      message.error((error as Error).message)
    } finally {
      setCancelling(false)
    }
  }

  const messages = detail.data?.messages
  const pending =
    pendingMessage &&
    !messages?.some(
      (item) => item.role === 'user' && item.runId === pendingMessage.runId && pendingMessage.runId
    )
      ? pendingMessage
      : null
  const visiblePreview =
    preview &&
    preview.runId === currentRun?.id &&
    !messages?.some((item) => item.role === 'assistant' && item.runId === preview.runId)
      ? preview
      : null
  const closedRun =
    !submitting && currentRun && TERMINAL_RUN_STATUSES.has(currentRun.status) ? currentRun : null
  const closedRunId = closedRun?.id
  const quotaFailure = Boolean(closedRun?.errorCode && QUOTA_ERROR_CODES.has(closedRun.errorCode))
  const retryQuestion = quotaFailure && closedRunId
    ? (messages?.find((item) => item.role === 'user' && item.runId === closedRunId)?.content ??
      (snapshotRun?.id === closedRunId ? snapshotRun.question : undefined))
    : undefined

  useEffect(() => {
    if (!closedRunId) return
    void queryClient.invalidateQueries({ queryKey: MY_QUOTA_KEY })
    void queryClient.invalidateQueries({ queryKey: MY_USAGE_KEY })
  }, [closedRunId, queryClient])

  const followLatest = () => {
    followRef.current = true
    setFollowing(true)
    const element = messagesRef.current
    if (element) element.scrollTop = element.scrollHeight
  }

  const scrollIfFollowing = useCallback(() => {
    const element = messagesRef.current
    if (element && followRef.current) element.scrollTop = element.scrollHeight
  }, [])

  useLayoutEffect(scrollIfFollowing, [
    scrollIfFollowing,
    messages,
    pending,
    progress,
    selectedId,
    running,
    closedRun,
    streamState
  ])

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
            <AgentHealth agent={targetAgent} />
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
                selectConversation(item.id)
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
                    <AgentHealth agent={currentCatalog} />
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
            {currentRun && running ? (
              <Tag color="processing">{STATUS_LABEL[currentRun.status]}</Tag>
            ) : null}
            <Button
              size="small"
              icon={<ReloadOutlined />}
              onClick={() => refreshConversation()}
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

          <div
            className="assistant-messages"
            ref={messagesRef}
            onScroll={({ currentTarget }) => {
              const nearBottom =
                currentTarget.scrollHeight - currentTarget.scrollTop - currentTarget.clientHeight <
                72
              followRef.current = nearBottom
              setFollowing(nearBottom)
            }}
          >
            {!messages?.length && !pending && !busy ? (
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
              messages?.map((item) => <MessageBubble key={item.id} message={item} />)
            )}
            {pending ? <MessageBubble message={pending} /> : null}
            {visiblePreview ? (
              <StreamingAnswer
                key={visiblePreview.runId}
                text={visiblePreview.text}
                status={currentRun?.status}
                usage={currentRun?.usage}
                onRender={scrollIfFollowing}
              />
            ) : null}
            {busy ? (
              <RunActivity
                run={running ? currentRun : null}
                progress={progress}
                hasOutput={Boolean(visiblePreview)}
                submittedAt={pending?.createdAt}
              />
            ) : null}
            {closedRun?.status === 'FAILED' ? (
              <Alert
                className="assistant-run-notice"
                type="error"
                showIcon
                message={
                  quotaFailure
                    ? '上一条问题因额度不足未完成'
                    : closedRun.errorCode === 'RUN_TIMED_OUT'
                      ? '任务超时'
                      : '任务执行失败'
                }
                description={
                  quotaFailure ? (
                    <div className="assistant-quota-failure">
                      <div>
                        {retryQuestion
                          ? '额度恢复后，可以在当前会话重试。'
                          : '额度恢复后，可以在当前会话发送新问题。'}
                      </div>
                      <Space wrap size={6}>
                        {retryQuestion ? (
                          <Button
                            type="primary"
                            size="small"
                            onClick={() => void submitQuestion(retryQuestion, { keepDraft: true })}
                          >
                            重试上一条问题
                          </Button>
                        ) : null}
                        <Button type="link" size="small" onClick={() => setQuotaOpen(true)}>
                          查看我的额度
                        </Button>
                      </Space>
                    </div>
                  ) : (
                    (closedRun.errorMessage ?? '请稍后重试，或联系管理员检查运行服务。')
                  )
                }
              />
            ) : null}
            {closedRun?.status === 'CANCELLED' ? (
              <div className="assistant-run-notice" role="status">
                任务已停止
              </div>
            ) : null}
          </div>

          {busy && (streamState === 'reconnecting' || streamState === 'unavailable') ? (
            <div className="assistant-stream-notice" role="status">
              <span>
                {streamState === 'reconnecting'
                  ? '实时连接中断，正在重连…'
                  : '实时更新不可用，任务状态仍会自动刷新。'}
              </span>
              {streamState === 'unavailable' ? (
                <Button
                  size="small"
                  type="link"
                  onClick={() => setStreamRevision((revision) => revision + 1)}
                >
                  重新连接
                </Button>
              ) : null}
            </div>
          ) : null}
          <div className="assistant-composer">
            {!following ? (
              <Button
                className="assistant-latest"
                size="small"
                icon={<DownOutlined />}
                onClick={followLatest}
              >
                回到最新
              </Button>
            ) : null}
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
              <AssistantQuota onOpen={() => setQuotaOpen(true)} />
              <Text type="secondary" style={{ fontSize: 12 }}>
                Enter 发送 · Shift + Enter 换行 · 切换 Agent 需新建会话
              </Text>
            </div>
          </div>
        </div>
      </section>
      <Drawer
        title="我的模型额度"
        placement="right"
        width="min(100%, 600px)"
        open={quotaOpen}
        destroyOnHidden
        onClose={() => setQuotaOpen(false)}
        extra={<Link to="/usage/history">用量明细</Link>}
      >
        <div className="usage-page">
          <QuotaOverview />
        </div>
      </Drawer>
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

function preferRun(live: RunProgress | null, snapshot: RunProgress | null): RunProgress | null {
  if (!live || !snapshot) return live ?? snapshot
  if (live.id !== snapshot.id) return live
  if (TERMINAL_RUN_STATUSES.has(snapshot.status)) return snapshot
  if (TERMINAL_RUN_STATUSES.has(live.status)) return live
  return RUN_STATUS_ORDER.indexOf(snapshot.status) >= RUN_STATUS_ORDER.indexOf(live.status)
    ? snapshot
    : live
}

function AgentHealth({ agent }: { agent: AiAgentCatalogItem }) {
  if (agent.healthy && !agent.healthVerified) return <Tag bordered={false}>已发布</Tag>
  return (
    <Tag color={agent.healthy ? 'green' : 'red'} bordered={false}>
      {agent.healthy ? '健康' : '无健康实例'}
    </Tag>
  )
}

function RunActivity({
  run,
  progress,
  hasOutput,
  submittedAt
}: {
  run: RunProgress | null
  progress: string | null
  hasOutput: boolean
  submittedAt?: string
}) {
  const [now, setNow] = useState(Date.now)
  const since = run?.createdAt ?? submittedAt
  useEffect(() => {
    if (!since) return
    const interval = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(interval)
  }, [since])
  const seconds = since ? Math.max(0, Math.floor((now - new Date(since).getTime()) / 1000)) : 0
  const status = run?.status
  const label = status ? STATUS_LABEL[status] : '正在提交'
  const description =
    status === 'RUNNING'
      ? (progress ?? (hasOutput ? '正在生成回答' : ACTIVE_RUN_DESCRIPTION.RUNNING))
      : status
        ? ACTIVE_RUN_DESCRIPTION[status]
        : '正在提交你的问题'
  return (
    <div className="assistant-run-activity" role="status">
      <LoadingOutlined aria-hidden />
      <div>
        <strong>{label}</strong>
        <span>{description}</span>
      </div>
      {seconds > 0 ? <time aria-hidden>{seconds}s</time> : null}
    </div>
  )
}

function StreamingAnswer({
  text,
  status,
  usage,
  onRender
}: {
  text: string
  status?: string
  usage?: AiRunUsage | null
  onRender: () => void
}) {
  const deferredText = useDeferredValue(text)
  const interrupted = status === 'CANCELLED' || status === 'FAILED'
  const content = status && TERMINAL_RUN_STATUSES.has(status) ? text : deferredText
  useLayoutEffect(onRender, [content, onRender])
  return (
    <div className="assistant-message">
      <div className="assistant-message-bubble">
        <div className="assistant-message-author">{interrupted ? '助手 · 部分输出' : '助手'}</div>
        <AnswerMarkdown>{content}</AnswerMarkdown>
        {status === 'COMPLETED' ? <AnswerTokenCount usage={usage} /> : null}
      </div>
    </div>
  )
}

function AssistantQuota({ onOpen }: { onOpen: () => void }) {
  const quota = useQuery({ queryKey: MY_QUOTA_KEY, queryFn: api.myQuota, refetchInterval: 30_000 })
  const snapshot = quota.isError ? undefined : quota.data
  const status = snapshot ? quotaStatus(snapshot) : undefined
  return (
    <button
      type="button"
      onClick={onOpen}
      className="assistant-quota-link"
      data-tone={status?.tone}
      aria-haspopup="dialog"
      aria-live="polite"
    >
      <WalletOutlined />
      {snapshot
        ? <span>可用额度 <strong>{formatUnits(snapshot.availableUnits)}</strong> Units</span>
        : <span>{quota.isError ? '额度暂不可查' : '正在查询额度'}</span>}
      {status && status.tone !== 'normal' ? <span>· {status.label}</span> : null}
    </button>
  )
}

function AnswerTokenCount({ usage }: { usage?: AiRunUsage | null }) {
  const tokens = answerTokens(usage)
  return (
    <div className="assistant-answer-usage">
      {tokens ? (
        <>
          <strong>消耗 {formatUnits(tokens.totalTokens)} Token</strong>
          <span>输入 {formatUnits(tokens.inputTokens)}</span>
          <span>输出 {formatUnits(tokens.outputTokens)}</span>
        </>
      ) : (
        <span>Token 用量暂无记录</span>
      )}
    </div>
  )
}

function eventToolLabel(data: Record<string, unknown>): string {
  const alias = typeof data.toolAlias === 'string' ? data.toolAlias : ''
  const original = typeof data.originalName === 'string' ? data.originalName : ''
  return original || String(data.tool ?? data.toolName ?? data.name ?? alias)
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

const AnswerMarkdown = memo(function AnswerMarkdown({ children }: { children: string }) {
  return (
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
        {children}
      </ReactMarkdown>
    </div>
  )
})

const MessageBubble = memo(function MessageBubble({ message }: { message: AiMessage }) {
  const isUser = message.role === 'user'
  return (
    <div className={`assistant-message${isUser ? ' user' : ''}`}>
      <div className="assistant-message-bubble">
        <div className="assistant-message-author">{isUser ? '我' : '助手'}</div>
        {isUser ? (
          <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
            {message.content}
          </Paragraph>
        ) : (
          <AnswerMarkdown>{message.content}</AnswerMarkdown>
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
        {!isUser ? <AnswerTokenCount usage={message.usage} /> : null}
      </div>
    </div>
  )
})
