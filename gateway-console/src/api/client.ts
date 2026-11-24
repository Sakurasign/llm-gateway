import type { AccountProxy, AccountProxyInput,
  ChannelInfo,
  CodexAccountStatus,
  CodexWakeupInput,
  CodexWakeupOverview,
  AccountUsageStats,
  ChannelPayload,
  RoutePayload,
  UpstreamAccount,
  AccountMapping,
  AccountGroup,
  AccountGroupInput,
  AccountOptions,
  GatewayKey,
  GatewayKeyInput,
  LoginResponse,
  LogQuery,
  PromptVersion,
  RelayLogPage,
  SavePromptPayload,
  StatsOverview,
  TimeseriesPoint,
  ChannelStat,
  ModelStat,
  VirtualModelInfo
} from './types'

const TOKEN_KEY = 'gw_admin_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(t: string): void {
  localStorage.setItem(TOKEN_KEY, t)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

/** 统一的请求封装。401 时清 token 并抛出可识别的错误。 */
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((options.headers as Record<string, string>) ?? {})
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const res = await fetch(path, { ...options, headers })
  if (res.status === 401) {
    clearToken()
    throw new Error('UNAUTHORIZED')
  }
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.message || body.detail || (res.status === 409 ? '配置冲突，请刷新后重试。' : `请求失败（${res.status}）`))
  }
  return (await res.json()) as T
}

interface DataList<T> {
  data: T[]
}

const body = (method: string, input?: unknown): RequestInit => ({ method, body: input === undefined ? undefined : JSON.stringify(input) })
type VersionedList<T> = DataList<T> & { version: number }

