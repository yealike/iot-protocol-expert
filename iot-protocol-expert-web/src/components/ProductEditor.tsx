import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { App, Button, Form, Input, Modal, Popconfirm, Select, Space, Table, Tabs } from 'antd'
import type { ProductDetail } from '../api/types'
import { api } from '../api/client'

type Section = 'properties' | 'functions' | 'events' | 'tags'
type ModelItem = { id: string; name: string; [key: string]: unknown }
type Model = Record<Section, ModelItem[]>
const sections: { key: Section; label: string; sample: ModelItem }[] = [
  {
    key: 'properties',
    label: '属性',
    sample: {
      id: 'voltage',
      name: '电压',
      valueType: { type: 'float', unit: 'V' },
      channel: true,
      readOnly: true
    }
  },
  {
    key: 'functions',
    label: '功能',
    sample: { id: 'start', name: '启动', async: true, inputs: [] }
  },
  {
    key: 'events',
    label: '事件',
    sample: { id: 'fault', name: '故障', level: 'WARN', valueType: { type: 'string' } }
  },
  {
    key: 'tags',
    label: '标签',
    sample: { id: 'location', name: '位置', valueType: { type: 'string' } }
  }
]
const emptyModel = (): Model => ({ properties: [], functions: [], events: [], tags: [] })
interface Props {
  open: boolean
  detail?: ProductDetail
  onClose: () => void
  onSaved: () => void
}
export default function ProductEditor({ open, detail, onClose, onSaved }: Props) {
  const [form] = Form.useForm()
  const { message } = App.useApp()
  const [model, setModel] = useState<Model>(emptyModel())
  const [section, setSection] = useState<Section>('properties')
  const [editing, setEditing] = useState<{ section: Section; index: number | null } | null>(null)
  const [itemJson, setItemJson] = useState('')
  const [saving, setSaving] = useState(false)
  const docs = useQuery({ queryKey: ['protocol-documents', ''], queryFn: () => api.protocolDocuments(), enabled: open })
  const options = (docs.data ?? []).map((doc) => {
    const name = doc.protocolNameEn.trim().toUpperCase()
    const version = doc.documentVersion.trim().toLowerCase()
    const supported = (name === 'YKC' && version === '1.6') || (name === 'OCPP' && version === '1.6j')
    return { value: doc.id, label: `${doc.protocolCode}（${doc.protocolNameEn}）· ${doc.documentVersion} — ${doc.title}${supported ? '' : '（暂不支持接入）'}`, disabled: !supported, transport: name === 'OCPP' ? 'WS' : 'TCP' }
  })
  const locked = !!detail && detail.product.deviceCount > 0
  useEffect(() => {
    if (!open) return
    const p = detail?.product
    form.resetFields()
    form.setFieldsValue(
      p
        ? { ...p, configurationJson: JSON.stringify(detail.configuration ?? {}, null, 2) }
        : {
            deviceType: 'CHARGER',
            transport: 'TCP',
            state: 'DRAFT',
            configurationJson: '{}'
          }
    )
    const source = detail?.metadata
    setModel(
      source
        ? {
            properties: source.properties as unknown as ModelItem[],
            functions: source.functions as unknown as ModelItem[],
            events: source.events as unknown as ModelItem[],
            tags: source.tags as unknown as ModelItem[]
          }
        : emptyModel()
    )
    setSection('properties')
    setEditing(null)
  }, [open, detail, form])
  const editItem = (key: Section, index: number | null) => {
    const sample = sections.find((s) => s.key === key)!.sample
    setItemJson(JSON.stringify(index === null ? sample : model[key][index], null, 2))
    setEditing({ section: key, index })
  }
  const saveItem = () => {
    if (!editing) return
    try {
      const item = JSON.parse(itemJson) as ModelItem
      if (
        !item ||
        typeof item.id !== 'string' ||
        !item.id.trim() ||
        typeof item.name !== 'string' ||
        !item.name.trim()
      )
        throw new Error('请填写非空 id 和 name')
      const list = [...model[editing.section]]
      if (list.some((v, i) => v.id === item.id && i !== editing.index))
        throw new Error('标识已存在')
      if (editing.index === null) list.push(item)
      else list[editing.index] = item
      setModel({ ...model, [editing.section]: list })
      setEditing(null)
    } catch (e) {
      message.error(e instanceof Error ? e.message : 'JSON 格式不正确')
    }
  }
  const submit = async () => {
    try {
      const values = await form.validateFields()
      let configuration: unknown
      try {
        configuration = JSON.parse(values.configurationJson || '{}')
      } catch {
        message.error('协议配置 JSON 格式不正确')
        return
      }
      setSaving(true)
      await api.saveProduct({ ...values, metadata: model, configuration }, detail?.product.id)
      message.success(detail ? '产品已更新' : '产品已创建')
      onSaved()
      onClose()
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setSaving(false)
    }
  }
  return (
    <>
      <Modal
        title={detail ? `编辑产品 · ${detail.product.name}` : '新建产品'}
        open={open}
        width={860}
        onCancel={onClose}
        onOk={submit}
        confirmLoading={saving}
        destroyOnClose
        okText="保存产品"
      >
        <Form form={form} layout="vertical" className="product-form-grid">
          <Form.Item
            name="productCode"
            label="产品编码"
            rules={[{ required: true, whitespace: true, max: 64 }]}
          >
            <Input disabled={!!detail} />
          </Form.Item>
          <Form.Item
            name="name"
            label="产品名称"
            rules={[{ required: true, whitespace: true, max: 128 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item name="deviceType" label="设备类型" rules={[{ required: true }]}>
            <Select disabled options={[{ value: 'CHARGER', label: '充电桩' }]} />
          </Form.Item>
          <Form.Item name="protocolDocumentId" label="协议及版本" rules={[{ required: true, message: '请选择协议文档' }]} extra={detail && !detail.product.protocolDocumentId ? '原产品尚未关联文档，请选择对应协议版本。' : '引用协议文档的名称与版本，设备继承产品协议。'}>
            <Select
              showSearch
              optionFilterProp="label"
              loading={docs.isLoading}
              placeholder="选择协议文档"
              notFoundContent="请先上传协议文档"
              options={options}
              onChange={(value: number) => form.setFieldValue('transport', options.find((item) => item.value === value)?.transport)}
            />
          </Form.Item>
          <Form.Item name="transport" label="传输方式" rules={[{ required: true }]}>
            <Select disabled options={[{ value: 'TCP', label: 'TCP' }, { value: 'WS', label: 'WebSocket' }]} />
          </Form.Item>
          <Form.Item name="state" label="产品状态">
            <Select
              options={[
                { value: 'DRAFT', label: '草稿' },
                { value: 'PUBLISHED', label: '已发布' }
              ]}
            />
          </Form.Item>
          <Form.Item name="vendor" label="厂商">
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="model" label="型号">
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item name="description" label="产品描述" className="product-form-wide">
            <Input maxLength={512} />
          </Form.Item>
          <Form.Item
            name="configurationJson"
            label="协议配置（JSON）"
            className="product-form-wide"
          >
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
        <div className="model-editor-title">
          <div>
            <strong>物模型定义</strong>
            <p>定义设备属性、功能、事件与标签；标识不可重复。</p>
          </div>
          {locked && <span>已绑定设备，物模型不可直接修改</span>}
        </div>
        <Tabs
          activeKey={section}
          onChange={(v) => setSection(v as Section)}
          items={sections.map((s) => ({
            key: s.key,
            label: `${s.label} (${model[s.key].length})`,
            children: (
              <>
                <Button
                  disabled={locked}
                  onClick={() => editItem(s.key, null)}
                  style={{ marginBottom: 12 }}
                >
                  新增{s.label}
                </Button>
                <Table<ModelItem>
                  rowKey="id"
                  size="small"
                  pagination={false}
                  dataSource={model[s.key]}
                  columns={[
                    { title: '名称', dataIndex: 'name' },
                    { title: '标识', dataIndex: 'id' },
                    {
                      title: '定义',
                      render: (_, r) => (
                        <span className="muted">
                          {JSON.stringify(r.valueType ?? r.inputs ?? '—')}
                        </span>
                      )
                    },
                    {
                      title: '操作',
                      render: (_, r, i) => (
                        <Space>
                          <Button
                            type="link"
                            size="small"
                            disabled={locked}
                            onClick={() => editItem(s.key, i)}
                          >
                            编辑
                          </Button>
                          <Popconfirm
                            title="确定删除该定义？"
                            onConfirm={() =>
                              setModel({
                                ...model,
                                [s.key]: model[s.key].filter((x) => x.id !== r.id)
                              })
                            }
                          >
                            <Button type="link" danger size="small" disabled={locked}>
                              删除
                            </Button>
                          </Popconfirm>
                        </Space>
                      )
                    }
                  ]}
                />
              </>
            )
          }))}
        />
      </Modal>
      <Modal
        title={`${editing?.index === null ? '新增' : '编辑'}物模型项`}
        open={!!editing}
        onCancel={() => setEditing(null)}
        onOk={saveItem}
        okText="保存定义"
      >
        <p className="muted">编辑完整 JSON，字段将由服务端按物模型规则校验。</p>
        <Input.TextArea
          value={itemJson}
          onChange={(e) => setItemJson(e.target.value)}
          rows={12}
          spellCheck={false}
        />
      </Modal>
    </>
  )
}
