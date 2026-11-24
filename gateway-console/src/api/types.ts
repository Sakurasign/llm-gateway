/**
 * 网关 API 类型定义。
 *
 * 这些类型与后端契约一一对应，改动后端时 TypeScript 会在编译期报错，
 * 避免前后端字段漂移。
 */

// ── 通用 ──────────────────────────────────────────────

export interface ApiEnvelope<T> {
  code?: number
  message?: string
  data?: T
}

export interface LoginResponse {
  token: string
  username: string
  role: string
  expiresIn: number
}

// ── 概览 ──────────────────────────────────────────────

export interface StatsOverview {
  requests: number
  errors: number
  input_tokens: number
  output_tokens: number
  cost_micro: number
  avg_ms: number
  successRate: number
}

export interface ModelStat {
  virtual_model: string
  requests: number
  input_tokens: number
  output_tokens: number
  cost_micro: number
}

export interface ChannelStat {
  channel_name: string
  requests: number
  errors: number
  avg_ms: number
  cost_micro: number
}

export interface TimeseriesPoint {
  ts: string
  requests: number
  tokens: number
  cost_micro: number
}

// ── 渠道 / 模型 ───────────────────────────────────────

export type ChannelProtocol = 'ANTHROPIC' | 'OPENAI_CHAT' | 'OPENAI_RESPONSES' | 'GEMINI' | 'WORKBUDDY' | 'CODEX_SUBSCRIPTION'

export interface ChannelInfo {
  id: number
  name: string
  protocol: ChannelProtocol
  baseUrl: string
  priority: number
  weight: number
  maxInflight: number
  timeoutMs: number
  apiKeyInQuery: boolean
  hasCredential: boolean
  passthrough: boolean
  enabled: boolean
}

export interface ModelRouteInfo {
  id: number
  priceRatio: number
  enabled: boolean
  channelId: number
  channelName: string
  upstreamModel: string
  priority: number
  weight: number
  protocol: ChannelProtocol
}

export interface VirtualModelInfo {
  name: string
  routes: ModelRouteInfo[]
}

// ── 日志 ──────────────────────────────────────────────

export interface RelayLogRow {
  account_id?: number | null
  account_name?: string | null
  group_id?: number | null
  group_name?: string | null
  inbound_endpoint?: string | null
  upstream_endpoint?: string | null
  reasoning_effort?: string | null
  billing_mode?: 'METERED' | 'SUBSCRIPTION' | null
  cached_tokens?: number | null
  cache_creation_tokens?: number | null
  uncached_input_tokens?: number | null
  account_cost_micro?: number | null
  reasoning_tokens?: number | null
  retry_count?: number | null
  request_id: string
  virtual_model: string
  upstream_model: string | null
  channel_name: string | null
  protocol_in: string
  protocol_out: string
  gateway_stream: number
  status_code: number
  error_code: string | null
  input_tokens: number
  output_tokens: number
  cost_micro: number
  usage_source: 'UPSTREAM' | 'ESTIMATED'
  ttft_ms: number | null
  total_ms: number | null
  created_at: string
}

export interface RelayLogPage {
  total: number
  page: number
  size: number
  data: RelayLogRow[]
}

export interface LogQuery {
  accountId?: number
  groupId?: number
  from?: string
  to?: string
  modelOrder?: boolean
  ascending?: boolean
  page?: number
  size?: number
  model?: string
  channel?: string
  status?: number | ''
}

// ── Prompt 版本 ───────────────────────────────────────

export interface PromptVersion {
  id: number
  name: string | null
  model: string
  params: string
  messages: string
  tags: string | null
  parent_id: number | null
  created_at: string
  truncated?: boolean
}

export interface SavePromptPayload {
  name?: string
  model: string
  params: Record<string, unknown>
  messages: Array<{ role: string; content: string }>
  tags?: string[]
}

// ── SSE 流式事件（Anthropic 格式）───────────────────────

export interface AnthropicUsage {
  input_tokens?: number
  output_tokens?: number
}

export interface AnthropicSseEvent {
  type: string
  index?: number
  message?: {
    id: string
    model: string
    usage?: AnthropicUsage
  }
  delta?: {
    type?: string
    text?: string
    thinking?: string
  }
  usage?: AnthropicUsage
  error?: {
    type?: string
    message?: string
  }
}

