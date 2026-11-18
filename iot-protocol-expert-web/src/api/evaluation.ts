import { aiRequest } from './client'
export interface Dataset {
  id: string
  datasetKey: string
  name: string
  status: string
  revision: number
}
export interface EvalCase {
  id: string
  caseKey: string
  input: Record<string, unknown>
  expectedOutput?: Record<string, unknown>
  enabled: boolean
  revision: number
  assertions?: Record<string, unknown>
  weight?: number
  tags?: string[]
}
export interface Experiment {
  id: string
  mode: string
  agentKey: string
  agentVersion: string
  status: string
  totalItems: number
  completedItems: number
  failedItems: number
  summary: Record<string, unknown>
  createdAt: string
}
export interface EvalResult {
  id: string
  runId?: string
  traceId?: string
  status: string
  inputSnapshot: Record<string, unknown>
  outputSnapshot: Record<string, unknown>
  durationMs?: number
  inputTokens?: number
  outputTokens?: number
  errorMessage?: string
}
export interface Score {
  id: string
  scope: string
  spanId?: string
  metricKey: string
  scoreType: string
  scoreNumber?: number
  scoreBoolean?: boolean
  scoreLabel?: string
  scoreText?: string
  reason?: string
  evidence?: Record<string, unknown>
  source: string
}
const get = <T>(path: string) => aiRequest<T>('/evaluation' + path)
const post = <T>(path: string, body: unknown) =>
  aiRequest<T>('/evaluation' + path, { method: 'POST', body: JSON.stringify(body) })
export const evaluationApi = {
  cancel: (id: string) => post<void>(`/experiments/${id}/cancel`, {}),
  archive: (id: string) => post<void>(`/datasets/${id}/archive`, {}),
  updateCase: (datasetId: string, id: string, revision: number, value: unknown) =>
    aiRequest<EvalCase>(`/evaluation/datasets/${datasetId}/cases/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ revision, value })
    }),
  datasets: () => get<Dataset[]>('/datasets'),
  createDataset: (body: unknown) => post<Dataset>('/datasets', body),
  cases: (id: string) => get<EvalCase[]>(`/datasets/${id}/cases`),
  addCase: (id: string, body: unknown) => post<EvalCase>(`/datasets/${id}/cases`, body),
  experiments: () => get<Experiment[]>('/experiments'),
  start: (body: unknown) => post<Experiment>('/experiments', body),
  results: (id: string) => get<EvalResult[]>(`/experiments/${id}/results`),
  scores: (id: string) => get<Score[]>(`/results/${id}/scores`),
  score: (id: string, body: unknown) => post<Score>(`/results/${id}/scores`, body),
  trace: (id: string) => get<Record<string, unknown>>(`/results/${id}/trace`)
}
