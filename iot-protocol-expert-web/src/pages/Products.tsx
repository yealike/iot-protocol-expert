import { productProtocol } from '../api/protocol'
import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { App, Button, Card, Col, Empty, Popconfirm, Row, Skeleton, Space, Table, Tag } from 'antd'
import ProductEditor from '../components/ProductEditor'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { DeviceTypeDescriptor, ProductRow } from '../api/types'

export default function Products() {
  const [creating, setCreating] = useState(false)
  const { message } = App.useApp()
  const me = useQuery({ queryKey: ['me'], queryFn: api.me })
  const products = useQuery({
    queryKey: ['products'],
    queryFn: () => api.queryProducts({ size: 100 })
  })
  const types = useQuery({ queryKey: ['device-types'], queryFn: api.deviceTypes })

  return (
    <>
      <div className="page-header">
        <div>
          <h1>产品与物模型</h1>
          <p>产品是一类设备的模板：物模型决定它能上报什么、能接受什么指令</p>
        </div>
        {me.data?.roles?.includes('ADMIN') && (
          <Button type="primary" onClick={() => setCreating(true)}>
            新建产品
          </Button>
        )}
      </div>
      <ProductEditor
        open={creating}
        onClose={() => setCreating(false)}
        onSaved={() => products.refetch()}
      />

      <Row gutter={[12, 12]} style={{ marginBottom: 8 }}>
        {(types.data ?? []).map((type: DeviceTypeDescriptor) => (
          <Col xs={12} md={8} xl={6} key={type.deviceType}>
            <Card size="small" bordered={false}>
              <div
                style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
              >
                <span style={{ fontSize: 20 }}>{type.icon}</span>
                <span className="mono" style={{ fontSize: 10, color: '#6d839b' }}>
                  {type.deviceType}
                </span>
              </div>
              <div style={{ fontWeight: 600, marginTop: 6 }}>{type.label}</div>
              <div className="muted" style={{ fontSize: 11 }}>
                {type.total ?? 0} 台设备
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <Card bordered={false} styles={{ body: { padding: 0 } }}>
        {products.isLoading ? (
          <Skeleton active />
        ) : products.isError ? (
          <Empty
            description={products.error instanceof Error ? products.error.message : '加载失败'}
          />
        ) : (
          <Table
            rowKey="id"
            size="middle"
            dataSource={products.data?.content ?? []}
            pagination={false}
            columns={[
              {
                title: '产品',
                dataIndex: 'name',
                render: (_: unknown, row: ProductRow) => (
                  <Link to={`/products/${row.id}`}>
                    <div style={{ fontWeight: 600 }}>{row.name}</div>
                    <span className="mono" style={{ fontSize: 11, color: '#6d839b' }}>
                      {row.productCode}
                    </span>
                  </Link>
                )
              },
              {
                title: '类型',
                dataIndex: 'deviceTypeLabel',
                width: 130,
                render: (label: string) => (
                  <Tag bordered={false} color="cyan">
                    {label}
                  </Tag>
                )
              },
              { title: '协议及版本', dataIndex: 'protocol', width: 150, render: (_: string, row: ProductRow) => productProtocol(row) },
              { title: '传输', dataIndex: 'transport', width: 90 },
              {
                title: '物模型',
                dataIndex: 'propertyCount',
                width: 220,
                render: (_: unknown, row: ProductRow) => (
                  <span className="muted" style={{ fontSize: 12 }}>
                    属性 {row.propertyCount} · 功能 {row.functionCount} · 事件 {row.eventCount}
                  </span>
                )
              },
              { title: '设备数', dataIndex: 'deviceCount', width: 90 },
              {
                title: '操作',
                width: 140,
                render: (_: unknown, row: ProductRow) => (
                  <Space>
                    <Link to={`/products/${row.id}`}>查看 / 编辑</Link>
                    {me.data?.roles?.includes('ADMIN') && (
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
              },
              {
                title: '状态',
                dataIndex: 'state',
                width: 100,
                render: (state: string) => (
                  <Tag color={state === 'PUBLISHED' ? 'green' : 'gold'} bordered={false}>
                    {state === 'PUBLISHED' ? '已发布' : '草稿'}
                  </Tag>
                )
              }
            ]}
            scroll={{ x: 980 }}
          />
        )}
      </Card>
    </>
  )
}