export type ChannelPayload = Omit<ChannelInfo, 'id' | 'hasCredential'> & { apiKey?: string }
export interface RoutePayload {
  virtualModel: string
  upstreamModel: string
  channelId: number
  weight: number
  priceRatio: number
}
export interface UpstreamAccount {
  id: number
  provider: 'WORKBUDDY' | 'CODEX_SUBSCRIPTION' | 'ANTHROPIC'
  name: string
  realm: string
  enabled: boolean
  expiresAt: number
  cooldownUntil: number
  lastError: string | null
  requests: number
  failures: number
  inputTokens: number
  outputTokens: number
  credits: number | null
  inflight: number
  concurrency: number
  proxyId: number | null
  source: string
  schedulable: boolean
  notes: string | null
  lastUsedAt: number | null
  priority: number
  mappedModels: number
}

export interface CodexQuotaWindow {
  durationSeconds: number
  usedPercent: number | null
  resetsAt: number | null
}

export interface AccountUsageRow {
  day: string
  model: string
  requests: number
  inputTokens: number
  outputTokens: number
  costMicro: number
  durationMs: number
  timedRequests: number
}

export interface AccountUsageStats {
  today: string
  rows: AccountUsageRow[]
}

export interface CodexAccountStatus {
  planType: string | null
  subscriptionStartedAt: number | null
  subscriptionExpiresAt: number | null
  windows: CodexQuotaWindow[]
  resetCredits: {
    availableCount: number | null
    options: { id: string; label: string; description: string | null; expiresAt: number }[]
    error: string | null
  }
  subscriptionError: string | null
  usageError: string | null
  checkedAt: number
}

export interface AccountMapping {
  model: string
  upstreamModel: string
}

export type AccountRoutingMode = 'ORDER' | 'SMART' | 'ROTATE'

export interface AccountGroup {
  id: number
  name: string
  description: string | null
  provider: UpstreamAccount['provider']
  automatic: boolean
  enabled: boolean
  sortOrder: number
  accountIds: number[]
  activeAccounts: number
  keyCount: number
  preferredAccountId: number | null
  accountOrder: number[]
  routingMode: AccountRoutingMode
}
export type AccountGroupInput = Pick<AccountGroup, 'name' | 'provider' | 'enabled' | 'sortOrder' | 'accountIds' | 'routingMode'> & { description: string }

export interface AccountProxy {
  id: number
  name: string
  protocol: 'http' | 'socks5'
  host: string
  port: number
  username: string | null
  hasPassword: boolean
  accounts: number
}
export type AccountProxyInput = Pick<AccountProxy, 'name' | 'protocol' | 'host' | 'port' | 'username'> & { password?: string }

export interface AccountOptions {
  notes: string
  schedulable: boolean
  priority: number
  concurrency: number
  groupIds: number[]
  proxyId: number
}

export interface KeySpend {
  total: number
  fiveHours: number
  oneDay: number
  sevenDays: number
}

export interface GatewayKey {
  id: number
  name: string
  prefix: string
  accountGroup: UpstreamAccount['provider'] | null
  accountId: number | null
  groupId: number | null
  groupIds: number[]
  enabled: boolean
  groupAvailable: boolean
  ipAllow: string[]
  ipDeny: string[]
  createdAt: number
  expiresAt: number | null
  lastUsedAt: number | null
  lastUsedIp: string | null
  rpmLimit: number | null
  spendLimits: KeySpend
  spendUsed: KeySpend
}

export type GatewayKeyInput = Pick<GatewayKey, 'name' | 'enabled' | 'ipAllow' | 'ipDeny'> & {
  groupIds: number[]
  customKey?: string
  expiresAt: number
  rpmLimit: number
  spendLimits: KeySpend
}

export interface CodexWakeupInput {
  name: string
  enabled: boolean
  accountIds: number[]
  model: string
  prompt: string
  effort: string
  schedule: {
    kind: 'daily' | 'weekly' | 'interval' | 'quota_reset' | 'startup'
    time?: string
    days?: number[]
    hours?: number
    window?: 'either' | '5h' | 'week'
    delay?: number
  }
}

export interface CodexWakeupTask {
  id: string
  input: CodexWakeupInput
  nextRunAt: number | null
  running: boolean
}

export interface CodexWakeupOverview {
  tasks: CodexWakeupTask[]
  history: { taskId: string; name: string; accountId: number; trigger: string; runAt: number; success: boolean; message: string }[]
}
