import { formatDateTime } from '../utils/dateTime'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { App, Button, Card, Form, Input, Modal, Popconfirm, Space, Table, Tag, Upload } from 'antd'
import { InboxOutlined, UploadOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { ProtocolDocument } from '../api/types'

export default function ProtocolDocuments() {
  const [protocol, setProtocol] = useState('')
  const [open, setOpen] = useState(false)
  const [file, setFile] = useState<File>()
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()
  const { message } = App.useApp()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const docs = useQuery({
    queryKey: ['protocol-documents', protocol],
    queryFn: () => api.protocolDocuments(protocol)
  })
  const submit = async () => {
    try {
      const values = await form.validateFields()
      if (!file) {
        message.error('请选择文件')
        return
      }
      const data = new FormData()
      data.append('protocolCode', values.protocolCode)
      data.append('protocolNameEn', values.protocolNameEn)
      data.append('documentVersion', values.documentVersion)
      data.append('title', values.title)
      data.append('file', file)
      setSaving(true)
      await api.uploadProtocolDocument(data)
      message.success('文档已上传')
      setOpen(false)
      setFile(undefined)
      form.resetFields()
      docs.refetch()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setSaving(false)
    }
  }
  return (
    <>
      <div className="page-header">
        <div>
          <h1>协议文档</h1>
          <p>按协议管理规范文件，支持在线查看和下载</p>
        </div>
        {me.data?.permissions?.includes('ops:manage') && (
          <Button type="primary" icon={<UploadOutlined />} onClick={() => setOpen(true)}>
            上传文档
          </Button>
        )}
      </div>
      <Card bordered={false}>
        <Space style={{ marginBottom: 16 }}>
          <Input.Search allowClear placeholder="按协议名称筛选" onSearch={setProtocol} style={{ width: 220 }} />
          <Button onClick={() => docs.refetch()}>刷新</Button>
        </Space>
        {docs.isError && (
          <p role="alert">{docs.error instanceof Error ? docs.error.message : '文档加载失败'}</p>
        )}
        <Table<ProtocolDocument>
          rowKey="id"
          loading={docs.isLoading}
          dataSource={docs.data ?? []}
          scroll={{ x: 900 }}
          columns={[
            { title: '文档名称', dataIndex: 'title' },
            {
              title: '协议中文名',
              dataIndex: 'protocolCode',
              render: (v: string) => <Tag color="cyan">{v}</Tag>
            },
            { title: '协议英文名', dataIndex: 'protocolNameEn' },
            { title: '版本号', dataIndex: 'documentVersion' },
            { title: '文件', dataIndex: 'originalFilename', ellipsis: true },
            {
              title: '大小',
              dataIndex: 'fileSize',
              render: (v: number) => `${(v / 1024 / 1024).toFixed(2)} MB`
            },
            { title: '上传人', dataIndex: 'uploadedBy' },
            { title: '上传时间', dataIndex: 'createdAt', render: (value: string | null) => formatDateTime(value) },
            {
              title: '操作',
              render: (_, row) => (
                <Space>
                  <a href={`/api/ops/protocol-documents/${row.id}/view`} target="_blank" rel="noopener noreferrer">查看</a>
                  <a href={`/api/ops/protocol-documents/${row.id}/download`}>下载</a>
                  {me.data?.permissions?.includes('ops:manage') && (
                    <Popconfirm
                      title="归档后将从列表隐藏，确定归档？"
                      onConfirm={async () => {
                        try {
                          await api.archiveProtocolDocument(row.id)
                          message.success('已归档')
                          docs.refetch()
                        } catch (e) {
                          message.error(e instanceof Error ? e.message : '归档失败')
                        }
                      }}
                    >
                      <Button type="link" danger size="small">
                        归档
                      </Button>
                    </Popconfirm>
                  )}
                </Space>
              )
            }
          ]}
        />
      </Card>
      <Modal
        title="上传协议文档"
        open={open}
        onCancel={() => setOpen(false)}
        onOk={submit}
        confirmLoading={saving}
        okText="上传"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="protocolCode" label="协议中文名" rules={[{ required: true, whitespace: true, max: 32 }]}>
            <Input placeholder="例如 云快充、开放充电协议" />
          </Form.Item>
          <Form.Item name="protocolNameEn" label="协议英文名" rules={[{ required: true, whitespace: true, max: 64, pattern: /^[A-Za-z0-9][A-Za-z0-9 ._-]*$/, message: '请输入英文名称，支持字母、数字、空格及 ._-' }]}>
            <Input placeholder="例如 YKC、OCPP" />
          </Form.Item>
          <Form.Item
            name="documentVersion"
            label="版本号"
            rules={[{ required: true, whitespace: true, max: 64 }]}
          >
            <Input placeholder="例如 1.6、1.6j" />
          </Form.Item>
          <Form.Item
            name="title"
            label="文档名称"
            rules={[{ required: true, whitespace: true, max: 256 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item label="文件" required>
            <Upload.Dragger
              accept=".pdf,.md,.txt"
              maxCount={1}
              fileList={file ? [{ uid: 'selected', name: file.name, status: 'done', size: file.size }] : []}
              beforeUpload={(selected) => {
                if (selected.size === 0 || selected.size > 20 * 1024 * 1024) {
                  message.error('文件大小须在 1 字节至 20 MB 之间')
                  return Upload.LIST_IGNORE
                }
                if (!/\.(pdf|md|txt)$/i.test(selected.name)) {
                  message.error('仅支持 PDF、Markdown 和 TXT 文件')
                  return Upload.LIST_IGNORE
                }
                setFile(selected)
                return false
              }}
              onRemove={() => setFile(undefined)}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击选择文件，或拖拽文件到此处</p>
              <p className="ant-upload-hint">支持 PDF、Markdown、TXT，最大 20 MB</p>
            </Upload.Dragger>
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
