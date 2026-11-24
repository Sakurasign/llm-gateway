<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import IpRulesFields from '../components/IpRulesFields.vue'
import { displayTime, parseIps } from '../api/display'
import type { AccountGroup, GatewayKey, GatewayKeyInput, KeySpend } from '../api/types'

const route = useRoute()
const router = useRouter()
const rows = ref<GatewayKey[]>([])
const groups = ref<AccountGroup[]>([])
const groupsById = computed(() => new Map(groups.value.map(group => [group.id, group])))
const search = ref('')
const groupFilter = ref<number>()
const statusFilter = ref<string>()
const page = ref(1)
const pageSize = ref(20)
const selected = ref<GatewayKey[]>([])
const moveGroup = ref<number>()
const spendFields = [
  { field: 'total', label: '总额度' }, { field: 'fiveHours', label: '5 小时' },
  { field: 'oneDay', label: '1 天' }, { field: 'sevenDays', label: '7 天' }
] as const
const status = (row: GatewayKey) => !row.enabled ? '已停用'
  : row.expiresAt && row.expiresAt * 1000 <= Date.now() ? '已过期'
  : spendFields.some(({ field }) => row.spendLimits[field] > 0 && row.spendUsed[field] >= row.spendLimits[field]) ? '额度已用完'
  : row.groupAvailable ? '已启用' : '无可用账号'
const visible = computed(() => rows.value.filter(row =>
  (!groupFilter.value || row.groupIds.includes(groupFilter.value)) &&
  (!statusFilter.value || status(row) === statusFilter.value) &&
  [row.name, row.prefix].some(value => value.toLowerCase().includes(search.value.trim().toLowerCase()))
))
const paged = computed(() => visible.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
watch([search, groupFilter, statusFilter, pageSize], () => { page.value = 1 })
watch(visible, () => { page.value = Math.min(page.value, Math.max(1, Math.ceil(visible.value.length / pageSize.value))) })
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const showForm = ref(false)
const keyId = ref<number>()
const emptySpend = (): KeySpend => ({ total: 0, fiveHours: 0, oneDay: 0, sevenDays: 0 })
const showSpend = ref(false)
const usd = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'USD', maximumFractionDigits: 6 })
const form = ref<{ name: string; enabled: boolean; groups: number[]; allow: string; deny: string;
  customKey: string; expires: string | null; rpm: number; spend: KeySpend }>({
  name: '', enabled: true, groups: [], allow: '', deny: '', customKey: '', expires: null, rpm: 0, spend: emptySpend()
})
const showIpRules = ref(false)
const showCustomKey = ref(false)
const legacyBinding = ref(false)
const canSave = computed(() => Boolean(form.value.name.trim() && form.value.groups.length &&
  (!showCustomKey.value || /^[A-Za-z0-9_-]{24,128}$/.test(form.value.customKey))))
