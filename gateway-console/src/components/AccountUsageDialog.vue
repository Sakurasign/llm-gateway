<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis, Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { api } from '../api/client'
import { displayTime } from '../api/display'
import { accountPlatforms } from '../api/protocols'
import type { AccountUsageRow, AccountUsageStats, CodexAccountStatus, UpstreamAccount } from '../api/types'

const props = defineProps<{ account: UpstreamAccount; status: string }>()
const emit = defineEmits<{ closed: [] }>()
const open = ref(true)
const tab = ref('stats')
const stats = ref<AccountUsageStats>()
const statsLoading = ref(false)
const statsError = ref('')
const chartEl = ref<HTMLDivElement>()
const format = new Intl.NumberFormat('zh-CN')
const cost = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 6 })
const formatCost = (micro: number) => cost.format(micro / 1_000_000)
type UsageTotals = Omit<AccountUsageRow, 'day' | 'model'>
function emptyTotals(): UsageTotals {
  return { requests: 0, inputTokens: 0, outputTokens: 0, costMicro: 0, durationMs: 0, timedRequests: 0 }
}
function addUsage(total: UsageTotals, row: UsageTotals) {
  total.requests += row.requests
  total.inputTokens += row.inputTokens
  total.outputTokens += row.outputTokens
  total.costMicro += row.costMicro
  total.durationMs += row.durationMs
  total.timedRequests += row.timedRequests
  return total
}
const breakdown = computed(() => {
  const today = stats.value?.today
  const byDay = new Map<string, UsageTotals>()
  const byModel = new Map<string, UsageTotals>()
  const total = emptyTotals()
  if (today) {
    const last = new Date(`${today}T00:00:00Z`).getTime()
    for (let offset = 29; offset >= 0; offset--) {
      const day = new Date(last - offset * 86400000).toISOString().slice(0, 10)
      byDay.set(day, emptyTotals())
    }
  }
  for (const row of stats.value?.rows ?? []) {
    addUsage(total, row)
    const day = byDay.get(row.day)
    if (day) addUsage(day, row)
    byModel.set(row.model, addUsage(byModel.get(row.model) ?? emptyTotals(), row))
  }
  const models = Array.from(byModel, ([model, usage]) => ({ model, ...usage })).sort((a, b) => b.requests - a.requests)
  const active = Array.from(byDay).filter(([, usage]) => usage.requests > 0)
  const busiest = active.reduce<(typeof active)[number] | undefined>((best, item) => !best || item[1].requests > best[1].requests ? item : best, undefined)
  const costliest = active.reduce<(typeof active)[number] | undefined>((best, item) => !best || item[1].costMicro > best[1].costMicro ? item : best, undefined)
  return { total, byDay, models, busiest, costliest, today: byDay.get(today ?? '') ?? emptyTotals() }
})

async function loadStats() {
  statsLoading.value = true
  statsError.value = ''
  try { stats.value = await api.accountUsage(props.account.id) }
  catch (error) { statsError.value = (error as Error).message }
  finally { statsLoading.value = false }
}

watch([chartEl, breakdown], ([element, summary], _, cleanup) => {
  if (!element) return
  const chart = echarts.init(element)
  const textColor = getComputedStyle(element).getPropertyValue('--el-text-color-secondary').trim()
  const days = [...summary.byDay]
  chart.setOption({
    color: ['#3b82f6', '#10b981'],
    legend: { textStyle: { color: textColor } },
    tooltip: { trigger: 'axis' },
    grid: { top: 36, bottom: 25, left: 54, right: 66 },
    xAxis: { type: 'category', data: days.map(([day]) => day.slice(5)), axisLabel: { color: textColor } },
    yAxis: [
      { type: 'value', minInterval: 1, name: '请求', axisLabel: { color: textColor } },
      { type: 'value', minInterval: 1, name: 'Token', axisLabel: { color: textColor }, splitLine: { show: false } }
    ],
    series: [
      { name: '请求', type: 'line', showSymbol: false, data: days.map(([, day]) => day.requests) },
      { name: 'Token', type: 'line', showSymbol: false, yAxisIndex: 1, data: days.map(([, day]) => day.inputTokens + day.outputTokens) }
    ]
  })
  const observer = new ResizeObserver(() => chart.resize())
  observer.observe(element)
  cleanup(() => { observer.disconnect(); chart.dispose() })
}, { flush: 'post' })