export const api = {
  login: (username: string, password: string): Promise<LoginResponse> =>
    request<LoginResponse>('/admin/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password })
    }),

  overview: (): Promise<{ version: number; models: number; channels: number }> =>
    request('/admin/api/v1/overview'),

  channels: (): Promise<VersionedList<ChannelInfo>> => request('/admin/api/v1/channels'),

  models: (): Promise<VersionedList<VirtualModelInfo>> => request('/admin/api/v1/models'),

  saveChannel: (version: number, input: ChannelPayload, id?: number) =>
    request<{ version: number }>(`/admin/api/v1/channels${id ? `/${id}` : ''}?version=${version}`, body(id ? 'PUT' : 'POST', input)),
  statusChannel: (version: number, id: number, enabled: boolean) =>
    request<{ version: number }>(`/admin/api/v1/channels/${id}?version=${version}&enabled=${enabled}`, body('PATCH')),
  deleteChannel: (version: number, id: number) =>
    request<{ version: number }>(`/admin/api/v1/channels/${id}?version=${version}`, body('DELETE')),
  saveRoute: (version: number, input: RoutePayload, id?: number) =>
    request<{ version: number }>(`/admin/api/v1/routes${id ? `/${id}` : ''}?version=${version}`, body(id ? 'PUT' : 'POST', input)),
  deleteRoute: (version: number, id: number) =>
    request<{ version: number }>(`/admin/api/v1/routes/${id}?version=${version}`, body('DELETE')),
  importConfig: (version: number, yaml: string) =>
    request<{ version: number }>(`/admin/api/v1/config/import?version=${version}`, body('POST', { yaml })),
  accounts: () => request<DataList<UpstreamAccount> & { bindings: { keyId: number; accountId: number }[]; keyIds: number[] }>('/admin/api/v1/accounts'),
  createAnthropicAccount: (input: { name: string; apiKey: string; proxyId?: number }) =>
    request<{ id: number }>('/admin/api/v1/accounts/anthropic', body('POST', input)),
  replaceAnthropicKey: (id: number, apiKey: string) =>
    request<{ id: number }>(`/admin/api/v1/accounts/${id}/anthropic-key`, body('PUT', { apiKey })),
  createOpenAiAccount: (input: { name: string; apiKey: string; proxyId?: number }) =>
    request<{ id: number }>('/admin/api/v1/accounts/openai-key', body('POST', input)),
  replaceOpenAiKey: (id: number, apiKey: string) =>
    request<{ id: number }>(`/admin/api/v1/accounts/${id}/openai-key`, body('PUT', { apiKey })),
  loginClaude: (id?: number, proxyId?: number) => request<{ url: string; state: string }>(`/admin/api/v1/accounts/anthropic/login?${id ? `id=${id}` : proxyId ? `proxyId=${proxyId}` : ''}`, body('POST')),
  confirmClaude: (state: string, callback: string) => request<{ id: number }>('/admin/api/v1/accounts/anthropic/confirm', body('POST', { state, callback })),
  loginCodex: (id?: number, proxyId?: number) => request<{ url: string; state: string }>(`/admin/api/v1/accounts/codex/login?${id ? `id=${id}` : proxyId ? `proxyId=${proxyId}` : ''}`, body('POST')),
  confirmCodex: (state: string, callback: string) => request<{ id: number }>('/admin/api/v1/accounts/codex/confirm', body('POST', { state, callback })),
  importCodexAccounts: (input: unknown) => request<{ ids: number[]; skipped: number; errors: { row: number; message: string }[]; warnings: { row: number; message: string }[] }>('/admin/api/v1/accounts/codex/import', body('POST', input)),
  accountSettings: (id: number) => request<{ mappings: AccountMapping[]; version: number; priority: number; concurrency: number; groupIds: number[]; proxyId: number }>(`/admin/api/v1/accounts/${id}/settings`),
  testAccountModel: (id: number, model: string) => request<{ ok: boolean; elapsedMs: number; output: string | null; message: string | null }>(
    `/admin/api/v1/accounts/${id}/test-model`, body('POST', { model })),
  editAccount: (id: number, version: number, input: { name: string; enabled: boolean; mappings: AccountMapping[]; options: AccountOptions }) =>
    request<{ version: number }>(`/admin/api/v1/accounts/${id}?version=${version}`, body('PUT', input)),
  proxies: () => request<DataList<AccountProxy>>('/admin/api/v1/proxies'),
  saveProxy: (input: AccountProxyInput, id?: number) => request(`/admin/api/v1/proxies${id ? `/${id}` : ''}`, body(id ? 'PUT' : 'POST', input)),
  deleteProxy: (id: number) => request(`/admin/api/v1/proxies/${id}`, body('DELETE')),
  testProxy: (id: number) => request<{ upstreamStatus: number }>(`/admin/api/v1/proxies/${id}/test`, body('POST')),
  groups: () => request<DataList<AccountGroup>>('/admin/api/v1/groups'),
  preferGroupAccount: (id: number, accountId: number | null) =>
    request(`/admin/api/v1/groups/${id}/preferred-account`, body('PUT', { accountId })),
  reorderGroupAccounts: (id: number, accountIds: number[]) =>
    request(`/admin/api/v1/groups/${id}/account-order`, body('PUT', { accountIds })),
  saveGroup: (input: AccountGroupInput, id?: number) => request<{ id: number }>(`/admin/api/v1/groups${id ? `/${id}` : ''}`, body(id ? 'PUT' : 'POST', input)),
  deleteGroup: (id: number) => request(`/admin/api/v1/groups/${id}`, body('DELETE')),
  bulkAccounts: (ids: number[], action: {enabled?: boolean; schedulable?: boolean}) => request('/admin/api/v1/accounts/bulk', body('POST', { ids, ...action })),
  bulkKeys: (ids: number[], action: {enabled?: boolean; groupId?: number}) => request('/admin/api/v1/keys/bulk', body('POST', { ids, ...action })),
  saveKeyIps: (id: number, ipAllow: string[], ipDeny: string[]) => request(`/admin/api/v1/keys/${id}/ips`, body('PUT', { ipAllow, ipDeny })),
  keys: () => request<DataList<GatewayKey>>('/admin/api/v1/keys'),
  saveKey: (input: GatewayKeyInput, id?: number) => request<{ id: number }>(`/admin/api/v1/keys${id ? `/${id}` : ''}`, body(id ? 'PUT' : 'POST', input)),
  resetKeyUsage: (id: number, total: boolean) => request(`/admin/api/v1/keys/${id}/reset-usage?total=${total}`, body('POST')),
  deleteKey: (id: number) => request(`/admin/api/v1/keys/${id}`, body('DELETE')),
  useKey: (id: number) => request<{name: string; apiKey: string; models: AccountMapping[]}>(`/admin/api/v1/keys/${id}/use`, body('POST')),
  loginWork: (realm: string, id?: number, proxyId?: number) => request<{ url: string; ticket: string }>(`/admin/api/v1/accounts/workbuddy/login?realm=${encodeURIComponent(realm)}${id ? `&id=${id}` : proxyId ? `&proxyId=${proxyId}` : ''}`, body('POST')),
  confirmWork: (ticket: string) => request<{ id: number }>('/admin/api/v1/accounts/workbuddy/confirm', body('POST', { ticket })),
  importWork: (input: unknown) => request<{ id: number }>('/admin/api/v1/accounts/workbuddy/import', body('POST', input)),
  statusAccount: (id: number, enabled: boolean) => request(`/admin/api/v1/accounts/${id}?enabled=${enabled}`, body('PATCH')),
  deleteAccount: (id: number) => request(`/admin/api/v1/accounts/${id}`, body('DELETE')),
  accountModels: (id: number) => request<DataList<string>>(`/admin/api/v1/accounts/${id}/models`),
  accountUsage: (id: number) => request<AccountUsageStats>(`/admin/api/v1/accounts/${id}/usage-stats`),
  codexWakeups: () => request<CodexWakeupOverview>('/admin/api/v1/accounts/wakeups'),
  saveCodexWakeup: (input: CodexWakeupInput, id?: string) => request<{ ok: boolean }>(
    `/admin/api/v1/accounts/wakeups${id ? `/${id}` : ''}`, body(id ? 'PUT' : 'POST', input)),
  runCodexWakeup: (id: string) => request<{ ok: boolean }>(`/admin/api/v1/accounts/wakeups/${id}/run`, body('POST')),
  deleteCodexWakeup: (id: string) => request<{ ok: boolean }>(`/admin/api/v1/accounts/wakeups/${id}`, body('DELETE')),
  codexStatus: (id: number) => request<CodexAccountStatus>(`/admin/api/v1/accounts/${id}/codex-status`),
  resetCodexQuota: (id: number, creditId: string) => request<{ windowsReset: number }>(`/admin/api/v1/accounts/${id}/codex-status/reset`, body('POST', { creditId })),
  accountCredits: (id: number) => request<{ available: boolean; credits?: number }>(`/admin/api/v1/accounts/${id}/credits`, body('POST')),

  statsOverview: (): Promise<StatsOverview> => request('/admin/api/v1/stats/overview'),

  statsByModel: (): Promise<DataList<ModelStat>> => request('/admin/api/v1/stats/by-model'),

  statsByChannel: (): Promise<DataList<ChannelStat>> => request('/admin/api/v1/stats/by-channel'),

  timeseries: (): Promise<DataList<TimeseriesPoint>> => request('/admin/api/v1/stats/timeseries'),

  logs: (params: LogQuery = {}): Promise<RelayLogPage> => {
    const q = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') {
        q.set(k, String(v))
      }
    })
    return request(`/admin/api/v1/logs?${q.toString()}`)
  },

  logDetail: (requestId: string): Promise<Record<string, unknown>> =>
    request(`/admin/api/v1/logs/${requestId}`),

  breaker: (channelId: number, model: string): Promise<{ state: string }> =>
    request(`/admin/api/v1/breaker/${channelId}/${encodeURIComponent(model)}`),

  // ── Prompt 版本 ──
  savePrompt: (payload: SavePromptPayload): Promise<{ id: number; saved: boolean }> =>
    request('/admin/api/v1/prompts', { method: 'POST', body: JSON.stringify(payload) }),

  listPrompts: (params: { limit?: number; model?: string } = {}): Promise<
    DataList<PromptVersion> & { total: number }
  > => {
    const q = new URLSearchParams()
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== '') q.set(k, String(v))
    })
    return request(`/admin/api/v1/prompts?${q.toString()}`)
  },

  getPrompt: (id: number): Promise<PromptVersion> => request(`/admin/api/v1/prompts/${id}`),

  deletePrompt: (id: number): Promise<{ deleted: number }> =>
    request(`/admin/api/v1/prompts/${id}`, { method: 'DELETE' })
}
