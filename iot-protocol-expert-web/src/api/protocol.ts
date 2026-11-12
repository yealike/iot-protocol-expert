/** 展示协议名称及版本，保留后端运行时编码。 */
export function formatProtocol(code: string): string {
  if (code === 'YKC16') return '云快充 · 1.6'
  if (code === 'OCPP16J') return 'OCPP · 1.6j'
  return code
}

export function productProtocol(product: { protocol: string; protocolName?: string | null; protocolNameEn?: string | null; protocolVersion?: string | null }): string {
  return product.protocolName ? `${product.protocolName}（${product.protocolNameEn}） · ${product.protocolVersion}` : formatProtocol(product.protocol)
}