const subscription = ref<CodexAccountStatus>()
const subscriptionLoading = ref(false)
const subscriptionError = ref('')
const subscriptionResetting = ref(false)
const selectedResetCreditId = ref('')
const selectedResetCredit = computed(() => subscription.value?.resetCredits.options.find(credit => credit.id === selectedResetCreditId.value))
const quotaPercent = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 })
const subscriptionDateFallback = computed(() => {
  const status = subscription.value
  if (status?.subscriptionError) return '查询失败'
  if (status?.planType?.toLowerCase() === 'free' && !status.subscriptionStartedAt && !status.subscriptionExpiresAt) return '未订阅'
  return '上游未提供'
})

function quotaWindowLabel(seconds: number) {
  if (seconds === 604800) return '周额度'
  if (seconds % 86400 === 0) return `${seconds / 86400} 天额度`
  if (seconds % 3600 === 0) return `${seconds / 3600} 小时额度`
  if (seconds % 60 === 0) return `${seconds / 60} 分钟额度`
  return `${seconds} 秒额度`
}

async function loadSubscription() {
  if (subscriptionLoading.value) return
  subscriptionLoading.value = true
  subscriptionError.value = ''
  try {
    subscription.value = await api.codexStatus(props.account.id)
    if (!selectedResetCredit.value) selectedResetCreditId.value = ''
  } catch (error) {
    subscriptionError.value = (error as Error).message
  } finally {
    subscriptionLoading.value = false
  }
}

