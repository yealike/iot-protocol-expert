import { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Row,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tree,
  Typography
} from 'antd'
import type { TreeDataNode } from 'antd'
import {
  DeleteOutlined,
  FileAddOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
  SafetyCertificateOutlined,
  SendOutlined
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../../api/client'
import type {
  AiSkillComponent,
  AiSkillCreate,
  AiSkillFileInfo,
  AiSkillUpdate,
  AiSkillValidation
} from '../../api/types'
import { formatDateTime } from '../../utils/dateTime'
import { enabledTag, ErrorAlert, rememberPublication } from './shared'

const { Text, Paragraph } = Typography

interface SkillMetaForm extends AiSkillCreate {
  enabled?: boolean
}

interface PublishForm {
  version?: string
  idempotencyKey: string
}

export default function SkillsPage() {
  const { message, modal } = App.useApp()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [selectedPath, setSelectedPath] = useState<string | null>(null)
  const [editorText, setEditorText] = useState('')
  const [dirty, setDirty] = useState(false)
  const [metaOpen, setMetaOpen] = useState(false)
  const [editingSkill, setEditingSkill] = useState<AiSkillComponent | null>(null)
  const [metaForm] = Form.useForm<SkillMetaForm>()
  const [fileOpen, setFileOpen] = useState(false)
  const [fileForm] = Form.useForm<{ path: string; content: string }>()
  const [validation, setValidation] = useState<AiSkillValidation | null>(null)
  const [publishOpen, setPublishOpen] = useState(false)
  const [publishForm] = Form.useForm<PublishForm>()

  const skills = useQuery({ queryKey: ['ai-skills'], queryFn: api.aiSkills })
  const selectedSkill = useMemo(
    () => skills.data?.find((skill) => skill.id === selectedId) ?? null,
    [selectedId, skills.data]
  )
  const files = useQuery({
    queryKey: ['ai-skill-files', selectedId],
    queryFn: () => api.aiSkillFiles(selectedId as string),
    enabled: Boolean(selectedId)
  })
  const file = useQuery({
    queryKey: ['ai-skill-file', selectedId, selectedPath],
    queryFn: () => api.aiSkillFile(selectedId as string, selectedPath as string),
    enabled: Boolean(selectedId && selectedPath)
  })

  useEffect(() => {
    if (!selectedId && skills.data?.length) setSelectedId(skills.data[0].id)
    if (selectedId && skills.data && !skills.data.some((skill) => skill.id === selectedId)) {
      setSelectedId(skills.data[0]?.id ?? null)
    }
  }, [selectedId, skills.data])

  useEffect(() => {
    if (!files.data?.length) {
      setSelectedPath(null)
      return
    }
    if (!selectedPath || !files.data.some((item) => item.path === selectedPath)) {
      setSelectedPath(files.data.find((item) => item.entry)?.path ?? files.data[0].path)
    }
  }, [files.data, selectedPath])

  useEffect(() => {
    if (file.data && !dirty) setEditorText(file.data.content)
  }, [file.data, dirty])

  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const refreshSkillData = async (skillId: string) => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['ai-skills'] }),
      queryClient.invalidateQueries({ queryKey: ['ai-skill-files', skillId] }),
      queryClient.invalidateQueries({ queryKey: ['ai-skill-file', skillId] })
    ])
  }

  const saveMeta = useMutation({
    mutationFn: (value: SkillMetaForm) =>
      editingSkill
        ? api.aiUpdateSkill(editingSkill.id, {
            expectedRevision: editingSkill.revision,
            displayName: value.displayName,
            description: value.description,
            enabled: value.enabled ?? editingSkill.enabled
          } satisfies AiSkillUpdate)
        : api.aiCreateSkill(value),
    onSuccess: async (result) => {
      message.success(editingSkill ? 'Skill 信息已保存' : 'Skill 已创建')
      setMetaOpen(false)
      setSelectedId(result.id)
      await queryClient.invalidateQueries({ queryKey: ['ai-skills'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const saveFile = useMutation({
    mutationFn: () => {
      if (!selectedId || !selectedPath || !selectedSkill) throw new Error('请选择 Skill 文件')
      return api.aiSaveSkillFile(selectedId, selectedPath, selectedSkill.revision, editorText)
    },
    onSuccess: async () => {
      setDirty(false)
      message.success('文件已保存')
      if (selectedId) await refreshSkillData(selectedId)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const createFile = useMutation({
    mutationFn: (value: { path: string; content: string }) => {
      if (!selectedId || !selectedSkill) throw new Error('请选择 Skill')
      const pathError = validateSkillPath(
        value.path,
        (files.data ?? []).map((item) => item.path)
      )
      if (pathError) throw new Error(pathError)
      return api.aiSaveSkillFile(selectedId, value.path, selectedSkill.revision, value.content)
    },
    onSuccess: async (result) => {
      message.success('文件已创建')
      setFileOpen(false)
      setDirty(false)
      setEditorText(result.content)
      setSelectedPath(result.path)
      if (selectedId) await refreshSkillData(selectedId)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const deleteFile = useMutation({
    mutationFn: (path: string) => {
      if (!selectedId || !selectedSkill) throw new Error('请选择 Skill')
      return api.aiDeleteSkillFile(selectedId, path, selectedSkill.revision)
    },
    onSuccess: async () => {
      message.success('文件已删除')
      setDirty(false)
      setSelectedPath(null)
      if (selectedId) await refreshSkillData(selectedId)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const deleteSkill = useMutation({
    mutationFn: (id: string) => api.aiDeleteSkill(id),
    onSuccess: async () => {
      message.success('Skill 已删除')
      setSelectedId(null)
      setSelectedPath(null)
      await queryClient.invalidateQueries({ queryKey: ['ai-skills'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const validate = useMutation({
    mutationFn: async () => {
      if (!selectedId) throw new Error('请选择 Skill')
      if (dirty) {
        if (!selectedPath || !selectedSkill) throw new Error('请选择 Skill 文件')
        await api.aiSaveSkillFile(selectedId, selectedPath, selectedSkill.revision, editorText)
        setDirty(false)
        await refreshSkillData(selectedId)
      }
      return api.aiValidateSkill(selectedId)
    },
    onSuccess: (result) => setValidation(result),
    onError: (error: Error) => message.error(error.message)
  })

  const publish = useMutation({
    mutationFn: (value: PublishForm) => {
      if (!selectedSkill) throw new Error('请选择 Skill')
      return api.aiPublishSkill(selectedSkill.id, {
        version: value.version?.trim() || undefined,
        expectedRevision: selectedSkill.revision,
        idempotencyKey: value.idempotencyKey
      })
    },
    onSuccess: async (result, value) => {
      const publicationId = rememberPublication(result, 'SKILL', value.version)
      message.success('Skill 发布已提交')
      setPublishOpen(false)
      await queryClient.invalidateQueries({ queryKey: ['ai-skills'] })
      navigate(`/ai-admin/publications/${publicationId}`)
    },
    onError: (error: Error) => message.error(error.message)
  })

  const openCreate = () => {
    setEditingSkill(null)
    metaForm.resetFields()
    metaForm.setFieldsValue({
      key: '',
      displayName: '',
      description: '',
      enabled: true
    })
    setMetaOpen(true)
  }

  const openEdit = (skill: AiSkillComponent) => {
    setEditingSkill(skill)
    metaForm.resetFields()
    metaForm.setFieldsValue({
      key: skill.key,
      displayName: skill.displayName,
      description: skill.description ?? '',
      enabled: skill.enabled
    })
    setMetaOpen(true)
  }

  const selectPath = (path: string) => {
    if (path === selectedPath) return
    const select = () => {
      setDirty(false)
      setSelectedPath(path)
    }
    if (!dirty) {
      select()
      return
    }
    modal.confirm({
      title: '放弃未保存的修改？',
      content: '切换文件后，当前编辑器中的未保存内容将丢失。',
      okText: '放弃并切换',
      cancelText: '继续编辑',
      onOk: select
    })
  }

  const selectSkill = (id: string) => {
    if (id === selectedId) return
    const select = () => {
      setDirty(false)
      setEditorText('')
      setSelectedPath(null)
      setSelectedId(id)
    }
    if (!dirty) {
      select()
      return
    }
    modal.confirm({
      title: '放弃未保存的修改？',
      content: '切换 Skill 后，当前编辑器中的未保存内容将丢失。',
      okText: '放弃并切换',
      cancelText: '继续编辑',
      onOk: select
    })
  }

  const treeData = useMemo(() => buildFileTree(files.data ?? []), [files.data])

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title="Skill 草稿"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => skills.refetch()}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建 Skill
            </Button>
          </Space>
        }
      >
        <ErrorAlert error={skills.error} />
        <Table<AiSkillComponent>
          rowKey="id"
          loading={skills.isLoading}
          dataSource={skills.data ?? []}
          pagination={{ pageSize: 10, hideOnSinglePage: true }}
          onRow={(row) => ({ onClick: () => selectSkill(row.id) })}
          columns={[
            {
              title: 'Skill',
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text strong>{row.displayName}</Text>
                  <Text type="secondary" className="mono">
                    {row.key}
                  </Text>
                </Space>
              )
            },
            {
              title: '说明',
              dataIndex: 'description',
              ellipsis: true,
              render: (value: string | null) => value || '-'
            },
            {
              title: '已发布',
              dataIndex: 'publishedVersion',
              width: 100,
              render: (value: string | null) =>
                value ? <Tag color="green">{value}</Tag> : <Tag>未发布</Tag>
            },
            { title: '草稿 rev', dataIndex: 'draftRevision', width: 90 },
            {
              title: '状态',
              dataIndex: 'enabled',
              width: 80,
              render: (value: boolean) => enabledTag(value)
            },
            { title: 'revision', dataIndex: 'revision', width: 90 },
            {
              title: '更新时间',
              dataIndex: 'updatedAt',
              width: 170,
              render: (value: string) => formatDateTime(value)
            },
            {
              title: '操作',
              width: 190,
              render: (_, row) => (
                <Space size={0} onClick={(event) => event.stopPropagation()}>
                  <Button type="link" size="small" onClick={() => openEdit(row)}>
                    编辑
                  </Button>
                  <Button type="link" size="small" onClick={() => selectSkill(row.id)}>
                    打开
                  </Button>
                  <Popconfirm
                    title="确认删除该 Skill？已有发布版本或绑定引用时会被拒绝。"
                    onConfirm={() => deleteSkill.mutate(row.id)}
                  >
                    <Button type="link" size="small" danger loading={deleteSkill.isPending}>
                      删除
                    </Button>
                  </Popconfirm>
                </Space>
              )
            }
          ]}
        />
      </Card>

      {selectedSkill ? (
        <Card
          title={
            <Space>
              <span>{selectedSkill.displayName}</span>
              <Tag className="mono">{selectedSkill.key}</Tag>
              <Tag>rev {selectedSkill.revision}</Tag>
              {selectedSkill.publishedVersion ? (
                <Tag color="green">已发布 {selectedSkill.publishedVersion}</Tag>
              ) : (
                <Tag>未发布</Tag>
              )}
            </Space>
          }
          extra={
            <Space wrap>
              <Button size="small" onClick={() => openEdit(selectedSkill)}>
                编辑信息
              </Button>
              <Button
                size="small"
                icon={<SafetyCertificateOutlined />}
                loading={validate.isPending}
                onClick={() => validate.mutate()}
              >
                校验
              </Button>
              <Button
                size="small"
                type="primary"
                icon={<SendOutlined />}
                onClick={() => {
                  if (dirty) {
                    message.warning('请先保存当前文件再发布')
                    return
                  }
                  publishForm.setFieldsValue({
                    version: nextPatchVersion(selectedSkill.publishedVersion),
                    idempotencyKey: crypto.randomUUID()
                  })
                  setPublishOpen(true)
                }}
              >
                发布
              </Button>
            </Space>
          }
        >
          <Row gutter={16}>
            <Col xs={24} lg={7} xl={6}>
              <Card
                size="small"
                title="文件树"
                extra={
                  <Button
                    type="text"
                    size="small"
                    icon={<FileAddOutlined />}
                    onClick={() => {
                      fileForm.resetFields()
                      fileForm.setFieldsValue({ path: 'references/', content: '# 参考资料\n' })
                      setFileOpen(true)
                    }}
                  >
                    新建
                  </Button>
                }
              >
                <ErrorAlert error={files.error} />
                {files.data?.length ? (
                  <Tree
                    blockNode
                    defaultExpandAll
                    treeData={treeData}
                    selectedKeys={selectedPath ? [selectedPath] : []}
                    onSelect={(keys) => {
                      const path = String(keys[0] ?? '')
                      if (path) selectPath(path)
                    }}
                  />
                ) : (
                  <Alert
                    type="info"
                    showIcon
                    message="暂无文件"
                    description="至少创建 SKILL.md 后才可发布。"
                  />
                )}
              </Card>
            </Col>
            <Col xs={24} lg={17} xl={18}>
              <Card
                size="small"
                title={
                  selectedPath ? (
                    <Space>
                      <FileTextOutlined />
                      <span className="mono">{selectedPath}</span>
                      {dirty ? <Tag color="orange">未保存</Tag> : null}
                    </Space>
                  ) : (
                    '文件内容'
                  )
                }
                extra={
                  selectedPath ? (
                    <Space>
                      <Popconfirm
                        title="确认删除该文件？"
                        onConfirm={() => selectedPath && deleteFile.mutate(selectedPath)}
                      >
                        <Button
                          size="small"
                          danger
                          icon={<DeleteOutlined />}
                          loading={deleteFile.isPending}
                        >
                          删除
                        </Button>
                      </Popconfirm>
                      <Button
                        size="small"
                        type="primary"
                        icon={<SaveOutlined />}
                        disabled={!dirty}
                        loading={saveFile.isPending}
                        onClick={() => saveFile.mutate()}
                      >
                        保存
                      </Button>
                    </Space>
                  ) : null
                }
              >
                <ErrorAlert error={file.error} />
                {file.isLoading ? (
                  <div style={{ padding: 48, textAlign: 'center' }}>
                    <Spin tip="正在读取文件…" />
                  </div>
                ) : selectedPath ? (
                  <>
                    <Input.TextArea
                      className="ai-skill-editor"
                      value={editorText}
                      onChange={(event) => {
                        setEditorText(event.target.value)
                        setDirty(true)
                      }}
                      spellCheck={false}
                    />
                    <div className="ai-skill-editor-foot">
                      <Text type="secondary">
                        {new TextEncoder().encode(editorText).length} bytes · 单文件上限 256 KiB
                      </Text>
                      <Text type="secondary">
                        {file.data?.digest ? `digest ${file.data.digest.slice(0, 12)}…` : ''}
                      </Text>
                    </div>
                  </>
                ) : (
                  <Alert type="info" showIcon message="请选择或新建文件" />
                )}
              </Card>
            </Col>
          </Row>
        </Card>
      ) : null}

      <Modal
        title={editingSkill ? '编辑 Skill 信息' : '新建 Skill'}
        open={metaOpen}
        confirmLoading={saveMeta.isPending}
        onCancel={() => setMetaOpen(false)}
        onOk={() => metaForm.submit()}
        destroyOnHidden
      >
        <Form form={metaForm} layout="vertical" onFinish={(value) => saveMeta.mutate(value)}>
          <Form.Item
            name="key"
            label="Skill key"
            rules={[
              { required: true },
              {
                pattern: /^[a-z0-9][a-z0-9-]{1,39}$/,
                message: '只允许 2-40 位小写字母、数字和短横线'
              }
            ]}
          >
            <Input disabled={Boolean(editingSkill)} placeholder="device-offline-check" />
          </Form.Item>
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true }]}>
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} maxLength={1024} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch disabled={!editingSkill} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建 Skill 文件"
        open={fileOpen}
        confirmLoading={createFile.isPending}
        onCancel={() => setFileOpen(false)}
        onOk={() => fileForm.submit()}
        destroyOnHidden
        width={720}
      >
        <Form form={fileForm} layout="vertical" onFinish={(value) => createFile.mutate(value)}>
          <Form.Item
            name="path"
            label="相对路径"
            extra="只允许 SKILL.md 或 references/**/*.md|txt；不得包含 ..、反斜杠、绝对路径或大小写冲突。"
            rules={[
              { required: true },
              {
                validator: (_, value: string) => {
                  const error = validateSkillPath(
                    value,
                    (files.data ?? []).map((item) => item.path)
                  )
                  return error ? Promise.reject(new Error(error)) : Promise.resolve()
                }
              }
            ]}
          >
            <Input className="mono" placeholder="references/field-checklist.md" />
          </Form.Item>
          <Form.Item name="content" label="初始内容" rules={[{ required: true }]}>
            <Input.TextArea className="ai-skill-editor ai-skill-editor-small" spellCheck={false} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="Skill 校验结果"
        open={Boolean(validation)}
        footer={<Button onClick={() => setValidation(null)}>关闭</Button>}
        onCancel={() => setValidation(null)}
        width={760}
      >
        {validation ? <ValidationResult result={validation} /> : null}
      </Modal>

      <Modal
        title="发布 Skill"
        open={publishOpen}
        confirmLoading={publish.isPending}
        onCancel={() => setPublishOpen(false)}
        onOk={() => publishForm.submit()}
        destroyOnHidden
      >
        <Alert
          type="warning"
          showIcon
          message="发布将冻结当前草稿为不可变版本，已发布内容不可覆盖。"
          style={{ marginBottom: 16 }}
        />
        <Form form={publishForm} layout="vertical" onFinish={(value) => publish.mutate(value)}>
          <Form.Item
            name="version"
            label="版本"
            extra="留空由后端递增草稿版本；格式必须为 major.minor.patch。"
          >
            <Input placeholder="1.0.0" />
          </Form.Item>
          <Form.Item name="idempotencyKey" label="幂等键" rules={[{ required: true }]}>
            <Input className="mono" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  )
}

function ValidationResult({ result }: { result: AiSkillValidation }) {
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert
        type={result.valid ? 'success' : 'error'}
        showIcon
        message={result.valid ? '校验通过，可以发布' : '校验未通过'}
        description={`文件 ${result.fileCount} 个，共 ${result.totalBytes} bytes，草稿 revision ${result.draftRevision}`}
      />
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label="名称">{result.metadata?.name || '-'}</Descriptions.Item>
        <Descriptions.Item label="说明">{result.metadata?.description || '-'}</Descriptions.Item>
      </Descriptions>
      {result.errors.length > 0 ? (
        <Alert
          type="error"
          showIcon
          message="错误"
          description={
            <List
              size="small"
              dataSource={result.errors}
              renderItem={(item) => (
                <List.Item>
                  <Space direction="vertical" size={0}>
                    <Text strong>{item.code}</Text>
                    <Paragraph style={{ margin: 0 }}>
                      {item.field ? `${item.field}: ` : ''}
                      {item.message}
                    </Paragraph>
                  </Space>
                </List.Item>
              )}
            />
          }
        />
      ) : null}
      {result.warnings.length > 0 ? (
        <Alert
          type="warning"
          showIcon
          message="警告"
          description={
            <List
              size="small"
              dataSource={result.warnings}
              renderItem={(item) => (
                <List.Item>
                  {item.field ? `${item.field}: ` : ''}
                  {item.message}
                </List.Item>
              )}
            />
          }
        />
      ) : null}
    </Space>
  )
}

function buildFileTree(files: AiSkillFileInfo[]): TreeDataNode[] {
  const roots: TreeDataNode[] = []
  files.forEach((file) => {
    const segments = file.path.split('/')
    let level = roots
    let accumulated = ''
    segments.forEach((segment, index) => {
      accumulated = accumulated ? `${accumulated}/${segment}` : segment
      const isLeaf = index === segments.length - 1
      let node = level.find((item) => item.key === accumulated)
      if (!node) {
        node = {
          key: accumulated,
          title: isLeaf ? (
            <Space size={4}>
              <FileTextOutlined />
              <span>{segment}</span>
              {file.entry ? <Tag color="blue">入口</Tag> : null}
            </Space>
          ) : (
            segment
          ),
          isLeaf,
          selectable: isLeaf,
          children: isLeaf ? undefined : []
        }
        level.push(node)
      }
      if (!isLeaf) level = node.children ?? []
    })
  })
  return roots
}

function validateSkillPath(path: string, existing: string[]): string | null {
  if (!path) return '路径不能为空'
  if (path.length > 512) return '路径不能超过 512 字符'
  if (path.startsWith('/') || path.includes('\\') || path.includes(':')) {
    return '路径必须是不含盘符或反斜杠的相对路径'
  }
  if (path.includes('//')) return '路径不能包含空段'
  const segments = path.split('/')
  if (segments.length > 8) return '路径深度不能超过 8 层'
  if (segments.some((segment) => !segment || segment === '.' || segment === '..')) {
    return '路径不能包含空段、. 或 ..'
  }
  if (path !== 'SKILL.md' && !path.startsWith('references/')) {
    return '只允许 SKILL.md 与 references/**/*.md|txt'
  }
  if (path !== 'SKILL.md' && !/\.(md|txt)$/.test(path)) {
    return '参考资料只允许 .md 或 .txt'
  }
  const folded = new Set<string>()
  for (const segment of segments) {
    const value = segment.toLowerCase()
    if (folded.has(value)) return '路径存在大小写冲突'
    folded.add(value)
  }
  if (existing.some((item) => item.toLowerCase() === path.toLowerCase())) {
    return '该路径已存在'
  }
  return null
}

function nextPatchVersion(current?: string | null): string {
  if (!current || !/^\d+\.\d+\.\d+$/.test(current)) return '1.0.0'
  const [major, minor, patch] = current.split('.')
  return `${major}.${minor}.${Number(patch) + 1}`
}