const showSwitch = ref(false)
const keyUse = ref<Awaited<ReturnType<typeof api.useKey>>>()
const model = ref('')
const endpoint = ref('')
const switchLink = computed(() => {
  if (!keyUse.value || !model.value) return ''
  try {
    const url = new URL(endpoint.value.trim())
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) return ''
    const base = url.href.replace(/\/+$/, '')
    const params = new URLSearchParams({ resource: 'provider', app: 'codex', name: keyUse.value.name,
      endpoint: base.endsWith('/v1') ? base : `${base}/v1`, apiKey: keyUse.value.apiKey,
      apiFormat: 'openai_responses', model: model.value })
    return `ccswitch://v1/import?${params}`
  } catch { return '' }
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [list, groupList] = await Promise.all([api.keys(), api.groups()])
    rows.value = list.data
    groups.value = groupList.data
  } catch (e) { error.value = (e as Error).message }
  finally { loading.value = false }
}
function edit(row?: GatewayKey, groupId?: number) {
  keyId.value = row?.id
  const defaultGroup = groupId ?? (row?.accountId == null ? undefined : groups.value.find(group => group.automatic && group.provider === row.accountGroup)?.id)
  form.value = { name: row?.name ?? '', enabled: row?.enabled ?? true,
    groups: row?.groupIds.length ? [...row.groupIds] : defaultGroup == null ? [] : [defaultGroup],
    allow: row?.ipAllow.join('\n') ?? '', deny: row?.ipDeny.join('\n') ?? '', customKey: '',
    expires: row?.expiresAt ? String(row.expiresAt * 1000) : null, rpm: row?.rpmLimit ?? 0, spend: emptySpend() }
  showCustomKey.value = false
  showSpend.value = Boolean(row && spendFields.some(({ field }) => row.spendLimits[field] > 0))
  if (row && showSpend.value) {
    for (const { field } of spendFields) form.value.spend[field] = row.spendLimits[field] / 1000000
  }
  showIpRules.value = Boolean(form.value.allow || form.value.deny)
  legacyBinding.value = row?.accountId != null
  showForm.value = true
}
async function perform(run: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try { await run() }
  catch (e) { ElMessage.error((e as Error).message) }
  finally { busy.value = false }
}
function save(andImport = false) {
  const { name, groups: groupIds, enabled, allow, deny, customKey, expires, rpm } = form.value
  if (!canSave.value) return
  const spendLimits = emptySpend()
  if (showSpend.value) {
    for (const { field } of spendFields) spendLimits[field] = Math.round((form.value.spend[field] ?? 0) * 1000000)
  }
  const input: GatewayKeyInput = { name: name.trim(), groupIds, enabled,
    customKey: showCustomKey.value ? customKey : undefined, expiresAt: Math.floor(Number(expires) / 1000), rpmLimit: rpm ?? 0, spendLimits,
    ipAllow: showIpRules.value ? parseIps(allow) : [], ipDeny: showIpRules.value ? parseIps(deny) : [] }
  void perform(async () => {
    const result = await api.saveKey(input, keyId.value)
    showForm.value = false
    ElMessage.success('密钥已保存')
    await load()
    if (andImport) await prepareImport(result.id)
  })
}
async function prepareImport(id: number) {
  const usage = await api.useKey(id)
  if (!usage.models.length) {
    ElMessage.warning('请先为分组中的可用账号配置模型映射')
    return
  }
  keyUse.value = usage
  model.value = usage.models[0]?.model ?? ''
  endpoint.value = `${window.location.origin}/v1`
  showSwitch.value = true
}
function copyKey(row: GatewayKey) {
  void perform(async () => {
    const { apiKey } = await api.useKey(row.id)
    await navigator.clipboard.writeText(apiKey)
    ElMessage.success('密钥已复制')
  })
}
async function remove(row: GatewayKey) {
  try { await ElMessageBox.confirm(`撤销「${row.name}」？使用此密钥的客户端将无法继续调用。`, '撤销密钥', { type:'warning' }) }
  catch { return }
  void perform(async () => { await api.deleteKey(row.id); ElMessage.success('密钥已撤销'); await load() })
}
async function resetUsage(row: GatewayKey, total: boolean) {
  try {
    await ElMessageBox.confirm(`重置「${row.name}」的${total ? '总额度和周期' : '周期'}用量？`, '重置用量', { type: 'warning' })
  } catch { return }
  await perform(async () => {
    await api.resetKeyUsage(row.id, total)
    ElMessage.success('用量已重置')
    await load()
  })
}
async function bulk(action: { enabled?: boolean; groupId?: number }) {
  const ids = selected.value.map(row => row.id)
  await perform(async () => {
    await api.bulkKeys(ids, action)
    moveGroup.value = undefined
    ElMessage.success('密钥已更新')
    await load()
  })
}
onMounted(async () => {
  await load()
  const group = groupsById.value.get(Number(route.query.group))
  if (group) {
    edit(undefined, group.id)
    void router.replace('/keys')
  }
})
</script>

