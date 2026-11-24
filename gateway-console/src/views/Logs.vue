<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { Download, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import type { AccountGroup, LogQuery, RelayLogRow, UpstreamAccount } from '../api/types'

const rows = ref<RelayLogRow[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const model = ref('')
const accounts = ref<UpstreamAccount[]>([])
const groups = ref<AccountGroup[]>([])
const accountId = ref<number>()
const groupId = ref<number>()
const dates = ref<[string, string] | null>(null)
const modelOrder = ref(false)
const ascending = ref(false)
const exporting = ref(false)
const status = ref<number | ''>('')
const loading = ref(true)
const err = ref('')

const drawerOpen = ref(false)
const detail = ref<Record<string, unknown> | null>(null)
let detailRequest = 0

type RouteStep = { channel: string; accountId?: number; groupId?: number; status?: number; reason?: string }
const routeSteps = computed<RouteStep[]>(() => {
  const raw = detail.value?.route_trace
  try {
    const trace: unknown = typeof raw === 'string' ? JSON.parse(raw) : raw
    return Array.isArray(trace) ? trace.filter(step => step && typeof step.channel === 'string') : []
  } catch { return [] }
})

const skipReasons: Record<string, string> = {
  INFLIGHT_FULL: '并发已满',
  ACCOUNT_UNAVAILABLE: '账号暂不可用',
  INVALID_CONTEXT: '请求需要发送完整上下文'
}
const statusReasons: Record<number, string> = {
  401: '授权失败', 402: '额度不可用', 403: '访问被拒绝', 429: '已限流或额度用尽'
}

async function load(): Promise<void> {
  loading.value = true
  err.value = ''
  try {
    const res = await api.logs({ ...query(), page: page.value, size })
    rows.value = res.data ?? []
    total.value = res.total ?? 0
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}

function onPageChange(p: number): void {
  page.value = p
  void load()
}

async function showDetail(requestId: string): Promise<void> {
  const request = ++detailRequest
  detail.value = null
  drawerOpen.value = true
  try {
    const result = await api.logDetail(requestId)
    if (request === detailRequest) detail.value = result
  } catch (e) {
    if (request === detailRequest) detail.value = { error: e instanceof Error ? e.message : String(e) }
  }
}

function query(): LogQuery {
  return { model: model.value, status: status.value, accountId: accountId.value, groupId: groupId.value,
    from: dates.value?.[0], to: dates.value?.[1], modelOrder: modelOrder.value, ascending: ascending.value }
}

function sortChange({ prop, order }: { prop: string; order: string | null }) {
  modelOrder.value = prop === 'virtual_model' && order != null
  ascending.value = order === 'ascending'
  search()
}

function fmtCost(micro: number): string {
  return '$' + (micro / 1_000_000).toFixed(6)
}

function fmtTime(t: string): string {
  return String(t).replace('T', ' ').slice(0, 19)
}

function fmtDuration(ms?: number | null) { return ms && ms > 0 ? `${(ms / 1000).toFixed(2)}s` : '—' }
const count = new Intl.NumberFormat('zh-CN')
function fmtTokens(tokens?: number | null) { return tokens == null ? '—' : count.format(tokens) }
function totalInput(row: RelayLogRow) {
  if (row.uncached_input_tokens == null) return null
  return row.uncached_input_tokens + (row.cached_tokens ?? 0) + (row.cache_creation_tokens ?? 0)
}
function cacheRate(row: RelayLogRow) {
  const total = totalInput(row)
  return total ? `${((row.cached_tokens ?? 0) / total * 100).toFixed(1)}%` : '—'
}
function outputTps(row: RelayLogRow) {
  return row.total_ms && row.output_tokens > 0 ? `${(row.output_tokens * 1000 / row.total_ms).toFixed(1)} tok/s` : '—'
}
function generationTps(row: RelayLogRow) {
  const duration = (row.total_ms ?? 0) - (row.ttft_ms ?? 0)
  return row.ttft_ms && duration > 0 && row.output_tokens > 0 ? `${(row.output_tokens * 1000 / duration).toFixed(1)} tok/s` : '—'
}
function billing(row: RelayLogRow) {
  return row.billing_mode === 'SUBSCRIPTION' ? '订阅' : row.billing_mode === 'METERED' ? '按量' : '—'
}
function accountLabel(row: RelayLogRow) { return row.account_name || (row.account_id == null ? '—' : `#${row.account_id}`) }
function groupLabel(row: RelayLogRow) { return row.group_name || (row.group_id == null ? '—' : `#${row.group_id}`) }

async function exportCsv() {
  exporting.value = true
  const filters = query()
  try {
    const result: RelayLogRow[] = []
    const first = await api.logs({ ...filters, page: 1, size: 100 })
    result.push(...first.data)
    for (let page = 2; (page - 1) * 100 < first.total; page++) {
      const batch = await api.logs({ ...filters, page, size: 100 })
      if (!batch.data.length) break
      result.push(...batch.data)
    }
    const headers = ['账号', '模型', '上游模型', '推理强度', '入站端点', '上游端点', '分组', '类型', '计费模式', '非缓存输入', '输出', '缓存读取', '缓存写入', '总输入', '推理', '缓存命中率', '生成 TPS', '费用（USD）', '账号折前成本（USD）', '首字延迟（ms）', '总耗时（ms）', '输出 TPS', '状态', '时间', '请求 ID']
    const records = result.map(row => [accountLabel(row), row.virtual_model, row.upstream_model, row.reasoning_effort, row.inbound_endpoint,
      row.upstream_endpoint, groupLabel(row), row.gateway_stream ? '流式' : '非流式', billing(row), row.uncached_input_tokens,
      row.output_tokens, row.cached_tokens, row.cache_creation_tokens, totalInput(row), row.reasoning_tokens, cacheRate(row), generationTps(row),
      row.cost_micro / 1_000_000, row.account_cost_micro == null ? null : row.account_cost_micro / 1_000_000, row.ttft_ms || null, row.total_ms, outputTps(row), row.status_code, fmtTime(row.created_at), row.request_id])
    const cell = (value: unknown) => {
      const text = String(value ?? '')
      return `"${(/^[\s]*[=+@-]/.test(text) ? "'" + text : text).replaceAll('"', '""')}"`
    }
    const url = URL.createObjectURL(new Blob(['\uFEFF' + [headers, ...records].map(row => row.map(cell).join(',')).join('\r\n')], {type: 'text/csv;charset=utf-8'}))
    const link = document.createElement('a')
    link.href = url
    link.download = 'sakura-usage.csv'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    err.value = e instanceof Error ? e.message : String(e)
  } finally { exporting.value = false }
}

onMounted(() => {
  void load()
  void api.accounts().then(res => { accounts.value = res.data ?? [] }).catch(() => {})
  void api.groups().then(res => { groups.value = res.data ?? [] }).catch(() => {})
})
</script>

<template>
  <h1>使用记录</h1>

  <div class="toolbar usage-filters">
    <el-input v-model="model" placeholder="模型" class="short-filter" clearable @keyup.enter="search" />
    <el-select v-model="accountId" placeholder="全部账号" aria-label="账号筛选" class="short-filter" filterable clearable @change="search">
      <el-option v-for="row in accounts" :key="row.id" :value="row.id" :label="row.name" />
    </el-select>
    <el-select v-model="groupId" placeholder="全部分组" aria-label="分组筛选" class="short-filter" filterable clearable @change="search">
      <el-option v-for="row in groups" :key="row.id" :value="row.id" :label="row.name" />
    </el-select>
    <el-select v-model="status" placeholder="全部状态" class="short-filter" clearable @change="search">
      <el-option v-for="code in [200, 400, 401, 402, 403, 429, 500, 502, 503]" :key="code" :label="String(code)" :value="code" />
    </el-select>
    <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期"
      end-placeholder="结束日期" range-separator="至" class="date-filter" @change="search" />
    <el-button :icon="Search" type="primary" @click="search">查询</el-button>
    <el-button :icon="Refresh" @click="load" aria-label="刷新" />
    <el-button :icon="Download" :loading="exporting" :disabled="!total" @click="exportCsv">导出 CSV</el-button>
  </div>

  <el-alert v-if="err" :title="err" type="error" :closable="false" style="margin-bottom: 14px" />

  <el-table v-loading="loading" :data="rows" row-key="request_id" stripe class="usage-table"
    :default-sort="{ prop: 'created_at', order: 'descending' }" @sort-change="sortChange">
    <el-table-column label="账号" min-width="84" show-overflow-tooltip>
      <template #default="{ row }">{{ accountLabel(row) }}</template>
    </el-table-column>
    <el-table-column label="模型" prop="virtual_model" sortable="custom" min-width="122" show-overflow-tooltip>
      <template #default="{ row }">
        <div>{{ row.virtual_model }}</div>
        <small v-if="row.upstream_model && row.upstream_model !== row.virtual_model" class="dim">{{ row.upstream_model }}</small>
      </template>
    </el-table-column>
    <el-table-column label="推理强度" min-width="85">
      <template #default="{ row }"><span class="effort">{{ row.reasoning_effort || '—' }}</span></template>
    </el-table-column>
    <el-table-column label="端点" min-width="165" show-overflow-tooltip>
      <template #default="{ row }">
        <div><span class="dim">入站</span> {{ row.inbound_endpoint || '—' }}</div>
        <div><span class="dim">上游</span> {{ row.upstream_endpoint || '—' }}</div>
      </template>
    </el-table-column>
    <el-table-column label="分组" min-width="80" show-overflow-tooltip>
      <template #default="{ row }"><el-tag v-if="row.group_id != null" size="small" effect="light">{{ groupLabel(row) }}</el-tag><span v-else>—</span></template>
    </el-table-column>
    <el-table-column label="类型" width="76">
      <template #default="{ row }"><el-tag size="small" :type="row.gateway_stream ? 'primary' : 'info'">{{ row.gateway_stream ? '流式' : '非流式' }}</el-tag></template>
    </el-table-column>
    <el-table-column label="计费模式" min-width="83">
      <template #default="{ row }"><el-tag v-if="row.billing_mode" size="small" effect="light" type="info">{{ billing(row) }}</el-tag><span v-else>—</span></template>
    </el-table-column>
    <el-table-column label="Token" min-width="176">
      <template #default="{ row }">
        <div class="token-line"><span title="非缓存输入 Token" class="token-in">↓ {{ fmtTokens(row.uncached_input_tokens ?? row.input_tokens) }}</span><span title="输出 Token" class="token-out">↑ {{ fmtTokens(row.output_tokens) }}</span></div>
        <div class="token-line"><span title="缓存读取 Token" class="token-read">读 {{ fmtTokens(row.cached_tokens) }}</span><span title="总输入 Token" class="token-write">总 {{ fmtTokens(totalInput(row)) }}</span></div>
        <div class="token-meta"><span v-if="row.cache_creation_tokens">缓存写入 {{ fmtTokens(row.cache_creation_tokens) }}</span><span v-if="row.reasoning_tokens">推理 {{ fmtTokens(row.reasoning_tokens) }}</span><span>命中 {{ cacheRate(row) }}</span><span title="输出 Token ÷ 首字后的耗时">生成 {{ generationTps(row) }}</span></div>
      </template>
    </el-table-column>
    <el-table-column label="费用" min-width="105">
      <template #default="{ row }"><div class="usage-cost">{{ fmtCost(row.cost_micro) }}</div><div v-if="row.account_id != null" class="account-cost" title="账号侧估算成本（按渠道倍率，未计分组折扣）">A {{ row.account_cost_micro == null ? '—' : fmtCost(row.account_cost_micro) }}</div><small v-if="row.usage_source === 'ESTIMATED' && row.cost_micro > 0" class="dim">估算</small></template>
    </el-table-column>
    <el-table-column label="延迟" min-width="140">
      <template #default="{ row }">
        <div class="usage-latency"><span>首字</span><strong>{{ fmtDuration(row.ttft_ms) }}</strong><span>总耗时</span><strong>{{ fmtDuration(row.total_ms) }}</strong><span>输出 TPS</span><strong>{{ outputTps(row) }}</strong></div>
      </template>
    </el-table-column>
    <el-table-column label="时间" prop="created_at" sortable="custom" min-width="158">
      <template #default="{ row }"><time>{{ fmtTime(row.created_at) }}</time><div class="row-detail"><el-tag size="small" :type="row.status_code < 400 ? 'success' : 'danger'" effect="plain">{{ row.status_code }}</el-tag><el-button link type="primary" @click="showDetail(row.request_id)">详情</el-button></div></template>
    </el-table-column>
    <template #empty><span class="dim">暂无使用记录</span></template>
  </el-table>

  <div class="toolbar mt" style="justify-content: flex-end">
    <el-pagination
      :current-page="page"
      :page-size="size"
      :total="total"
      layout="prev, pager, next"
      @current-change="onPageChange"
    />
  </div>

  <el-drawer v-model="drawerOpen" title="请求详情" size="520px">
    <template v-if="detail">
      <template v-if="routeSteps.length">
        <h3>路由与切号</h3>
        <p class="dim">请求 {{ detail.request_id }} · 重试 {{ detail.retry_count ?? 0 }} 次</p>
        <el-timeline>
          <el-timeline-item v-for="(step, index) in routeSteps" :key="index"
            :type="step.status == null ? 'info' : step.status < 400 ? 'success' : 'danger'">
            <strong>{{ step.channel }}</strong>
            <span v-if="step.accountId != null" class="dim"> · 账号 #{{ step.accountId }}</span>
            <span v-if="step.groupId != null" class="dim"> · 分组 #{{ step.groupId }}</span>
            <div v-if="step.status == null" class="dim">跳过 · {{ skipReasons[step.reason ?? ''] ?? step.reason }}</div>
            <div v-else-if="step.status < 400">成功 · {{ step.status }}</div>
            <div v-else>失败 · {{ step.status }}<template v-if="statusReasons[step.status]"> · {{ statusReasons[step.status] }}</template></div>
          </el-timeline-item>
        </el-timeline>
      </template>
      <details :open="!routeSteps.length">
        <summary>原始记录</summary>
        <pre class="mono" style="font-size: 12px; white-space: pre-wrap; word-break: break-all">{{ JSON.stringify(detail, null, 2) }}</pre>
      </details>
    </template>
    <el-skeleton v-else :rows="6" animated />
  </el-drawer>
</template>

<style scoped>
.usage-filters { gap: 8px; flex-wrap: wrap; }
.short-filter { width: 126px; }
.usage-filters .date-filter { width: 230px; flex: 0 0 230px; }
.usage-filters .el-button + .el-button { margin-left: 0; }
.usage-table :deep(td.el-table__cell) { padding-block: 14px; }
.usage-table :deep(.cell) { line-height: 1.55; }
.effort { text-transform: capitalize; }
.token-line { display: flex; flex-wrap: wrap; gap: 0 10px; font-variant-numeric: tabular-nums; }
.token-in, .usage-cost { color: var(--el-color-success); }
.token-out { color: #9068db; }
.token-read { color: var(--el-color-primary); }
.token-write, .account-cost { color: var(--el-color-warning); }
.token-meta { display: flex; flex-direction: column; color: var(--el-text-color-secondary); font-size: 12px; }
.usage-latency { border-left: 3px solid var(--el-color-success); padding-left: 8px; display: grid; grid-template-columns: auto 1fr; gap: 1px 6px; font-size: 12px; white-space: nowrap; }
.usage-latency span { color: var(--el-text-color-secondary); }
.usage-latency strong { font-weight: 500; font-variant-numeric: tabular-nums; }
.row-detail { display: flex; align-items: center; gap: 8px; }
</style>