async function resetSubscriptionQuota() {
  const credit = selectedResetCredit.value
  if (!credit || subscriptionLoading.value || subscriptionResetting.value) return
  try {
    await ElMessageBox.confirm(`为「${props.account.name}」使用「${credit.label}」（${displayTime(credit.expiresAt)} 到期）？会消耗 1 次重置机会。`,
      '确认重置额度', { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' })
  } catch { return }
  if (!open.value || subscriptionResetting.value) return
  subscriptionResetting.value = true
  try {
    const result = await api.resetCodexQuota(props.account.id, credit.id)
    if (result.windowsReset > 0) ElMessage.success(`已重置 ${result.windowsReset} 个额度周期`)
    else ElMessage.info('上游没有重置任何额度周期')
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    selectedResetCreditId.value = ''
    await loadSubscription()
    subscriptionResetting.value = false
  }
}

watch(tab, value => {
  if (value === 'subscription' && !subscription.value) void loadSubscription()
})
onMounted(loadStats)
</script>

<template>
  <el-dialog v-model="open" title="用量统计" class="account-usage-dialog" width="min(960px, 94vw)" top="6vh" append-to-body @closed="emit('closed')">
    <div class="usage-account">
      <el-icon class="usage-icon" :size="20"><DataAnalysis /></el-icon>
      <div><strong>{{ account.name }}</strong><div class="dim">{{ accountPlatforms[account.provider] }} · #{{ account.id }}</div></div>
      <el-tag class="usage-status" :type="status === '已启用' ? 'success' : 'info'" round>{{ status }}</el-tag>
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="近 30 天用量" name="stats">
        <div class="stats-panel" v-loading="statsLoading">
          <el-alert v-if="statsError" :title="statsError" type="error" :closable="false" show-icon />
          <template v-else-if="stats">
            <div class="usage-metrics">
              <div><span>网关费用</span><strong>{{ formatCost(breakdown.total.costMicro) }}</strong></div>
              <div><span>请求次数</span><strong>{{ format.format(breakdown.total.requests) }}</strong></div>
              <div><span>Token 总量</span><strong>{{ format.format(breakdown.total.inputTokens + breakdown.total.outputTokens) }}</strong></div>
              <div><span>平均响应时间</span><strong>{{ breakdown.total.timedRequests ? `${format.format(Math.round(breakdown.total.durationMs / breakdown.total.timedRequests))} ms` : '—' }}</strong></div>
            </div>
            <div class="usage-highlights">
              <section class="usage-box"><h3>今日概览</h3><span>{{ format.format(breakdown.today.requests) }} 次请求 · {{ format.format(breakdown.today.inputTokens + breakdown.today.outputTokens) }} Token</span><span class="dim">{{ formatCost(breakdown.today.costMicro) }}</span></section>
              <section class="usage-box"><h3>费用最高日</h3><span>{{ breakdown.costliest?.[0] ?? '—' }}</span><span class="dim">{{ breakdown.costliest ? formatCost(breakdown.costliest[1].costMicro) : '暂无记录' }}</span></section>
              <section class="usage-box"><h3>请求最多日</h3><span>{{ breakdown.busiest?.[0] ?? '—' }}</span><span class="dim">{{ breakdown.busiest ? `${format.format(breakdown.busiest[1].requests)} 次请求` : '暂无记录' }}</span></section>
            </div>
            <section class="usage-box"><h3>使用趋势</h3>
              <div v-if="tab === 'stats' && breakdown.total.requests" ref="chartEl" class="usage-chart" />
              <el-empty v-else :image-size="48" description="近 30 天暂无此账号的网关请求记录" />
            </section>
            <section v-if="breakdown.models.length" class="usage-box"><h3>模型分布</h3>
              <el-table :data="breakdown.models" size="small">
                <el-table-column label="模型" prop="model" min-width="200" />
                <el-table-column label="请求" align="right"><template #default="{ row }">{{ format.format(row.requests) }}</template></el-table-column>
                <el-table-column label="输入 Token" align="right"><template #default="{ row }">{{ format.format(row.inputTokens) }}</template></el-table-column>
                <el-table-column label="输出 Token" align="right"><template #default="{ row }">{{ format.format(row.outputTokens) }}</template></el-table-column>
                <el-table-column label="网关费用" align="right"><template #default="{ row }">{{ formatCost(row.costMicro) }}</template></el-table-column>
              </el-table>
            </section>
          </template>
        </div>
      </el-tab-pane>
      <el-tab-pane v-if="account.provider === 'CODEX_SUBSCRIPTION' && account.realm !== 'api'" label="订阅与额度" name="subscription" lazy>

        <div class="subscription-panel" v-loading="subscriptionLoading">
          <el-alert v-if="subscriptionError" :title="subscriptionError" type="error" :closable="false" show-icon />
          <template v-if="subscription">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="会员套餐">{{ subscription.planType?.toUpperCase() || '上游未提供' }}</el-descriptions-item>
              <el-descriptions-item label="会员开通时间">{{ displayTime(subscription.subscriptionStartedAt, subscriptionDateFallback) }}</el-descriptions-item>
              <el-descriptions-item label="订阅到期时间">{{ displayTime(subscription.subscriptionExpiresAt, subscriptionDateFallback) }}</el-descriptions-item>
            </el-descriptions>
            <el-alert v-if="subscription.subscriptionError" title="订阅信息查询失败" :description="subscription.subscriptionError" type="warning" :closable="false" />
            <el-alert v-if="subscription.usageError" title="额度查询失败" :description="subscription.usageError" type="warning" :closable="false" />
            <div v-if="subscription.windows.length" class="subscription-windows">
              <div v-for="window in subscription.windows" :key="window.durationSeconds" class="subscription-window">
                <strong>{{ quotaWindowLabel(window.durationSeconds) }}</strong>
                <template v-if="window.usedPercent != null">
                  <el-progress :percentage="window.usedPercent" :show-text="false" :status="window.usedPercent === 100 ? 'exception' : undefined" />
                  <div>已用 {{ quotaPercent.format(window.usedPercent) }}% · 剩余 {{ quotaPercent.format(100 - window.usedPercent) }}%</div>
                </template>
                <div v-else class="dim">上游未提供用量</div>
                <div class="dim">重置时间 {{ displayTime(window.resetsAt) }}</div>
              </div>
            </div>
            <div v-if="!subscription.usageError && !subscription.windows.some(window => window.durationSeconds === 18000)" class="dim">
              无 5 小时额度
            </div>
            <div class="subscription-window">
              <strong>剩余重置次数 {{ subscription.resetCredits.availableCount ?? '—' }}</strong>
              <el-alert v-if="subscription.resetCredits.error" title="重置次数查询失败" :description="subscription.resetCredits.error" type="warning" :closable="false" />
              <template v-else-if="subscription.resetCredits.options.length">
                <el-select v-model="selectedResetCreditId" placeholder="选择重置券" aria-label="选择重置券"
                  :disabled="subscriptionLoading || subscriptionResetting">
                  <el-option v-for="credit in subscription.resetCredits.options" :key="credit.id" :value="credit.id"
                    :label="`${credit.label} · 到期 ${displayTime(credit.expiresAt)}`" />
                </el-select>
                <div v-if="selectedResetCredit?.description" class="dim">{{ selectedResetCredit.description }}</div>
                <div><el-button type="warning" plain :disabled="!selectedResetCredit || subscriptionLoading || !!subscriptionError"
                  :loading="subscriptionResetting" @click="resetSubscriptionQuota">重置额度</el-button></div>
              </template>
              <div v-else class="dim">{{ subscription.resetCredits.availableCount === 0 ? '暂无可用重置券' : '上游未提供可选重置券' }}</div>
            </div>
            <div class="dim">查询时间 {{ displayTime(subscription.checkedAt) }}</div>
          </template>
        </div>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button @click="open = false">关闭</el-button>
      <el-button v-if="tab === 'subscription'" type="primary" :icon="Refresh" :loading="subscriptionLoading"
        :disabled="subscriptionResetting" @click="loadSubscription">刷新额度</el-button>
      <el-button v-else type="primary" :icon="Refresh" :loading="statsLoading" @click="loadStats">刷新统计</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
:global(.account-usage-dialog .el-dialog__body) { max-height: calc(88vh - 108px); overflow-y: auto; }
.usage-account { display: flex; align-items: center; gap: 12px; padding: 12px; margin-bottom: 10px; border: 1px solid var(--el-color-primary-light-7); border-radius: 9px; background: var(--el-color-primary-light-9); }
.usage-account strong { color: var(--el-text-color-primary); font-size: 15px; }
.usage-icon { width: 36px; height: 36px; flex: 0 0 auto; border-radius: 8px; background: var(--el-color-primary); color: #fff; }
.usage-status { margin-left: auto; }
.stats-panel, .subscription-panel { display: grid; gap: 12px; min-height: 84px; }
.usage-metrics, .usage-highlights, .subscription-windows { display: grid; gap: 12px; }
.usage-metrics { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.usage-highlights { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.usage-metrics > div, .usage-box, .subscription-window { padding: 12px; border: 1px solid var(--el-border-color-light); border-radius: 8px; }
.usage-metrics span { color: var(--el-text-color-secondary); font-size: 12px; }
.usage-metrics strong { display: block; margin-top: 6px; font-size: 21px; font-variant-numeric: tabular-nums; color: var(--el-text-color-primary); }
.usage-box h3 { margin: 0 0 8px; color: var(--el-text-color-primary); font-size: 14px; }
.usage-highlights .usage-box { display: flex; flex-direction: column; gap: 3px; }
.usage-chart { width: 100%; height: 225px; }
.subscription-windows { grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
.subscription-window { display: grid; gap: 8px; }
</style>