<template>
  <section class="workspace-page" aria-labelledby="keys-heading">
    <div class="page-heading">
      <div><h1 id="keys-heading">我的密钥</h1><p>一把密钥可访问所选分组的模型。</p></div>
      <el-button type="primary" :icon="Plus" @click="edit()">创建密钥</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-error" />
    <div class="panel">
      <div class="filter-bar management-filters">
        <el-input v-model="search" placeholder="搜索密钥" aria-label="搜索密钥" :prefix-icon="Search" clearable />
        <el-select v-model="groupFilter" placeholder="全部分组" aria-label="分组筛选" clearable>
          <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" aria-label="状态筛选" clearable>
          <el-option v-for="option in ['已启用', '已停用', '已过期', '额度已用完', '无可用账号']" :key="option" :value="option" />
        </el-select>
        <el-button class="refresh-action" :icon="Refresh" aria-label="刷新密钥" @click="load" />
      </div>
      <p class="table-scroll-hint">横向滑动可查看密钥操作</p>
      <div v-if="selected.length" class="page-actions management-count">
        <span>已选 {{ selected.length }} 项</span>
        <el-button :loading="busy" @click="bulk({ enabled: true })">批量启用</el-button>
        <el-button :loading="busy" @click="bulk({ enabled: false })">批量停用</el-button>
        <el-select v-model="moveGroup" placeholder="选择目标分组" aria-label="目标分组" style="width: 170px">
          <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
        </el-select>
        <el-button :loading="busy" :disabled="!moveGroup" @click="bulk({ groupId: moveGroup })">批量换组</el-button>
      </div>
      <el-table v-loading="loading" :data="paged" row-key="id" empty-text="暂无密钥" @selection-change="selected = $event">
        <el-table-column type="selection" width="44" />
        <el-table-column label="名称 / 密钥" min-width="175"><template #default="{ row }"><strong>{{ row.name }}</strong><div class="mono dim">{{ row.prefix }}••••</div></template></el-table-column>
        <el-table-column label="分组" min-width="156"><template #default="{ row }">
          <el-tag v-for="id in row.groupIds" :key="id" effect="light" class="key-group">{{ groupsById.get(id)?.name ?? `#${id}` }}</el-tag>
          <div v-if="row.accountId != null" class="dim">旧账号绑定 #{{ row.accountId }}</div>
        </template></el-table-column>
        <el-table-column label="IP 管理" min-width="126"><template #default="{ row }">
          <span v-if="row.ipAllow.length || row.ipDeny.length">允许 {{ row.ipAllow.length }} · 拒绝 {{ row.ipDeny.length }}</span>
          <span v-else class="dim">不限</span>
        </template></el-table-column>
        <el-table-column label="状态" width="116"><template #default="{ row }"><span class="state-label" :class="{ enabled: status(row) === '已启用' }"><i />{{ status(row) }}</span></template></el-table-column>
        <el-table-column label="限制" min-width="126"><template #default="{ row }">
          <div>{{ row.rpmLimit ? `${row.rpmLimit} 次 / 分钟` : '不限频率' }}</div>
          <div class="dim">{{ displayTime(row.expiresAt, '永不过期') }}</div>
        </template></el-table-column>
        <el-table-column label="用量 / 额度" min-width="168"><template #default="{ row }">
          <template v-for="{ field, label } in spendFields" :key="field">
            <div v-if="row.spendLimits[field] > 0" class="dim">{{ label }} {{ usd.format(row.spendUsed[field] / 1000000) }} / {{ usd.format(row.spendLimits[field] / 1000000) }}</div>
          </template>
          <div v-if="!spendFields.some(({ field }) => row.spendLimits[field] > 0)" class="dim">不限</div>
          <el-button v-if="row.spendLimits.total > 0" link type="primary" @click="resetUsage(row, true)">重置总用量</el-button>
          <el-button v-if="row.spendLimits.fiveHours || row.spendLimits.oneDay || row.spendLimits.sevenDays" link type="primary" @click="resetUsage(row, false)">重置周期</el-button>
        </template></el-table-column>
        <el-table-column label="最近使用 / IP" min-width="146"><template #default="{ row }">
          <div>{{ displayTime(row.lastUsedAt) }}</div><div class="mono dim">{{ row.lastUsedIp }}</div>
        </template></el-table-column>
        <el-table-column label="创建时间" min-width="128"><template #default="{ row }">{{ displayTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="265"><template #default="{ row }">
          <el-button link type="primary" @click="edit(row)">编辑</el-button>
          <el-button link :disabled="busy || !row.enabled || status(row) === '已过期'" @click="copyKey(row)">复制</el-button>
          <el-button link type="primary" :disabled="busy || status(row) !== '已启用'" @click="perform(() => prepareImport(row.id))">导入 CC Switch</el-button>
          <el-button link type="danger" @click="remove(row)">撤销</el-button>
        </template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" class="management-count table-pagination"
        :total="visible.length" :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next" />
    </div>
    <el-dialog v-model="showForm" :title="keyId ? '编辑密钥' : '创建密钥'" width="min(520px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="save()">
        <el-form-item label="密钥名称"><el-input v-model="form.name" placeholder="例如 Codex 本机" maxlength="64" /></el-form-item>
        <el-form-item label="所属分组">
          <el-select v-model="form.groups" placeholder="按优先顺序选择分组" aria-label="所属分组" multiple filterable>
            <el-option v-for="group in groups" :key="group.id" :label="`${group.name} · ${group.enabled ? `${group.activeAccounts} 个可用账号` : '已停用'}`" :value="group.id" />
          </el-select>
        </el-form-item>
        <el-alert v-if="legacyBinding" title="保存后将从原账号切换到所选分组。" type="warning" :closable="false" class="page-error" />
        <el-form-item label="状态"><el-switch v-model="form.enabled" active-text="已启用" inactive-text="已停用" /></el-form-item>
        <el-form-item v-if="!keyId" label="自定义密钥"><el-switch v-model="showCustomKey" /></el-form-item>
        <el-form-item v-if="showCustomKey" label="密钥内容">
          <el-input v-model="form.customKey" type="password" show-password autocomplete="new-password" placeholder="24–128 位字母、数字、下划线或连字符" />
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker v-model="form.expires" value-format="x" type="datetime" placeholder="永不过期" />
        </el-form-item>
        <el-form-item label="每分钟请求数">
          <el-input-number v-model="form.rpm" :min="0" :max="100000" :precision="0" />
          <span class="form-hint">0 表示不限</span>
        </el-form-item>
        <el-form-item label="用量额度"><el-switch v-model="showSpend" /></el-form-item>
        <template v-if="showSpend">
          <p class="form-hint">按网关实际计费金额统计，单位为美元。0 表示不限，周期额度按滚动时间统计。</p>
          <div class="form-grid">
            <el-form-item v-for="{ field, label } in spendFields" :key="field" :label="label">
              <el-input-number v-model="form.spend[field]" :min="0" :max="1000000" :precision="6" :controls="false" />
            </el-form-item>
          </div>
        </template>
        <div class="mapping-panel">
          <div class="mapping-head"><h3>IP 管理</h3><el-switch v-model="showIpRules" aria-label="限制来源 IP" active-text="限制来源 IP" /></div>
          <IpRulesFields v-if="showIpRules" v-model:allow="form.allow" v-model:deny="form.deny" />
        </div>
      </el-form>
      <template #footer>
        <div class="page-actions dialog-actions">
          <el-button @click="showForm = false">取消</el-button>
          <el-button v-if="!keyId" :loading="busy" :disabled="!canSave || !form.enabled || !form.groups.some(id => groupsById.get(id)?.enabled && groupsById.get(id)?.activeAccounts)" @click="save(true)">创建并导入 CC Switch</el-button>
          <el-button type="primary" :loading="busy" :disabled="!canSave" @click="save()">{{ keyId ? '保存' : '创建' }}</el-button>
        </div>
      </template>
    </el-dialog>
    <el-dialog v-model="showSwitch" title="导入 CC Switch" width="min(520px, 94vw)" destroy-on-close @closed="keyUse = undefined">
      <el-form v-if="keyUse" label-position="top">
        <el-form-item label="供应商"><el-input :model-value="keyUse.name" readonly /></el-form-item>
        <el-form-item label="默认模型"><el-select v-model="model" filterable><el-option v-for="item in keyUse.models" :key="item.model" :label="item.model" :value="item.model" /></el-select></el-form-item>
        <el-form-item label="网关地址"><el-input v-model="endpoint" /></el-form-item>
        <p class="form-hint">在 CC Switch 中确认添加 Codex 供应商，密钥会随配置导入。</p>
      </el-form>
      <template #footer>
        <div class="page-actions dialog-actions">
          <el-button @click="showSwitch = false">关闭</el-button>
          <a v-if="switchLink" :href="switchLink" class="external-action">打开 CC Switch ↗</a>
          <el-button v-else type="primary" disabled>打开 CC Switch</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.key-group { margin: 2px 4px 2px 0; }
</style>
