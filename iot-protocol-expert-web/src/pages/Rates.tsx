import { useState, type CSSProperties } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App,
  Button,
  Card,
  Drawer,
  Empty,
  Input,
  InputNumber,
  Popconfirm,
  Segmented,
  Space,
  Switch,
  Table,
  Tag
} from 'antd'
import { EditOutlined, PlusOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { RateModelSave, RateModelView } from '../api/types'
import { formatDateTime } from '../utils/dateTime'

const periods = [
  {
    key: 'sharp',
    label: '尖',
    color: '#e85555',
    index: 0,
    energyKey: 'sharpEnergy',
    serviceKey: 'sharpService'
  },
  {
    key: 'peak',
    label: '峰',
    color: '#e69a3a',
    index: 1,
    energyKey: 'peakEnergy',
    serviceKey: 'peakService'
  },
  {
    key: 'flat',
    label: '平',
    color: '#4778d9',
    index: 2,
    energyKey: 'flatEnergy',
    serviceKey: 'flatService'
  },
  {
    key: 'valley',
    label: '谷',
    color: '#2c9c7a',
    index: 3,
    energyKey: 'valleyEnergy',
    serviceKey: 'valleyService'
  }
] as const

type PriceField =
  | 'sharpEnergy'
  | 'sharpService'
  | 'peakEnergy'
  | 'peakService'
  | 'flatEnergy'
  | 'flatService'
  | 'valleyEnergy'
  | 'valleyService'

function defaultSegments(): number[] {
  return Array.from({ length: 48 }, (_, index) => {
    const hour = Math.floor(index / 2)
    if (hour < 6) return 3
    if (hour < 10) return 2
    if (hour < 15) return 1
    if (hour < 18) return 2
    if (hour < 21) return 0
    return 2
  })
}

function blankModel(source?: RateModelView | null): RateModelView {
  return {
    modelNo: '',
    sharpEnergy: source?.sharpEnergy ?? 1.2,
    sharpService: source?.sharpService ?? 0.4,
    peakEnergy: source?.peakEnergy ?? 1,
    peakService: source?.peakService ?? 0.35,
    flatEnergy: source?.flatEnergy ?? 0.8,
    flatService: source?.flatService ?? 0.3,
    valleyEnergy: source?.valleyEnergy ?? 0.5,
    valleyService: source?.valleyService ?? 0.2,
    lossRatio: source?.lossRatio ?? 0,
    segments: source?.segments?.length === 48 ? [...source.segments] : defaultSegments(),
    active: false,
    updatedAt: null
  }
}

function periodForIndex(index: number) {
  return periods.find((period) => period.index === index) ?? periods[2]
}

function priceOf(model: RateModelView, period: (typeof periods)[number]): number {
  return Number(model[period.energyKey]) + Number(model[period.serviceKey])
}

function timeOfSlot(index: number): string {
  return `${String(Math.floor(index / 2)).padStart(2, '0')}:${index % 2 ? '30' : '00'}`
}

export default function Rates() {
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<RateModelView | null>(null)
  const [paintPeriod, setPaintPeriod] = useState<number>(2)
  const [painting, setPainting] = useState(false)
  const rates = useQuery({ queryKey: ['rate-models'], queryFn: api.rateModels })
  const save = useMutation({
    mutationFn: (body: RateModelSave) => api.saveRateModel(body),
    onSuccess: () => {
      message.success('费率模型已保存')
      setEditing(null)
      void queryClient.invalidateQueries({ queryKey: ['rate-models'] })
    },
    onError: (error: Error) => message.error(error.message)
  })

  const rows = rates.data ?? []
  const active = rows.find((row) => row.active)
  const editingActiveModel = Boolean(
    editing?.modelNo && rows.some((row) => row.active && row.modelNo === editing.modelNo)
  )

  const openEditor = (model?: RateModelView) => {
    setEditing(model ? { ...model, segments: [...model.segments] } : blankModel(active))
    setPaintPeriod(2)
    setPainting(false)
  }

  const updateDraft = <Key extends keyof RateModelView>(
    key: Key,
    value: RateModelView[Key]
  ) => {
    setEditing((current) => (current ? { ...current, [key]: value } : current))
  }

  const updatePrice = (field: PriceField, value: number) => {
    setEditing((current) => (current ? { ...current, [field]: value } : current))
  }

  const paintSlot = (index: number) => {
    if (!editing) return
    const segments = [...editing.segments]
    segments[index] = paintPeriod
    updateDraft('segments', segments)
  }

  const saveDraft = () => {
    if (!editing) return
    const modelNo = editing.modelNo.trim()
    if (!/^\d{4}$/.test(modelNo)) {
      message.error('费率模型编号必须为 4 位数字')
      return
    }
    if (editing.segments.length !== 48) {
      message.error('费率时段必须完整覆盖 48 个半小时')
      return
    }
    save.mutate({ ...editing, modelNo })
  }

  const activate = (model: RateModelView) => save.mutate({ ...model, active: true })

  return (
    <div className="rates-page">
      <div className="page-header">
        <div>
          <h1>费率管理</h1>
          <p>维护充电电费、服务费、尖峰平谷时段与计损比例。</p>
        </div>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor()}>
          新建费率
        </Button>
      </div>

      <Card variant="borderless" className="rate-overview-card">
        <div className="rate-overview-heading">
          <div>
            <span className="rate-eyebrow">当前生效模型</span>
            <strong className="mono">{active?.modelNo ?? '未设置'}</strong>
          </div>
          <div className="rate-overview-meta">
            <span>计损比例</span>
            <strong>{active ? `${active.lossRatio}%` : '—'}</strong>
            <small>{formatDateTime(active?.updatedAt, '尚未配置')}</small>
          </div>
        </div>
        <div className="rate-period-grid">
          {periods.map((period) => (
            <div
              className={`rate-period-card period-${period.key}`}
              key={period.key}
              style={{ '--period-color': period.color } as CSSProperties}
            >
              <div>
                <span className="rate-period-dot" />
                <strong>{period.label}时段</strong>
              </div>
              <b>{active ? `¥ ${priceOf(active, period).toFixed(5)}` : '—'}</b>
              <small>电费 + 服务费 / kWh</small>
            </div>
          ))}
        </div>
      </Card>

      <Card variant="borderless" className="data-card" styles={{ body: { padding: 0 } }}>
        <div className="rate-list-heading">
          <div>
            <h2>费率模型</h2>
            <p>每次只允许一个模型生效，48 个时段覆盖完整自然日。</p>
          </div>
          <span>共 {rows.length} 个模型</span>
        </div>
        {rates.isError ? (
          <Alert
            type="error"
            showIcon
            message={rates.error instanceof Error ? rates.error.message : '费率加载失败'}
          />
        ) : (
          <Table<RateModelView>
            rowKey="modelNo"
            loading={rates.isLoading}
            dataSource={rows}
            locale={{ emptyText: <Empty description="暂无费率模型" /> }}
            pagination={false}
            scroll={{ x: 960 }}
            columns={[
              {
                title: '模型编号',
                dataIndex: 'modelNo',
                width: 130,
                render: (value: string) => <span className="mono rate-model-no">{value}</span>
              },
              {
                title: '尖峰平谷',
                width: 340,
                render: (_, row) => (
                  <div className="rate-summary-prices">
                    {periods.map((period) => (
                      <span key={period.key}>
                        <i style={{ background: period.color }} />
                        {period.label} ¥{priceOf(row, period).toFixed(4)}
                      </span>
                    ))}
                  </div>
                )
              },
              {
                title: '计损比例',
                dataIndex: 'lossRatio',
                width: 110,
                align: 'right',
                render: (value: number) => `${value}%`
              },
              {
                title: '状态',
                dataIndex: 'active',
                width: 110,
                render: (value: boolean) => (
                  <Tag color={value ? 'green' : 'default'} bordered={false}>
                    {value ? '已生效' : '未生效'}
                  </Tag>
                )
              },
              {
                title: '更新时间',
                dataIndex: 'updatedAt',
                width: 180,
                render: (value: string | null) => formatDateTime(value)
              },
              {
                title: '操作',
                width: 160,
                fixed: 'right',
                render: (_, row) => (
                  <Space>
                    <Button
                      size="small"
                      icon={<EditOutlined />}
                      onClick={() => openEditor(row)}
                    >
                      编辑
                    </Button>
                    {!row.active ? (
                      <Popconfirm
                        title="设为当前生效费率？"
                        description="新订单将按该模型计费。"
                        onConfirm={() => activate(row)}
                      >
                        <Button size="small" type="link" icon={<ThunderboltOutlined />}>
                          生效
                        </Button>
                      </Popconfirm>
                    ) : null}
                  </Space>
                )
              }
            ]}
          />
        )}
      </Card>

      <Drawer
        title={editing?.modelNo ? `编辑费率模型 ${editing.modelNo}` : '新建费率模型'}
        width="min(960px, 100vw)"
        open={Boolean(editing)}
        onClose={() => !save.isPending && setEditing(null)}
        footer={
          <div className="drawer-footer">
            <Button onClick={() => setEditing(null)}>取消</Button>
            <Button type="primary" loading={save.isPending} onClick={saveDraft}>
              保存费率
            </Button>
          </div>
        }
      >
        {editing ? (
          <div className="rate-editor">
            <div className="rate-editor-basic">
              <label>
                <span>模型编号</span>
                <Input
                  maxLength={4}
                  inputMode="numeric"
                  className="mono"
                  value={editing.modelNo}
                  placeholder="例如 0001"
                  onChange={(event) => updateDraft('modelNo', event.target.value)}
                />
              </label>
              <label>
                <span>计损比例（%）</span>
                <InputNumber
                  min={0}
                  max={100}
                  precision={0}
                  value={editing.lossRatio}
                  onChange={(value) => updateDraft('lossRatio', value ?? 0)}
                />
              </label>
              <label className="rate-active-switch">
                <span>保存后立即生效</span>
                <Switch
                  checked={editing.active}
                  disabled={editingActiveModel}
                  onChange={(checked) => updateDraft('active', checked)}
                />
              </label>
            </div>

            <div className="rate-editor-section-title">
              <div>
                <h3>分时价格</h3>
                <p>电费与服务费均按每 kWh 计价，页面显示两者合计。</p>
              </div>
            </div>
            <div className="rate-price-editor-grid">
              {periods.map((period) => (
                <div
                  className={`rate-price-editor period-${period.key}`}
                  key={period.key}
                  style={{ '--period-color': period.color } as CSSProperties}
                >
                  <div className="rate-price-editor-head">
                    <span>
                      <i style={{ background: period.color }} />
                      {period.label}时段
                    </span>
                    <strong>¥ {priceOf(editing, period).toFixed(5)}</strong>
                  </div>
                  <label>
                    <span>电费 ¥/kWh</span>
                    <InputNumber
                      min={0}
                      precision={5}
                      step={0.00001}
                      value={editing[period.energyKey]}
                      onChange={(value) => updatePrice(period.energyKey, value ?? 0)}
                    />
                  </label>
                  <label>
                    <span>服务费 ¥/kWh</span>
                    <InputNumber
                      min={0}
                      precision={5}
                      step={0.00001}
                      value={editing[period.serviceKey]}
                      onChange={(value) => updatePrice(period.serviceKey, value ?? 0)}
                    />
                  </label>
                </div>
              ))}
            </div>

            <div className="rate-editor-section-title rate-schedule-heading">
              <div>
                <h3>48 个半小时时段</h3>
                <p>选择时段类型后点击格子涂色，也可以拖动连续涂色。</p>
              </div>
              <Space wrap>
                <Segmented
                  value={paintPeriod}
                  onChange={(value) => setPaintPeriod(Number(value))}
                  options={periods.map((period) => ({
                    label: period.label,
                    value: period.index
                  }))}
                />
                <Button
                  onClick={() =>
                    updateDraft('segments', Array.from({ length: 48 }, () => paintPeriod))
                  }
                >
                  填充全天
                </Button>
              </Space>
            </div>
            <div
              className="rate-schedule-grid"
              onPointerLeave={() => setPainting(false)}
              onPointerUp={() => setPainting(false)}
            >
              {editing.segments.map((value, index) => {
                const period = periodForIndex(value)
                return (
                  <button
                    key={index}
                    type="button"
                    className={`rate-slot period-${period.key}`}
                    style={{ '--period-color': period.color } as CSSProperties}
                    onPointerDown={() => {
                      setPainting(true)
                      paintSlot(index)
                    }}
                    onPointerEnter={() => {
                      if (painting) paintSlot(index)
                    }}
                  >
                    <span>{timeOfSlot(index)}</span>
                    <strong>{period.label}</strong>
                  </button>
                )
              })}
            </div>
            <div className="rate-legend">
              {periods.map((period) => (
                <span key={period.key}>
                  <i style={{ background: period.color }} />
                  {period.label}时段
                </span>
              ))}
            </div>
          </div>
        ) : null}
      </Drawer>
    </div>
  )
}
