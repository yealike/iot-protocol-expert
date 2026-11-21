import type { AiRunUsage, QuotaPolicyState, QuotaSnapshot, QuotaSubject } from '../api/types'

export const MY_QUOTA_KEY = ['my-quota'] as const
export const MY_USAGE_KEY = ['my-usage'] as const

export const quotaModeLabels = {
  TOTAL: '固定额度',
  ON_FIRST_USE_WINDOW: '周期额度'
}

export function formatUnits(value: number | null | undefined) {
  return typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('zh-CN') : '—'
}

export function quotaStatus(snapshot: QuotaSnapshot) {
  if (!snapshot.policies.length) return { label: '未分配', tone: 'warning' }
  if (snapshot.policies.some((policy) => policy.state !== 'ACTIVE' || !policy.effective)) {
    return { label: '额度不可用', tone: 'danger' }
  }
  if (snapshot.availableUnits <= 0) return { label: '额度已用尽', tone: 'danger' }
  if (snapshot.policies.some((policy) => policy.availableUnits / policy.limitUnits <= 0.1)) {
    return { label: '额度偏低', tone: 'warning' }
  }
  return { label: '额度正常', tone: 'normal' }
}

export function policyStatus(policy: QuotaPolicyState) {
  if (policy.state === 'DISABLED') return { label: '已停用', color: 'default' }
  if (policy.state !== 'ACTIVE') {
    return { label: policy.debtUnits > 0 ? '有待补扣额度' : '账户不可用', color: 'error' }
  }
  if (!policy.effective) return { label: '策略不在有效期', color: 'default' }
  if (policy.expiredWindow) return { label: '下次调用开启新周期', color: 'blue' }
  if (policy.availableUnits <= 0) return { label: '已用尽', color: 'error' }
  if (policy.mode === 'ON_FIRST_USE_WINDOW' && policy.activationState !== 'ACTIVE') {
    return { label: '等待首次使用', color: 'blue' }
  }
  return { label: '生效中', color: 'green' }
}

export function formatQuotaSubject(subject: QuotaSubject) {
  return [
    subject.name && subject.name !== subject.code
      ? `${subject.name}（${subject.code}）`
      : subject.code,
    subject.context
  ]
    .filter(Boolean)
    .join(' · ')
}

export function answerTokens(usage?: AiRunUsage | null) {
  if (usage?.modelCalls === 0) return null
  const { inputTokens, outputTokens, totalTokens } = usage ?? {}
  if (
    typeof inputTokens !== 'number' ||
    !Number.isSafeInteger(inputTokens) ||
    inputTokens < 0 ||
    typeof outputTokens !== 'number' ||
    !Number.isSafeInteger(outputTokens) ||
    outputTokens < 0
  )
    return null
  return { inputTokens, outputTokens, totalTokens: totalTokens ?? inputTokens + outputTokens }
}
