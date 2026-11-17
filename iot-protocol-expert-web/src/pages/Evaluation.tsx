import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography
} from 'antd'
import { api } from '../api/client'
import TracePanel from './TracePanel'
import type { EvalCase } from '../api/evaluation'
import {
  evaluationApi as ev,
  type Dataset,
  type EvalResult,
  type Experiment
} from '../api/evaluation'

const pretty = (v: unknown) => JSON.stringify(v ?? {}, null, 2)
export default function Evaluation() {
  const { message } = App.useApp()
  const qc = useQueryClient()
  const [dataset, setDataset] = useState<Dataset>()
  const [experiment, setExperiment] = useState<Experiment>()
  const [result, setResult] = useState<EvalResult>()
  const [dialog, setDialog] = useState<'dataset' | 'case' | 'experiment' | 'score'>()
  const [trace, setTrace] = useState<Record<string, unknown>>()
  const [editingCase, setEditingCase] = useState<EvalCase>()
  const [compareIds, setCompareIds] = useState<React.Key[]>([])
  const [busy, setBusy] = useState(false)
  const [form] = Form.useForm()
  const datasets = useQuery({ queryKey: ['eval-datasets'], queryFn: ev.datasets })
  const experiments = useQuery({
    queryKey: ['eval-experiments'],
    queryFn: ev.experiments,
    refetchInterval: 5000
  })
  const cases = useQuery({
    queryKey: ['eval-cases', dataset?.id],
    queryFn: () => ev.cases(dataset!.id),
    enabled: !!dataset
  })
  const results = useQuery({
    queryKey: ['eval-results', experiment?.id],
    queryFn: () => ev.results(experiment!.id),
    enabled: !!experiment,
    refetchInterval: 5000
  })
  const scores = useQuery({
    queryKey: ['eval-scores', result?.id],
    queryFn: () => ev.scores(result!.id),
    enabled: !!result
  })
  const agents = useQuery({ queryKey: ['ai-agent-catalog'], queryFn: api.aiAgentCatalog })
  const models = useQuery({ queryKey: ['ai-models'], queryFn: api.aiModels })
  const open = (kind: typeof dialog) => {
    form.resetFields()
    setEditingCase(undefined)
    setDialog(kind)
  }
  const save = async () => {
    try {
      const v = await form.validateFields()
      setBusy(true)
      if (dialog === 'dataset') await ev.createDataset(v)
      if (dialog === 'case') {
        const value = {
          caseKey: v.caseKey,
          input: { question: v.question },
          expectedOutput: { answer: v.answer ?? '' },
          assertions: { contains: (v.contains ?? '').split('\n').filter(Boolean) },
          enabled: v.enabled !== false,
          weight: v.weight ?? editingCase?.weight ?? 1,
          tags: (v.tags ?? '')
            .split(',')
            .map((t: string) => t.trim())
            .filter(Boolean)
        }
        if (editingCase)
          await ev.updateCase(dataset!.id, editingCase.id, editingCase.revision, value)
        else await ev.addCase(dataset!.id, value)
      }
      if (dialog === 'experiment') {
        const agent = agents.data?.find((a) => a.agentKey === v.agentKey)
        await ev.start({ ...v, agentVersion: agent?.version })
      }
      if (dialog === 'score')
        await ev.score(result!.id, {
          metricKey: v.metricKey,
          scoreType: 'NUMERIC',
          scoreNumber: v.scoreNumber,
          reason: v.reason,
          spanId: v.spanId || null
        })
      setDialog(undefined)
      message.success('已保存')
      await qc.invalidateQueries({
        queryKey: [
          'eval-' +
            (dialog === 'dataset'
              ? 'datasets'
              : dialog === 'case'
                ? 'cases'
                : dialog === 'score'
                  ? 'scores'
                  : 'experiments')
        ]
      })
    } catch (e) {
      if (e instanceof Error) message.error(e.message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <Space direction="vertical" style={{ width: '100%' }} size="large">
      <Typography.Title level={3}>Agent 能力评估</Typography.Title>
      {[datasets.error, experiments.error, cases.error, results.error, scores.error]
        .filter(Boolean)
        .map((e, i) => (
          <Alert key={i} type="error" message={(e as Error).message} />
        ))}
      <Tabs
        items={[
          {
            key: 'runs',
            label: '线上评估与离线实验',
            children: (
              <Card
                extra={
                  <Button type="primary" onClick={() => open('experiment')}>
                    启动实验
                  </Button>
                }
              >
                {compareIds.length > 0 && (
                  <Card title="所选实验指标对比">
                    <Table
                      pagination={false}
                      rowKey="id"
                      dataSource={experiments.data?.filter((e) => compareIds.includes(e.id))}
                      columns={[
                        { title: '实验', dataIndex: 'id' },
                        { title: 'Agent 版本', dataIndex: 'agentVersion' },
                        { title: '指标汇总', render: (_, e) => <pre>{pretty(e.summary)}</pre> }
                      ]}
                    />
                  </Card>
                )}
                <Table
                  rowSelection={{
                    selectedRowKeys: compareIds,
                    onChange: (keys) => setCompareIds(keys.slice(-2))
                  }}
                  rowKey="id"
                  dataSource={experiments.data}
                  loading={experiments.isLoading}
                  columns={[
                    {
                      title: '模式',
                      dataIndex: 'mode',
                      filters: [
                        { text: '线上', value: 'ONLINE' },
                        { text: '离线', value: 'DATASET' }
                      ],
                      onFilter: (v, r) => r.mode === v
                    },
                    { title: 'Agent', render: (_, r) => `${r.agentKey} / ${r.agentVersion}` },
                    { title: '状态', dataIndex: 'status', render: (s) => <Tag>{s}</Tag> },
                    {
                      title: '完成 / 失败 / 总数',
                      render: (_, r) => `${r.completedItems} / ${r.failedItems} / ${r.totalItems}`
                    },
                    { title: '创建时间', dataIndex: 'createdAt' },
                    {
                      title: '操作',
                      render: (_, r) => (
                        <Space>
                          <Button onClick={() => setExperiment(r)}>结果</Button>
                          {r.mode === 'DATASET' && ['QUEUED', 'RUNNING'].includes(r.status) && (
                            <Button
                              onClick={async () => {
                                try {
                                  await ev.cancel(r.id)
                                  await experiments.refetch()
                                } catch (e) {
                                  message.error((e as Error).message)
                                }
                              }}
                            >
                              取消
                            </Button>
                          )}
                        </Space>
                      )
                    }
                  ]}
                />
              </Card>
            )
          },
          {
            key: 'datasets',
            label: '评测数据集',
            children: (
              <Card extra={<Button onClick={() => open('dataset')}>创建数据集</Button>}>
                <Table
                  rowKey="id"
                  dataSource={datasets.data}
                  columns={[
                    { title: '标识', dataIndex: 'datasetKey' },
                    { title: '名称', dataIndex: 'name' },
                    { title: '状态', dataIndex: 'status' },
                    { title: '版本', dataIndex: 'revision' },
                    {
                      title: '操作',
                      render: (_, r) => (
                        <Space>
                          <Button onClick={() => setDataset(r)}>用例</Button>
                          {r.status !== 'ARCHIVED' && (
                            <Button
                              onClick={async () => {
                                try {
                                  await ev.archive(r.id)
                                  await datasets.refetch()
                                } catch (e) {
                                  message.error((e as Error).message)
                                }
                              }}
                            >
                              归档
                            </Button>
                          )}
                        </Space>
                      )
                    }
                  ]}
                />
              </Card>
            )
          }
        ]}
      />
      <Drawer
        width="80%"
        title={dataset?.name}
        open={!!dataset}
        onClose={() => setDataset(undefined)}
        extra={<Button onClick={() => open('case')}>添加用例</Button>}
      >
        <Table
          rowKey="id"
          dataSource={cases.data}
          columns={[
            { title: '标识', dataIndex: 'caseKey' },
            { title: '输入', render: (_, r) => <pre>{pretty(r.input)}</pre> },
            { title: '参考答案', render: (_, r) => <pre>{pretty(r.expectedOutput)}</pre> },
            {
              title: '操作',
              render: (_, r) => (
                <Button
                  onClick={() => {
                    open('case')
                    setEditingCase(r)
                    form.setFieldsValue({
                      caseKey: r.caseKey,
                      question: r.input.question,
                      answer: r.expectedOutput?.answer,
                      contains: ((r.assertions?.contains as string[]) ?? []).join('\n'),
                      enabled: r.enabled,
                      weight: r.weight,
                      tags: r.tags?.join(',')
                    })
                  }}
                >
                  编辑
                </Button>
              )
            }
          ]}
        />
      </Drawer>
      <Drawer
        width="85%"
        title="实验结果"
        open={!!experiment}
        onClose={() => setExperiment(undefined)}
      >
        <Table
          rowKey="id"
          dataSource={results.data}
          columns={[
            { title: '输入', render: (_, r) => String(r.inputSnapshot?.question ?? '') },
            { title: '状态', dataIndex: 'status' },
            { title: '耗时 ms', dataIndex: 'durationMs' },
            {
              title: '输入 / 输出 Token',
              render: (_, r) => `${r.inputTokens ?? '-'} / ${r.outputTokens ?? '-'}`
            },
            {
              title: '详情',
              render: (_, r) => (
                <Button
                  onClick={() => {
                    setTrace(undefined)
                    setResult(r)
                  }}
                >
                  链路与评分
                </Button>
              )
            }
          ]}
        />
      </Drawer>
      <Drawer width="85%" title="链路与评分" open={!!result} onClose={() => setResult(undefined)}>
        <Descriptions
          items={[
            { key: 'run', label: 'Run', children: result?.runId },
            { key: 'trace', label: 'Trace', children: result?.traceId }
          ]}
        />
        {result?.errorMessage && <Alert type="error" message={result.errorMessage} />}
        <Card title="输入与输出">
          <pre style={{ whiteSpace: 'pre-wrap' }}>
            {pretty({ input: result?.inputSnapshot, output: result?.outputSnapshot })}
          </pre>
        </Card>
        <Card title="评分" extra={<Button onClick={() => open('score')}>人工评分</Button>}>
          <Table
            rowKey="id"
            dataSource={scores.data}
            columns={[
              { title: '指标', dataIndex: 'metricKey' },
              { title: '范围', render: (_, r) => r.spanId ?? r.scope },
              {
                title: '分数',
                render: (_, r) =>
                  String(r.scoreNumber ?? r.scoreBoolean ?? r.scoreText ?? r.scoreLabel ?? '')
              },
              { title: '来源', dataIndex: 'source' },
              { title: '理由', dataIndex: 'reason' }
            ]}
          />
        </Card>
        <Card
          title="完整 Trace / Span 属性"
          extra={
            <Button
              onClick={async () => {
                try {
                  setTrace(await ev.trace(result!.id))
                } catch (e) {
                  message.error((e as Error).message)
                }
              }}
            >
              加载链路
            </Button>
          }
        >
          {trace ? (
            <TracePanel
              trace={trace}
              onScore={(spanId) => {
                open('score')
                form.setFieldValue('spanId', spanId)
              }}
            />
          ) : (
            '加载后查看 Span 树，选中节点可直接评分。'
          )}
        </Card>
      </Drawer>
      <Modal
        title={
          { dataset: '创建数据集', case: '添加用例', experiment: '启动实验', score: '人工评分' }[
            dialog ?? 'dataset'
          ]
        }
        open={!!dialog}
        onCancel={() => setDialog(undefined)}
        onOk={() => void save()}
        confirmLoading={busy}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          {dialog === 'dataset' && (
            <>
              <Form.Item
                name="datasetKey"
                label="数据集标识"
                rules={[{ required: true }, { pattern: /^[a-z][a-z0-9_-]{1,99}$/ }]}
              >
                <Input />
              </Form.Item>
              <Form.Item name="name" label="名称" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item name="description" label="说明">
                <Input.TextArea />
              </Form.Item>
            </>
          )}
          {dialog === 'case' && (
            <>
              <Form.Item name="caseKey" label="用例标识" rules={[{ required: true }]}>
                <Input />
              </Form.Item>
              <Form.Item name="question" label="用户提问" rules={[{ required: true }]}>
                <Input.TextArea />
              </Form.Item>
              <Form.Item name="answer" label="参考答案">
                <Input.TextArea />
              </Form.Item>
              <Form.Item name="contains" label="答案必须包含（每行一个）">
                <Input.TextArea />
              </Form.Item>
              <Form.Item name="weight" label="用例权重" initialValue={1}>
                <InputNumber min={0.0001} max={100} />
              </Form.Item>
              <Form.Item name="tags" label="标签（逗号分隔）">
                <Input />
              </Form.Item>
              <Form.Item name="enabled" label="启用" initialValue={true}>
                <Select
                  options={[
                    { value: true, label: '启用' },
                    { value: false, label: '禁用' }
                  ]}
                />
              </Form.Item>
            </>
          )}
          {dialog === 'experiment' && (
            <>
              <Form.Item name="datasetId" label="数据集" rules={[{ required: true }]}>
                <Select
                  options={datasets.data
                    ?.filter((d) => d.status !== 'ARCHIVED')
                    .map((d) => ({ value: d.id, label: d.name }))}
                />
              </Form.Item>
              <Form.Item
                name="agentKey"
                label="Agent（固定当前发布版本）"
                rules={[{ required: true }]}
              >
                <Select
                  options={agents.data?.map((a) => ({
                    value: a.agentKey,
                    label: `${a.name} / ${a.version}`
                  }))}
                />
              </Form.Item>
              <Form.Item name="judgeModelId" label="Judge 模型（可选，会产生模型调用）">
                <Select
                  allowClear
                  options={models.data?.map((m) => ({ value: m.id, label: m.displayName }))}
                />
              </Form.Item>
            </>
          )}
          {dialog === 'score' && (
            <>
              <Form.Item
                name="metricKey"
                label="指标标识"
                initialValue="human_quality"
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
              <Form.Item name="spanId" label="Span ID（空则评整个 Trace）">
                <Input />
              </Form.Item>
              <Form.Item name="scoreNumber" label="评分 0～1" rules={[{ required: true }]}>
                <InputNumber min={0} max={1} step={0.1} />
              </Form.Item>
              <Form.Item name="reason" label="理由">
                <Input.TextArea />
              </Form.Item>
            </>
          )}
        </Form>
      </Modal>
    </Space>
  )
}
