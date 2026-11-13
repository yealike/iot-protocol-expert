import { useState } from 'react'
import { Alert, Button, Card, Drawer, Popconfirm, Space, Table, Tag, message } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CurrentUser } from '../api/types'

export default function AiKnowledge({ user }: { user: CurrentUser }) {
  const admin = (user.permissions ?? []).includes('ai:admin')
  const client = useQueryClient()
  const [working, setWorking] = useState(false)
  const [previewId, setPreviewId] = useState<string | null>(null)
  const documents = useQuery({ queryKey: ['ai-knowledge'], queryFn: api.aiKnowledgeDocuments, enabled: admin })
  const preview = useQuery({ queryKey: ['ai-knowledge-preview', previewId], queryFn: () => api.aiKnowledgePreview(previewId as string), enabled: admin && !!previewId })
  const refresh = () => client.invalidateQueries({ queryKey: ['ai-knowledge'] })
  const index = async () => {
    setWorking(true)
    try {
      const result = await api.aiKnowledgeIndex()
      message.info(`共 ${result.total} 份，处理 ${result.indexed} 份，跳过 ${result.skipped} 份，失败 ${result.failed} 份`)
      await refresh()
    } catch (error) { message.error((error as Error).message) } finally { setWorking(false) }
  }
  const action = async (id: string, operation: 'publish' | 'archive') => {
    try { await api.aiKnowledgeAction(id, operation); await refresh(); message.success('操作成功') }
    catch (error) { message.error((error as Error).message) }
  }
  if (!admin) return <Alert type="error" message="无权访问知识管理" />
  return <Card title="AI 协议知识管理" extra={<Button type="primary" loading={working} onClick={index}>建立待审核索引</Button>}>
    <Alert type="info" showIcon message="从运营平台协议文档建立索引。请检查解析质量后再发布。" style={{ marginBottom: 16 }} />
    {documents.error && <Alert type="error" message={(documents.error as Error).message} />}
    <Table rowKey="id" loading={documents.isLoading} dataSource={documents.data ?? []} pagination={{ pageSize: 15 }} columns={[
      { title: '文档', dataIndex: 'title' },
      { title: '协议', dataIndex: 'protocolCode' },
      { title: '版本', dataIndex: 'protocolVersion' },
      { title: '片段', dataIndex: 'chunkCount' },
      { title: '状态', dataIndex: 'status', render: (status: string) => <Tag>{status}</Tag> },
      { title: '操作', render: (_, row) => <Space>
        <Button size="small" onClick={() => setPreviewId(row.id)}>查看片段</Button>
        <Popconfirm title="确认该索引已审核并发布？" onConfirm={() => action(row.id, 'publish')}><Button size="small" disabled={row.status !== 'READY_FOR_REVIEW'}>发布</Button></Popconfirm>
        <Popconfirm title="确认下线该索引？" onConfirm={() => action(row.id, 'archive')}><Button size="small" disabled={row.status === 'ARCHIVED'}>下线</Button></Popconfirm>
      </Space> }
    ]} />
    <Drawer title="索引片段（最多 50 条）" width={720} open={!!previewId} onClose={() => setPreviewId(null)}>
      {preview.error && <Alert type="error" message={(preview.error as Error).message} />}
      {(preview.data ?? []).map((chunk) => <Card key={chunk.chunkNo} size="small" title={`片段 ${chunk.chunkNo} · ${chunk.sectionPath || '章节不可用'} · ${chunk.pageNo ? `第 ${chunk.pageNo} 页` : '页码不可用'}`} style={{ marginBottom: 12 }}><div style={{ whiteSpace: 'pre-wrap' }}>{chunk.content}</div></Card>)}
    </Drawer>
  </Card>
}
