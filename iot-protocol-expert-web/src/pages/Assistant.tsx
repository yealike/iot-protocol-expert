import { formatDateTime } from '../utils/dateTime'
import { useEffect, useRef, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Empty,
  Input,
  List,
  Row,
  Select,
  Space,
  Tag,
  Typography,
  message
} from 'antd'
import { PlusOutlined, ReloadOutlined, SendOutlined, StopOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
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
  const busy = streaming || currentRun?.status === 'RUNNING' || currentRun?.status === 'PAUSE_REQUESTED' || currentRun?.status === 'CANCEL_REQUESTED'

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
  return (
    <Row gutter={16} style={{ minHeight: 'calc(100vh - 160px)' }}>
      <Col xs={24} lg={7}>
        <Card
          size="small"
          title="会话"
          extra={
            <Space>
              <Select
                size="small"
                value={agentKey}
                style={{ width: 130 }}
                onChange={setAgentKey}
                options={(agents.data ?? []).map((agent) => ({
                  value: agent.agentKey,
                  label: agent.name
                }))}
              />
              <Button size="small" icon={<PlusOutlined />} onClick={createConversation}>
                新建
              </Button>
            </Space>
          }
          styles={{ body: { maxHeight: 'calc(100vh - 250px)', overflow: 'auto' } }}
        >
          <List
            size="small"
            dataSource={conversations.data?.content ?? []}
            locale={{ emptyText: <Empty description="还没有会话" /> }}
            renderItem={(item) => (
              <List.Item
                onClick={() => setSelectedId(item.id)}
                style={{
                  cursor: 'pointer',
                  background: item.id === selectedId ? 'rgba(40,88,165,0.12)' : undefined,
                  borderRadius: 6,
                  paddingInline: 8
                }}
              >
                <List.Item.Meta
                  title={<Text ellipsis>{item.title}</Text>}
                  description={
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {item.agentKey} · {formatDateTime(item.updatedAt)}
                    </Text>
                  }
                />
              </List.Item>
            )}
          />
        </Card>
      </Col>

      <Col xs={24} lg={17}>
        <Card
          size="small"
          title={detail.data?.conversation.title ?? 'AI 助手'}
          extra={
            <Space>
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
          }
          styles={{ body: { display: 'flex', flexDirection: 'column', gap: 12 } }}
        >
          {notice ? <Alert type="warning" showIcon message={notice} closable onClose={() => setNotice(null)} /> : null}

          <div style={{ minHeight: 320, maxHeight: 'calc(100vh - 420px)', overflow: 'auto' }}>
            {messages.length === 0 ? (
              <Empty description="选择或新建会话后开始提问" />
            ) : (
              messages.map((item) => <MessageBubble key={item.id} message={item} />)
            )}
            {streamText ? <MessageBubble message={streamPreview(selectedId, streamText)} streaming /> : null}
            {progress.length > 0 && busy ? (
              <Space size={4} wrap style={{ marginTop: 8 }}>
                {progress.slice(-6).map((step, index) => (
                  <Tag key={`${step}-${index}`} color="blue">
                    {step}
                  </Tag>
                ))}
              </Space>
            ) : null}
          </div>

          <Input.TextArea
            value={input}
            onChange={(event) => setInput(event.target.value)}
            placeholder="例如：订单 X 为什么停止？结束 SOC 多少？协议如何定义该停止原因？（Ctrl/⌘ + Enter 发送）"
            autoSize={{ minRows: 2, maxRows: 5 }}
            onKeyDown={(event) => {
              if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
                void send()
              }
            }}
          />
          <Space>
            <Button type="primary" icon={<SendOutlined />} loading={submitting} onClick={send}>
              发送
            </Button>
            <Checkbox checked={priority} onChange={(event) => setPriority(event.target.checked)}>优先处理（请求暂停当前任务）</Checkbox>
            <Text type="secondary" style={{ fontSize: 12 }}>
              回答只包含服务端工具返回的事实与引用；缺失数据会明确标注。
            </Text>
          </Space>
        </Card>
      </Col>
    </Row>
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
    <div style={{ display: 'flex', justifyContent: isUser ? 'flex-end' : 'flex-start', marginBottom: 12 }}>
      <div
        style={{
          maxWidth: '86%',
          background: isUser ? 'rgba(40,88,165,0.14)' : 'rgba(255,255,255,0.05)',
          border: '1px solid rgba(255,255,255,0.08)',
          borderRadius: 8,
          padding: '10px 12px'
        }}
      >
        <div style={{ fontSize: 11, opacity: 0.6, marginBottom: 4 }}>
          {isUser ? '我' : '助手'}
          {streaming ? ' · 生成中' : ''}
        </div>
        <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{message.content}</Paragraph>
        {message.citations.length > 0 ? (
          <div style={{ marginTop: 8, borderTop: '1px dashed rgba(255,255,255,0.12)', paddingTop: 6 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              引用
            </Text>
            <ul style={{ margin: '4px 0 0 0', paddingLeft: 18, fontSize: 12 }}>
              {message.citations.map((citation, index) => (
                <li key={`${citation.documentId}-${index}`}>
                  {citation.sourceDocumentId && /^\d+$/.test(citation.sourceDocumentId) ? (
                    <a href={`/api/ops/protocol-documents/${citation.sourceDocumentId}/view${citation.pageNo ? `#page=${citation.pageNo}` : ''}`} target="_blank" rel="noreferrer">{citation.title}</a>
                  ) : citation.title}
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
