/**
 * 部分局域网部署通过 http://内网IP 访问，不属于浏览器安全上下文，
 * crypto.randomUUID 在此时不可用；用 getRandomValues 生成等价的 v4 UUID 兜底。
 */
if (typeof crypto !== 'undefined' && typeof crypto.randomUUID !== 'function') {
  const bytes = (): Uint8Array => crypto.getRandomValues(new Uint8Array(16))
  const format = (value: Uint8Array): string => {
    value[6] = (value[6] & 0x0f) | 0x40
    value[8] = (value[8] & 0x3f) | 0x80
    const hex = Array.from(value, (byte) => byte.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
  }
  Object.defineProperty(crypto, 'randomUUID', {
    configurable: true,
    writable: true,
    value: () => format(bytes())
  })
}

export {}
