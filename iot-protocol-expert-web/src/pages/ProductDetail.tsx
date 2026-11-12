import { useQuery } from '@tanstack/react-query'
import { Card, Descriptions, Empty, Skeleton, Table, Tabs, Tag } from 'antd'
import { useParams } from 'react-router-dom'
import { api } from '../api/client'

export default function ProductDetail() {
  const { id } = useParams()
  const productId = Number(id)
  const detail = useQuery({
    queryKey: ['product', productId],
    queryFn: () => api.product(productId),
    enabled: Number.isFinite(productId)
  })

  if (detail.isLoading) return <Skeleton active paragraph={{ rows: 10 }} />
  if (detail.isError || !detail.data) {
    return (
      <Empty description={detail.error instanceof Error ? detail.error.message : '产品不存在'} />
    )
  }

  const { product, metadata, configuration } = detail.data

  return (
    <>
      <div className="page-header">
        <div>
          <h1>{product.name}</h1>
          <p>
            <span className="mono">{product.productCode}</span> · {product.deviceTypeLabel} ·{' '}
            {product.protocol} / {product.transport}
          </p>
        </div>
      </div>

      <Card size="small" bordered={false}>
        <Descriptions column={3} size="small" colon={false}>
          <Descriptions.Item label="厂商">{product.vendor ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="型号">{product.model ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="设备数">{product.deviceCount}</Descriptions.Item>
          <Descriptions.Item label="状态">{product.state}</Descriptions.Item>
          <Descriptions.Item label="描述" span={3}>
            {product.description ?? '—'}
          </Descriptions.Item>
        </Descriptions>
        {Object.keys(configuration).length > 0 ? (
          <pre className="mono" style={{ fontSize: 11, color: '#9fb4cb', overflow: 'auto' }}>
            {JSON.stringify(configuration, null, 2)}
          </pre>
        ) : null}
      </Card>

      <Card bordered={false} style={{ marginTop: 16 }}>
        <Tabs
          items={[
            {
              key: 'properties',
              label: `属性（${metadata.properties.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={metadata.properties}
                  columns={[
                    { title: '属性', dataIndex: 'name' },
                    {
                      title: '标识',
                      dataIndex: 'id',
                      render: (value: string) => <span className="mono">{value}</span>
                    },
                    {
                      title: '类型',
                      dataIndex: 'valueType',
                      render: (type: {
                        type: string
                        unit?: string
                        min?: number
                        max?: number
                        elements?: unknown[]
                      }) => (
                        <span>
                          <Tag bordered={false}>{type.type}</Tag>
                          {type.unit ? <span className="muted"> {type.unit}</span> : null}
                          {type.min !== undefined || type.max !== undefined ? (
                            <span className="muted" style={{ fontSize: 11 }}>
                              {' '}
                              [{type.min ?? '-∞'}, {type.max ?? '+∞'}]
                            </span>
                          ) : null}
                          {type.elements ? (
                            <span className="muted" style={{ fontSize: 11 }}>
                              {' '}
                              {type.elements.length} 枚举
                            </span>
                          ) : null}
                        </span>
                      )
                    },
                    {
                      title: '通道',
                      dataIndex: 'channel',
                      width: 80,
                      render: (value?: boolean) => (value ? '是' : '否')
                    },
                    {
                      title: '读写',
                      dataIndex: 'readOnly',
                      width: 80,
                      render: (value?: boolean) => (value ? '只读' : '可写')
                    },
                    { title: '说明', dataIndex: 'description', ellipsis: true }
                  ]}
                />
              )
            },
            {
              key: 'functions',
              label: `功能（${metadata.functions.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={metadata.functions}
                  columns={[
                    { title: '功能', dataIndex: 'name' },
                    {
                      title: '标识',
                      dataIndex: 'id',
                      render: (value: string) => <span className="mono">{value}</span>
                    },
                    {
                      title: '异步',
                      dataIndex: 'async',
                      width: 80,
                      render: (value: boolean) => (value ? '是' : '否')
                    },
                    {
                      title: '入参',
                      dataIndex: 'inputs',
                      render: (inputs: { id: string; name: string }[]) =>
                        inputs.length
                          ? inputs.map((item) => `${item.name}(${item.id})`).join('、')
                          : '无'
                    }
                  ]}
                />
              )
            },
            {
              key: 'events',
              label: `事件（${metadata.events.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={metadata.events}
                  columns={[
                    { title: '事件', dataIndex: 'name' },
                    {
                      title: '标识',
                      dataIndex: 'id',
                      render: (value: string) => <span className="mono">{value}</span>
                    },
                    { title: '默认级别', dataIndex: 'level', width: 120 },
                    {
                      title: '载荷',
                      dataIndex: 'valueType',
                      render: (type: { type: string; properties?: unknown[] }) => (
                        <span className="muted">
                          {type?.type ?? '—'}
                          {type?.properties ? ` (${type.properties.length} 字段)` : ''}
                        </span>
                      )
                    }
                  ]}
                />
              )
            },
            {
              key: 'tags',
              label: `标签（${metadata.tags.length}）`,
              children: (
                <Table
                  size="small"
                  rowKey="id"
                  pagination={false}
                  dataSource={metadata.tags}
                  columns={[
                    { title: '标签', dataIndex: 'name' },
                    {
                      title: '标识',
                      dataIndex: 'id',
                      render: (value: string) => <span className="mono">{value}</span>
                    },
                    {
                      title: '类型',
                      dataIndex: 'valueType',
                      render: (type: { type: string }) => <Tag bordered={false}>{type?.type}</Tag>
                    }
                  ]}
                />
              )
            },
            {
              key: 'json',
              label: '原始物模型',
              children: (
                <pre
                  className="mono"
                  style={{ fontSize: 11, color: '#9fb4cb', overflow: 'auto', maxHeight: 520 }}
                >
                  {JSON.stringify(metadata, null, 2)}
                </pre>
              )
            }
          ]}
        />
      </Card>
    </>
  )
}
