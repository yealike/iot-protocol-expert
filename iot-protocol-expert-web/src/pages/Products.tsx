import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { App, Button, Card, Empty, Popconfirm, Skeleton, Space, Table, Tag } from 'antd'
import { AppstoreOutlined, PlusOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { productProtocol } from '../api/protocol'
import { api } from '../api/client'
import type { ProductRow } from '../api/types'
import ProductEditor from '../components/ProductEditor'

export default function Products() {
  const [creating, setCreating] = useState(false)
  const { message } = App.useApp()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => api.queryProducts({ size: 100 })
  })
  const rows = products.data?.content ?? []
  const published = rows.filter((row) => row.state === 'PUBLISHED').length
  const deviceCount = rows.reduce((total, row) => total + row.deviceCount, 0)
  const modelItemCount = rows.reduce(
    (total, row) => total + row.propertyCount + row.functionCount + row.eventCount,
    0
  )

  return (
    <div className="products-page">
      <div className="page-header">
        <div>
          <h1>产品与物模型</h1>
          <p>面向充电桩产品统一管理协议模板、设备属性、功能与事件。</p>
        </div>
        {me.data?.permissions?.includes('ops:manage') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            新建产品
          </Button>
        )}
      </div>
      <ProductEditor
        open={creating}
        onClose={() => setCreating(false)}
        onSaved={() => products.refetch()}
      />

      <section className="product-overview-strip" aria-label="产品概况">
        <div className="product-overview-title">
          <span><AppstoreOutlined /></span>
          <div><strong>充电桩产品库</strong><small>CHARGING PRODUCT CATALOG</small></div>
        </div>
        <div className="product-overview-stat"><span>产品总数</span><strong>{products.data?.total ?? '—'}<small> 个</small></strong></div>
        <div className="product-overview-stat"><span>已发布产品</span><strong>{products.data ? published : '—'}<small> 个</small></strong></div>
        <div className="product-overview-stat"><span>关联设备</span><strong>{products.data ? deviceCount : '—'}<small> 台</small></strong></div>
        <div className="product-overview-stat"><span>物模型定义</span><strong>{products.data ? modelItemCount : '—'}<small> 项</small></strong></div>
      </section>

      <Card variant="borderless" className="product-list-card" styles={{ body: { padding: 0 } }}>
        <div className="product-list-heading">
          <div><h2>产品列表</h2><p>选择产品可查看并维护完整物模型定义</p></div>
          <Tag color="blue" bordered={false}>仅支持充电桩</Tag>
        </div>
        {products.isLoading ? (
          <div className="product-table-loading"><Skeleton active paragraph={{ rows: 6 }} /></div>
        ) : products.isError ? (
          <Empty
            description={products.error instanceof Error ? products.error.message : '加载失败'}
          />
        ) : (
          <Table<ProductRow>
            rowKey="id"
            size="middle"
            dataSource={rows}
            pagination={false}
            locale={{ emptyText: '暂无充电桩产品' }}
            columns={[
              {
                title: '产品名称',
                dataIndex: 'name',
                width: 260,
                render: (_: unknown, row) => (
                  <Link to={`/products/${row.id}`} className="product-name-cell">
                    <span className="product-name-icon"><AppstoreOutlined /></span>
                    <span><strong>{row.name}</strong><small className="mono">{row.productCode}</small></span>
                  </Link>
                )
              },
              {
                title: '协议及版本',
                dataIndex: 'protocol',
                width: 180,
                render: (_: string, row) => <span className="protocol-cell">{productProtocol(row)}</span>
              },
              { title: '传输方式', dataIndex: 'transport', width: 110 },
              {
                title: '物模型',
                dataIndex: 'propertyCount',
                width: 260,
                render: (_: unknown, row) => (
                  <div className="model-counts">
                    <span><b>{row.propertyCount}</b> 属性</span>
                    <span><b>{row.functionCount}</b> 功能</span>
                    <span><b>{row.eventCount}</b> 事件</span>
                  </div>
                )
              },
              {
                title: '设备数',
                dataIndex: 'deviceCount',
                width: 100,
                render: (value: number) => <span className="device-count">{value} 台</span>
              },
              {
                title: '状态',
                dataIndex: 'state',
                width: 110,
                render: (state: string) => (
                  <Tag color={state === 'PUBLISHED' ? 'green' : 'gold'} bordered={false}>
                    {state === 'PUBLISHED' ? '已发布' : '草稿'}
                  </Tag>
                )
              },
              {
                title: '操作',
                width: 160,
                fixed: 'right',
                render: (_: unknown, row) => (
                  <Space size={4}>
                    <Link to={`/products/${row.id}`}>查看 / 编辑</Link>
                    {me.data?.permissions?.includes('ops:manage') && (
                      <Popconfirm
                        title="确定删除该产品？"
                        description="已绑定设备的产品不能删除"
                        onConfirm={async () => {
                          try {
                            await api.deleteProduct(row.id)
                            message.success('已删除')
                            products.refetch()
                          } catch (e) {
                            message.error(e instanceof Error ? e.message : '删除失败')
                          }
                        }}
                      >
                        <Button size="small" type="link" danger disabled={row.deviceCount > 0}>
                          删除
                        </Button>
                      </Popconfirm>
                    )}
                  </Space>
                )
              }
            ]}
            scroll={{ x: 1120 }}
          />
        )}
      </Card>
    </div>
  )
}
