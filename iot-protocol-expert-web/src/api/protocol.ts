/** 充电协议统一展示英文名及版本。 */
export function productProtocol(product: {
  protocol: string
  protocolEnglishName?: string | null
  protocolVersion?: string | null
}): string {
  const fallbackName =
    product.protocol === 'YKC16'
      ? 'YKC'
      : product.protocol === 'OCPP16J'
        ? 'OCPP'
        : product.protocol
  const fallbackVersion =
    product.protocol === 'YKC16'
      ? '1.6'
      : product.protocol === 'OCPP16J'
        ? '1.6J'
        : ''
  const name = product.protocolEnglishName?.trim() || fallbackName
  const rawVersion = product.protocolVersion?.trim() || fallbackVersion
  const version =
    name.toUpperCase() === 'OCPP' && rawVersion.toLowerCase() === '1.6j'
      ? '1.6J'
      : rawVersion
  return [name, version].filter(Boolean).join(' ')
}
