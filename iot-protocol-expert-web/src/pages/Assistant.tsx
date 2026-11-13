import { formatDateTime } from '../utils/dateTime'
import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Checkbox, Input, Select, Space, Tag, Typography, message } from 'antd'
import {
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

const { Text, Paragraph } = Typography

const STATUS_LABEL: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  PAUSE_REQUESTED: '正在暂停',
  CANCEL_REQUESTED: '正在停止',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消'
}

/**
 * AI 助手：会话列表 + 流式回答 + 执行进度。
 *
 * 事件流只用于展示进度与增量文本，最终内容以服务端消息为准；断线重连用 cursor 补齐。
 */
export default function Assistant() {
  const queryClient = useQueryClient()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [agentKey, setAgentKey] = useState('team-leader')
  const [input, setInput] = useState('')
  const [streamText, setStreamText] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [priority, setPriority] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [progress, setProgress] = useState<string[]>([])
  const [notice, setNotice] = useState<string | null>(null)
  const cursorRef = useRef(0)
  const messagesRef = useRef<HTMLDivElement | null>(null)

  const agents = useQuery({ queryKey: ['ai-agents'], queryFn: api.aiAgents })
  const conversations = useQuery({
    queryKey: ['ai-conversations'],
    queryFn: () => api.aiConversations()
  })

  useEffect(() => {
    if (agents.data?.length && !agents.data.some((agent) => agent.agentKey === agentKey)) {
      setAgentKey(agents.data[0].agentKey)
    }
  }, [agents.data, agentKey])
  const detail = useQuery({
    queryKey: ['ai-conversation', selectedId],
    queryFn: () => api.aiConversation(selectedId as string),
    enabled: Boolean(selectedId)
  })

  const currentRun = detail.data?.currentRun ?? null
  const busy =
    streaming ||
    currentRun?.status === 'RUNNING' ||
    currentRun?.status === 'PAUSE_REQUESTED' ||
    currentRun?.status === 'CANCEL_REQUESTED'

  const refreshConversation = () => {
    if (selectedId) {
      void queryClient.invalidateQueries({ queryKey: ['ai-conversation', selectedId] })
    }
    void queryClient.invalidateQueries({ queryKey: ['ai-conversations'] })
  }

  useEffect(() => {
    if (!selectedId) {
      return
    }
    cursorRef.current = 0
    setStreamText('')
    setProgress([])
    setNotice(null)
    const stop = openAiEventStream(selectedId, 0, (event) => handleEvent(event))
    return stop
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  const handleEvent = (event: AiStreamEvent) => {
    if (event.cursor > 0) {
      cursorRef.current = event.cursor
    }
    const data = event.data
    switch (event.type) {
      case 'run.started':
        setStreamText('')
        setProgress([])
        setStreaming(true)
        setProgress((prev) => [...prev, '开始执行'])
        refreshConversation()
        break
      case 'run.queued':
      case 'run.pause_requested':
      case 'run.cancel_requested':
        refreshConversation()
        break
      case 'tool.started':
        setStreaming(true)
        setProgress((prev) => [...prev, `调用工具 ${String(data.tool ?? '')}`])
        break
      case 'tool.finished':
        setProgress((prev) => [...prev, `工具返回 ${String(data.state ?? '')}`])
        break
      case 'text.delta':
        setStreamText((prev) => prev + String(data.delta ?? ''))
        break
      case 'run.warning':
        setNotice(String(data.message ?? data.warning ?? '执行受限'))
        break
      case 'run.paused':
        setStreaming(false)
        setNotice('已暂停；继续时会重新执行原问题，已发出的外部请求不保证停止')
        refreshConversation()
        break
      case 'run.cancelled':
        setStreaming(false)
        setNotice('已请求停止；已发出的外部请求不保证远端未执行')
        refreshConversation()
        break
      case 'run.failed':
        setStreaming(false)
        setNotice(String(data.message ?? '执行失败'))
        refreshConversation()
        break
      case 'run.completed':
        setStreaming(false)
        setStreamText('')
        refreshConversation()
        break
      default:
        break
    }
  }

  const createConversation = async () => {
    try {
      const created = await api.aiCreateConversation(agentKey)
      setSelectedId(created.id)
      refreshConversation()
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const send = async () => {
    const question = input.trim()
    if (!question) {
      return
    }
    try {
      setSubmitting(true)
      let conversationId = selectedId
      if (!conversationId) {
        const created = await api.aiCreateConversation(agentKey)
        conversationId = created.id
        setSelectedId(created.id)
      }
      if (!busy) {
        setStreamText('')
        setProgress([])
      }
      setNotice(null)
      setStreaming(true)
      await api.aiSubmitRun(conversationId, question, crypto.randomUUID(), priority ? 200 : 100)
      setInput('')
      setPriority(false)
      setTimeout(refreshConversation, 400)
    } catch (error) {
      setStreaming(false)
      message.error((error as Error).message)
    } finally {
      setSubmitting(false)
    }
  }

  const runAction = async (action: 'pause' | 'resume' | 'cancel') => {
    if (!currentRun) {
      return
    }
    try {
      if (action === 'pause') {
        await api.aiPauseRun(currentRun.id)
      } else if (action === 'resume') {
        await api.aiResumeRun(currentRun.id)
      } else {
        await api.aiCancelRun(currentRun.id)
      }
      setTimeout(refreshConversation, 500)
    } catch (error) {
      message.error((error as Error).message)
    }
  }

  const messages = detail.data?.messages ?? []

  useEffect(() => {
    const element = messagesRef.current
    if (element) {
      element.scrollTop = element.scrollHeight
    }
  }, [messages, streamText, progress, selectedId])

  return (
    <div className="assistant-shell">
      <aside className="assistant-sidebar">
        <div className="assistant-panel-head">
          <h2>会话历史</h2>
          <Space size={8}>
            <Select
              size="small"
              value={agentKey}
              style={{ width: 138 }}
              onChange={setAgentKey}
              popupMatchSelectWidth={false}
              options={(agents.data ?? []).map((agent) => ({
                value: agent.agentKey,
                label: agent.name
              }))}
            />
            <Button
              size="small"
              type="primary"
              icon={<PlusOutlined />}
              onClick={createConversation}
            >
              新建
            </Button>
          </Space>
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
                onClick={() => setSelectedId(item.id)}
              >
                <span className="title">{item.title}</span>
                <span className="meta">
                  <span className="agent">{item.agentKey}</span>
                  <span>{formatDateTime(item.updatedAt)}</span>
                </span>
              </button>
            ))
          ) : (
            <div className="assistant-empty">
              <RobotOutlined style={{ fontSize: 34, color: '#9bb0c5' }} />
              <strong>开始你的第一次对话</strong>
              <span>选择智能体后点击“新建”，即可向运维助手提出订单、设备或协议问题。</span>
            </div>
          )}
        </div>
      </aside>

      <section className="assistant-main">
        <div className="assistant-panel-head">
          <h3>{detail.data?.conversation.title ?? 'AI 助手'}</h3>
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
              onClick={() => runAction('pause')}
              disabled={currentRun?.status !== 'RUNNING'}
            >
              暂停
            </Button>
            <Button
              size="small"
              onClick={() => runAction('resume')}
              disabled={currentRun?.status !== 'PAUSED'}
            >
              继续
            </Button>
            <Button
              size="small"
              danger
              icon={<StopOutlined />}
              onClick={() => runAction('cancel')}
              disabled={!currentRun || currentRun.status === 'CANCEL_REQUESTED'}
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

          <div className="assistant-messages" ref={messagesRef}>
            {messages.length === 0 ? (
              <div className="assistant-empty">
                <RobotOutlined style={{ fontSize: 42, color: '#9bb0c5' }} />
                <strong>选择或新建会话</strong>
                <span>助手会基于服务端工具返回的事实与引用作答，缺失数据会明确标注。</span>
              </div>
            ) : (
              messages.map((item) => <MessageBubble key={item.id} message={item} />)
            )}
            {streamText ? (
              <MessageBubble message={streamPreview(selectedId, streamText)} streaming />
            ) : null}
            {progress.length > 0 && busy ? (
              <div className="assistant-progress">
                {progress.slice(-6).map((step, index) => (
                  <Tag key={`${step}-${index}`} color="blue">
                    {step}
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
                placeholder="例如：订单 X 为什么停止？结束 SOC 多少？协议如何定义该停止原因？（Enter 发送，Shift + Enter 换行）"
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
                onClick={send}
              >
                发送
              </Button>
            </div>
            <div className="assistant-composer-footer">
              <Checkbox checked={priority} onChange={(event) => setPriority(event.target.checked)}>
                优先处理（请求暂停当前任务）
              </Checkbox>
              <Text type="secondary" style={{ fontSize: 12 }}>
                Enter 发送 · Shift + Enter 换行
              </Text>
            </div>
          </div>
        </div>
      </section>
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
                      href={`/api/ops/protocol-documents/${citation.sourceDocumentId}/view${citation.pageNo ? `#page=${citation.pageNo}` : ''}`}
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
