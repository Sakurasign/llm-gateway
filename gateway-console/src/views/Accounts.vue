<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search, Setting, Upload } from '@element-plus/icons-vue'
import { api } from '../api/client'
import AccountUsageDialog from '../components/AccountUsageDialog.vue'
import CodexWakeupDialog from '../components/CodexWakeupDialog.vue'
import { accountRoutingModes, displayTime } from '../api/display'
import { accountPlatforms } from '../api/protocols'
import type { AccountGroup, AccountMapping, AccountOptions, AccountProxy, UpstreamAccount } from '../api/types'

type Provider = UpstreamAccount['provider']
const providers = Object.keys(accountPlatforms) as Provider[]
const router = useRouter()
const rows = ref<UpstreamAccount[]>([])
const groups = ref<AccountGroup[]>([])
const proxies = ref<AccountProxy[]>([])
const authProxy = ref(0)
const filterGroup = ref<number>()
const dispatchDialog = ref(false)
const wakeupDialog = ref(false)
const dispatchGroupId = ref<number>()
const dispatchGroup = computed(() => groups.value.find(group => group.id === dispatchGroupId.value))
const dispatchAccounts = computed(() => {
  const group = dispatchGroup.value
  if (!group) return []
  const preferred = group.preferredAccountId
  const positions = new Map(group.accountOrder.map((id, index) => [id, index]))
  const rank = (id: number) => positions.get(id) ?? group.accountOrder.length
  return rows.value.filter(row => group.accountIds.includes(row.id)).sort((a, b) =>
    Number(b.id === preferred) - Number(a.id === preferred) || rank(a.id) - rank(b.id) || a.priority - b.priority || a.id - b.id)
})
const page = ref(1)
const pageSize = ref(20)
const selected = ref<UpstreamAccount[]>([])
const extraColumns = ref<string[]>([])
const sort = ref({ prop: 'name', order: 'ascending' })
function changeSort(value: { prop: string | null; order: string | null }) {
  sort.value = { prop: value.prop ?? 'name', order: value.order ?? 'ascending' }
  page.value = 1
}
function sortValue(row: UpstreamAccount) {
  switch (sort.value.prop) {
    case 'id': return row.id
    case 'priority': return row.priority
    case 'status': return state(row)
    case 'schedulable': return Number(row.schedulable)
    case 'lastUsedAt': return row.lastUsedAt ?? 0
    case 'expiresAt': return row.expiresAt
    default: return row.name
  }
}
const groupsFor = (id: number) => groups.value.filter(group => group.accountIds.includes(id))
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const search = ref('')
const filterProvider = ref<Provider>()
const filterStatus = ref('')
const visible = computed(() => rows.value.filter(row =>
  (!filterProvider.value || row.provider === filterProvider.value) &&
  (!filterGroup.value || groupsFor(row.id).some(group => group.id === filterGroup.value)) &&
  (!filterStatus.value || (filterStatus.value === '授权异常' ? ['请检查 API Key', '需重新授权'].includes(state(row)) : state(row) === filterStatus.value)) &&
  [row.name, row.notes ?? "", String(row.id)].some(value => value.toLowerCase().includes(search.value.trim().toLowerCase()))
))
const loginDialog = ref(false)
const authProvider = ref<Provider>('CODEX_SUBSCRIPTION')
const label = computed(() => accountPlatforms[authProvider.value])
const realm = ref('cn')
const reauthId = ref<number>()
const callback = ref('')
const keyName = ref('')
const upstreamKey = ref('')
const authMethod = ref<'oauth' | 'api'>('oauth')
const pending = ref<{url:string; ticket?:string; state?:string}>()
const importDialog = ref(false)
const importProvider = ref<Provider>('CODEX_SUBSCRIPTION')
const accountInput = ref('')
const importResult = ref<Awaited<ReturnType<typeof api.importCodexAccounts>>>()
const editDialog = ref(false)
const editing = ref<UpstreamAccount>()
const version = ref(0)
const accountForm = ref<{name: string; enabled: boolean; mappings: AccountMapping[]; options: AccountOptions}>({
  name: '', enabled: true, mappings: [], options: { notes: '', schedulable: true, priority: 100, concurrency: 16, groupIds: [], proxyId: 0 }
})
const displayed = computed(() => [...visible.value].sort((left, right) => {
  const a = sortValue(left)
  const b = sortValue(right)
  const result = typeof a === 'number' && typeof b === 'number' ? a - b : String(a).localeCompare(String(b), 'zh-CN', { numeric: true })
  return (sort.value.order === 'descending' ? -result : result) || left.id - right.id
}).slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const editingGroups = computed(() => groups.value.filter(group => !group.automatic && group.provider === editing.value?.provider))
watch([search, filterProvider, filterGroup, filterStatus, pageSize], () => { page.value = 1 })
const discovered = ref<string[]>([])
const mappingTemplates = computed(() => {
  const account = editing.value
  return account ? rows.value.filter(row => row.id !== account.id && row.provider === account.provider &&
    row.realm === account.realm && row.mappedModels > 0) : []
})
const fetchingModels = ref(false)

const usageAccount = ref<UpstreamAccount>()

const testDialog = ref(false)
const testAccount = ref<UpstreamAccount>()
const testMappings = ref<AccountMapping[]>([])
const testModel = ref('')
const testLoading = ref(false)
const testing = ref(false)
const testResult = ref<Awaited<ReturnType<typeof api.testAccountModel>>>()
const testError = ref('')

async function openTest(row: UpstreamAccount) {
  if (testing.value || testLoading.value) return
  testAccount.value = row
  testMappings.value = []
  testModel.value = ''
  testResult.value = undefined
  testError.value = ''
  testDialog.value = true
  testLoading.value = true
  try {
    testMappings.value = (await api.accountSettings(row.id)).mappings
    testModel.value = testMappings.value[0]?.upstreamModel ?? ''
  } catch (error) {
    ElMessage.error((error as Error).message)
  } finally {
    testLoading.value = false
  }
}
async function runTest() {
  const row = testAccount.value
  const model = testModel.value.trim()
  if (!row || !model || testing.value) return
  const upstream = row.provider === 'WORKBUDDY' && !model.includes(':') ? `${row.realm}:${model}` : model
  testError.value = ''
  testResult.value = undefined
  testing.value = true
  try {
    testResult.value = await api.testAccountModel(row.id, upstream)
  } catch (error) {
    testError.value = (error as Error).message
  } finally {
    testing.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [accounts, groupList, proxyList] = await Promise.all([api.accounts(), api.groups(), api.proxies()])
    rows.value = accounts.data
    groups.value = groupList.data
    proxies.value = proxyList.data
    page.value = Math.min(page.value, Math.max(1, Math.ceil(visible.value.length / pageSize.value)))
    selected.value = []
  }
  catch (e) { error.value = (e as Error).message }
  finally { loading.value = false }
}
async function action<T>(run: () => Promise<T>, complete: (value: T) => void | Promise<void>) {
  if (busy.value) return
  busy.value = true
  try { await complete(await run()) }
  catch (e) { ElMessage.error((e as Error).message) }
  finally { busy.value = false }
}
function start(account?: UpstreamAccount) {
  authProvider.value = account?.provider ?? filterProvider.value ?? 'CODEX_SUBSCRIPTION'
  realm.value = account?.provider === 'WORKBUDDY' ? account.realm : 'cn'
  reauthId.value = account?.id
  authProxy.value = 0
  pending.value = undefined
  callback.value = ''
  keyName.value = account?.name ?? ''
  upstreamKey.value = ''
  authMethod.value = account?.realm === 'api' ? 'api' : 'oauth'
  loginDialog.value = true
  if (account && account.realm !== 'api') begin()
}
function saveApiKey() {
  const id = reauthId.value
  const apiKey = upstreamKey.value.trim()
  const name = keyName.value.trim()
  if (!apiKey || (id === undefined && !name)) return
  const claude = authProvider.value === 'ANTHROPIC'
  const create = claude ? api.createAnthropicAccount : api.createOpenAiAccount
  const replace = claude ? api.replaceAnthropicKey : api.replaceOpenAiKey
  void action(() => id === undefined ? create({ name, apiKey, proxyId: authProxy.value || undefined }) : replace(id, apiKey), async result => {
    upstreamKey.value = ''
    loginDialog.value = false
    ElMessage.success(id === undefined ? '账号已添加' : 'API Key 已更新')
    await load()
    const row = rows.value.find(row => row.id === result.id)
    if (id === undefined && row) await openEdit(row)
  })
}
function begin() {
  callback.value = ''
  const login = authProvider.value === 'ANTHROPIC' ? api.loginClaude : api.loginCodex
  void action<NonNullable<typeof pending.value>>(() => authProvider.value === 'WORKBUDDY'
    ? api.loginWork(realm.value, reauthId.value, authProxy.value)
    : login(reauthId.value, authProxy.value), result => {
    pending.value = result
  })
}
async function copyAuthLink() {
  const url = pending.value?.url
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('授权链接已复制')
  } catch {
    ElMessage.warning('无法访问剪贴板，请选中授权链接手动复制')
  }
}
async function authorized(result: {id:number}) {
  loginDialog.value = false
  callback.value = ''
  ElMessage.success('账号已授权')
  await load()
  const row = rows.value.find(row => row.id === result.id)
  if (row) await openEdit(row)
}
function confirmWork() {
  const ticket = pending.value?.ticket
  if (ticket) void action(() => api.confirmWork(ticket), authorized)
}
function confirmOAuth() {
  const state = pending.value?.state
  const confirm = authProvider.value === 'ANTHROPIC' ? api.confirmClaude : api.confirmCodex
  if (state) void action(() => confirm(state, callback.value.trim()), authorized)
}
function openImport(provider: Provider) {
  importProvider.value = provider
  importResult.value = undefined
  accountInput.value = ''
  importDialog.value = true
}
async function readAccountFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 16 * 1024 * 1024) { ElMessage.error('文件超过 16 MB'); return }
  try { accountInput.value = await file.text(); importResult.value = undefined }
  catch { ElMessage.error('无法读取文件') }
}
function importAccount() {
  let payload: unknown
  try { payload = JSON.parse(accountInput.value.replace(/^\uFEFF/, '')) }
  catch { ElMessage.error('凭证文件不是有效 JSON'); return }
  if (importProvider.value === 'CODEX_SUBSCRIPTION') {
    void action(() => api.importCodexAccounts(payload), async result => {
      importResult.value = result
      if (result.ids.length) { accountInput.value = ''; await load() }
    })
  } else void action(() => api.importWork(payload), authorizedFromImport)
}
async function authorizedFromImport(result: {id:number}) {
  importDialog.value = false
  ElMessage.success('账号已导入')
  await load()
  const row = rows.value.find(row => row.id === result.id)
  if (row) await openEdit(row)
}
function configureImported() {
  const row = rows.value.find(row => row.id === importResult.value?.ids[0])
  if (row) { importDialog.value = false; void openEdit(row) }
}
async function openEdit(row: UpstreamAccount) {
  try {
    const settings = await api.accountSettings(row.id)
    editing.value = row
    version.value = settings.version
    accountForm.value = {
      name: row.name, enabled: row.enabled, mappings: settings.mappings,
      options: { notes: row.notes ?? '', schedulable: row.schedulable, priority: settings.priority, concurrency: settings.concurrency, groupIds: settings.groupIds, proxyId: settings.proxyId }
    }
    discovered.value = []
    editDialog.value = true
  } catch (e) { ElMessage.error((e as Error).message) }
}
function copyMappings(id: number) {
  const account = editing.value
  if (!account) return
  void action(() => api.accountSettings(id), settings => {
    if (editing.value?.id === account.id && editDialog.value && !accountForm.value.mappings.length) {
      accountForm.value.mappings = settings.mappings
    }
  })
}
function openDispatch() {
  dispatchGroupId.value = filterGroup.value ?? groups.value[0]?.id
  dispatchDialog.value = true
}
function preferAccount(accountId: number) {
  const group = dispatchGroup.value
  if (!group) return
  void action(() => api.preferGroupAccount(group.id, accountId), async () => {
    ElMessage.success('已切换首选账号')
    await load()
  })
}
function saveDispatchOrder(accountIds: number[]) {
  const group = dispatchGroup.value
  if (!group) return
  void action(() => api.reorderGroupAccounts(group.id, accountIds), async () => {
    ElMessage.success(accountIds.length ? '已保存账号顺序' : '已恢复默认顺序')
    await load()
  })
}
function moveDispatchAccount(index: number, direction: -1 | 1) {
  const accountIds = dispatchAccounts.value.map(row => row.id)
  const target = index + direction
  if (target < 0 || target >= accountIds.length || accountIds[target] === dispatchGroup.value?.preferredAccountId) return
  const [moved] = accountIds.splice(index, 1)
  accountIds.splice(target, 0, moved)
  saveDispatchOrder(accountIds)
}
async function discover() {
  const id = editing.value?.id
  if (id === undefined) return
  fetchingModels.value = true
  try {
    discovered.value = (await api.accountModels(id)).data
    if (!discovered.value.length) ElMessage.info('上游没有返回可用模型')
  } catch (e) { ElMessage.error((e as Error).message) }
  finally { fetchingModels.value = false }
}
function suggestModels(query: string, done: (items: { value: string }[]) => void) {
  const filter = query.toLowerCase()
  done(discovered.value.filter(model => model.toLowerCase().includes(filter)).map(value => ({ value })))
}
function addDiscoveredModels() {
  const mappings = accountForm.value.mappings
  const existing = new Set(mappings.map(mapping => mapping.model.toLowerCase()))
  const additions = discovered.value.filter(model => !existing.has(model.toLowerCase())).slice(0, 100 - mappings.length)
  mappings.push(...additions.map(model => ({ model, upstreamModel: model })))
}
const canSave = computed(() => accountForm.value.name.trim() && accountForm.value.mappings.every(row => row.model.trim() && row.upstreamModel.trim()))
function saveEdit(createKey = false) {
  const row = editing.value
  if (!row || !canSave.value) return
  const mappings = accountForm.value.mappings.map(({model, upstreamModel}) => ({model: model.trim(), upstreamModel: upstreamModel.trim()}))
  void action(() => api.editAccount(row.id, version.value, { ...accountForm.value, name: accountForm.value.name.trim(), mappings }), async () => {
    editDialog.value = false
    ElMessage.success('账号配置已保存')
    if (createKey) {
      const group = accountForm.value.options.groupIds[0] ?? groups.value.find(group => group.provider === row.provider && group.automatic)?.id
      await router.push({ path: '/keys', query: { group } })
    }
    else await load()
  })
}
async function remove(row: UpstreamAccount) {
  try { await ElMessageBox.confirm(`移除「${row.name}」？绑定到该账号的密钥将停用。`, '移除账号', { type: 'warning' }) }
  catch { return }
  void action(() => api.deleteAccount(row.id), async () => { ElMessage.success('账号已移除'); await load() })
}
function bulk(update: { enabled?: boolean; schedulable?: boolean }, ids = selected.value.map(row => row.id)) {
  void action(() => api.bulkAccounts(ids, update), async () => { ElMessage.success('账号状态已更新'); await load() })
}
const creditFormat = new Intl.NumberFormat('zh-CN')
function credits(row: UpstreamAccount) {
  void action(() => api.accountCredits(row.id), async result => {
    if (result.available) { ElMessage.success('积分已更新'); await load() }
    else ElMessage.warning('上游未返回积分')
  })
}
function expiry(row: UpstreamAccount) { return row.provider === 'ANTHROPIC' ? '不适用' : displayTime(row.expiresAt, '未提供') }
function state(row: UpstreamAccount) {
  const now = Date.now() / 1000
  if (['http_401', 'http_403', 'reauthorization_required'].includes(row.lastError ?? '') && !row.enabled) return row.realm === 'api' ? '请检查 API Key' : '需重新授权'
  if (!row.enabled) return '已停用'
  if (!row.schedulable) return '暂停调度'
  if (row.cooldownUntil > now) return '冷却中'
  if (row.mappedModels === 0) return '待配置模型'
  if (row.provider !== 'ANTHROPIC' && row.expiresAt <= now) return '待刷新'
  return '已启用'
}
onMounted(load)
</script>

<template>
  <section class="workspace-page" aria-labelledby="accounts-heading">
    <div class="page-heading"><h1 id="accounts-heading">账号管理</h1></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-error" />
    <div class="panel">
      <div class="account-toolbar">
        <div class="filter-bar account-filters">
        <el-input v-model="search" placeholder="搜索账号" aria-label="搜索账号" :prefix-icon="Search" clearable />
        <el-select v-model="filterProvider" placeholder="全部平台" aria-label="平台筛选" clearable><el-option v-for="provider in providers" :key="provider" :label="accountPlatforms[provider]" :value="provider" /></el-select>
        <el-select v-model="filterGroup" placeholder="全部分组" aria-label="分组筛选" clearable filterable>
          <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
        </el-select>
        <el-select v-model="filterStatus" placeholder="全部状态" aria-label="状态筛选" clearable><el-option v-for="status in ['已启用', '冷却中', '暂停调度', '授权异常', '已停用', '待配置模型', '待刷新']" :key="status" :label="status" :value="status" /></el-select>
        </div>
        <div class="page-actions account-tools">
          <el-button :icon="Refresh" aria-label="刷新账号" @click="load" />
          <el-popover placement="bottom-end" trigger="click" :width="155">
            <template #reference><el-button :icon="Setting" aria-label="显示列" /></template>
            <el-checkbox-group v-model="extraColumns" class="column-options">
              <el-checkbox value="id">账号 ID</el-checkbox>
              <el-checkbox value="proxy">代理</el-checkbox>
            </el-checkbox-group>
          </el-popover>
          <el-button @click="wakeupDialog = true">定时唤醒</el-button>
          <el-button :disabled="loading || !groups.length" @click="openDispatch">账号切换</el-button>
          <el-button type="primary" :icon="Plus" @click="start()">添加账号</el-button>
        </div>
      </div>
      <p class="table-scroll-hint">横向滑动可查看完整账号信息</p>
      <div v-if="selected.length" class="page-actions management-count">
        <span class="dim">已选 {{ selected.length }} 项</span>
        <el-button size="small" @click="bulk({ enabled: true })">批量启用</el-button>
        <el-button size="small" @click="bulk({ enabled: false })">批量停用</el-button>
        <el-button size="small" @click="bulk({ schedulable: true })">批量开启调度</el-button>
        <el-button size="small" @click="bulk({ schedulable: false })">批量暂停调度</el-button>
      </div>
      <el-table v-loading="loading" class="account-table" :data="displayed" row-key="id" empty-text="没有匹配的账号"
        :default-sort="{ prop: 'name', order: 'ascending' }" @sort-change="changeSort" @selection-change="selected = $event">
        <el-table-column type="selection" width="44" />
        <el-table-column label="名称" prop="name" sortable="custom" min-width="145">
          <template #default="{ row }"><strong>{{ row.name }}</strong><div v-if="row.notes || row.lastError" class="dim ip-preview" :title="row.notes || row.lastError">{{ row.notes || row.lastError }}</div></template>
        </el-table-column>
        <el-table-column v-if="extraColumns.includes('id')" label="ID" prop="id" sortable="custom" width="68">
          <template #default="{ row }"><span class="dim">#{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="平台 / 类型" min-width="121"><template #default="{ row }">
          <el-tag effect="light" :type="row.provider === 'WORKBUDDY' ? 'success' : 'info'">{{ accountPlatforms[row.provider as Provider] }}</el-tag>
          <div class="dim">{{ ({ cn: '国内站', global: '国际站', api: 'API Key', oauth: 'OAuth', chatgpt: 'OAuth' } as Record<string, string>)[row.realm] }}</div>
          <div v-if="row.provider === 'WORKBUDDY'" class="dim workbuddy-credits">
            剩余积分 {{ row.credits == null ? '未查询' : creditFormat.format(row.credits) }}
          </div>
        </template></el-table-column>
        <el-table-column label="容量" width="80"><template #default="{ row }">
          <span class="capacity-pill" :class="{ 'capacity-full': row.inflight >= row.concurrency }" title="当前并发 / 并发上限">
            {{ row.inflight }} / {{ row.concurrency }}
          </span>
        </template></el-table-column>
        <el-table-column label="状态" prop="status" sortable="custom" width="96"><template #default="{ row }"><span class="state-label" :class="{ enabled: state(row) === '已启用' }"><i />{{ state(row) }}</span></template></el-table-column>
        <el-table-column label="调度" prop="schedulable" sortable="custom" width="70"><template #default="{ row }">
          <el-switch :model-value="row.schedulable" :disabled="busy" :aria-label="`调度 ${row.name}`" @change="bulk({schedulable: Boolean($event)}, [row.id])" />
        </template></el-table-column>
        <el-table-column label="分组" min-width="105"><template #default="{ row }">
          <div class="group-tags"><el-tag v-for="group in groupsFor(row.id)" :key="group.id" size="small" effect="plain">{{ group.name }}</el-tag></div>
        </template></el-table-column>
        <el-table-column v-if="extraColumns.includes('proxy')" label="代理" min-width="105">
          <template #default="{ row }">{{ proxies.find(proxy => proxy.id === row.proxyId)?.name ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="优先级" prop="priority" sortable="custom" width="83" />
        <el-table-column label="最近使用" prop="lastUsedAt" sortable="custom" min-width="121"><template #default="{ row }">{{ displayTime(row.lastUsedAt) }}</template></el-table-column>
        <el-table-column label="到期时间" prop="expiresAt" sortable="custom" min-width="126"><template #default="{ row }"><span :class="{ dim: row.expiresAt <= 0 }">{{ expiry(row) }}</span></template></el-table-column>
        <el-table-column label="操作" width="118"><template #default="{ row }">
          <div class="account-actions">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-dropdown trigger="click">
              <el-button link>更多</el-button>
              <template #dropdown><el-dropdown-menu>
                <el-dropdown-item :disabled="testing || testLoading" @click="openTest(row)">测试模型</el-dropdown-item>
                <el-dropdown-item @click="usageAccount = row">用量统计</el-dropdown-item>
                <el-dropdown-item @click="start(row)">{{ row.realm === 'api' ? '更换 API Key' : '重新授权' }}</el-dropdown-item>
                <el-dropdown-item v-if="row.provider === 'WORKBUDDY'" @click="credits(row)">更新积分</el-dropdown-item>
                <el-dropdown-item divided @click="remove(row)">移除账号</el-dropdown-item>
              </el-dropdown-menu></template>
            </el-dropdown>
          </div>
        </template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" class="management-count table-pagination"
        layout="total, sizes, prev, pager, next" :pager-count="3" :page-sizes="[20, 50, 100]" :total="visible.length" />
    </div>
    <CodexWakeupDialog v-model="wakeupDialog" :accounts="rows" />
    <AccountUsageDialog v-if="usageAccount" :key="usageAccount.id" :account="usageAccount" :status="state(usageAccount)"
      @closed="usageAccount = undefined" />
    <el-dialog v-model="testDialog" title="测试模型" width="min(500px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="runTest">
        <el-form-item label="账号"><el-input :model-value="testAccount?.name" readonly /></el-form-item>
        <el-form-item label="上游模型">
          <el-select v-model="testModel" aria-label="上游模型" filterable allow-create default-first-option
            placeholder="选择或输入上游模型名称" :loading="testLoading" :disabled="testing || testLoading"
            @change="testResult = undefined; testError = ''">
            <el-option v-for="mapping in testMappings" :key="mapping.model" :label="`${mapping.model} → ${mapping.upstreamModel}`"
              :value="mapping.upstreamModel" />
          </el-select>
        </el-form-item>
        <p class="form-hint">使用此账号和已绑定的代理发送一条真实请求，可能消耗上游额度。</p>
      </el-form>
      <el-alert v-if="testError" title="测试失败" :description="testError" type="error" :closable="false" show-icon />
      <el-alert v-else-if="testResult" :title="`${testResult.ok ? '模型可用' : '测试失败'} · ${testResult.elapsedMs} ms`"
        :description="testResult.output ?? testResult.message ?? undefined" :type="testResult.ok ? 'success' : 'error'" :closable="false" show-icon />
      <template #footer>
        <el-button @click="testDialog = false">关闭</el-button>
        <el-button type="primary" :loading="testing" :disabled="testLoading || !testModel.trim()" @click="runTest">开始测试</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="dispatchDialog" title="账号切换" width="min(800px, 94vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="密钥分组">
          <el-select v-model="dispatchGroupId" filterable :disabled="busy" aria-label="切换账号的分组">
            <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <p v-if="dispatchGroup" class="form-hint">
        <strong>{{ accountRoutingModes[dispatchGroup.routingMode ?? 'ORDER'].label }}</strong>
        {{ accountRoutingModes[dispatchGroup.routingMode ?? 'ORDER'].hint }}
        <span v-if="dispatchGroup.routingMode === 'SMART' || dispatchGroup.routingMode === 'ROTATE'">此处设置基础顺序。</span>
      </p>
      <el-alert v-if="dispatchGroup && !dispatchGroup.enabled" title="此分组已停用" type="warning" :closable="false" />
      <el-table :data="dispatchAccounts" row-key="id" empty-text="分组暂无账号">
        <el-table-column type="index" label="顺序" width="64" />
        <el-table-column label="账号" min-width="145">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <span v-if="row.id === dispatchGroup?.preferredAccountId" class="dispatch-tag">首选</span>
          </template>
        </el-table-column>
        <el-table-column label="调度状态" min-width="155">
          <template #default="{ row }">
            <span>{{ state(row) }}</span>
            <div v-if="state(row) === '冷却中'" class="dim">{{ displayTime(row.cooldownUntil) }} 恢复</div>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="默认优先级" width="104" />
        <el-table-column label="操作" width="184">
          <template #default="{ row, $index }">
            <div v-if="row.id !== dispatchGroup?.preferredAccountId" class="account-actions dispatch-actions">
              <el-button link type="primary" :disabled="busy || $index === 0 || dispatchAccounts[$index - 1]?.id === dispatchGroup?.preferredAccountId" @click="moveDispatchAccount($index, -1)">上移</el-button>
              <el-button link type="primary" :disabled="busy || $index === dispatchAccounts.length - 1" @click="moveDispatchAccount($index, 1)">下移</el-button>
              <el-button link type="primary" :disabled="busy" @click="preferAccount(row.id)">设为首选</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button v-if="dispatchGroup && (dispatchGroup.preferredAccountId != null || dispatchGroup.accountOrder.length)" :disabled="busy" @click="saveDispatchOrder([])">恢复默认顺序</el-button>
        <el-button @click="dispatchDialog = false">关闭</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="loginDialog" :title="reauthId === undefined ? '添加账号' : authMethod === 'api' ? '更换 API Key' : `重新授权 ${label}`" width="min(490px, 94vw)" destroy-on-close @closed="callback = ''; upstreamKey = ''">
      <el-form v-if="!pending" label-position="top">
        <el-form-item label="平台"><el-select v-model="authProvider" :disabled="reauthId !== undefined" @change="authMethod = 'oauth'; upstreamKey = ''"><el-option v-for="provider in providers" :key="provider" :label="accountPlatforms[provider]" :value="provider" /></el-select></el-form-item>
        <el-form-item v-if="reauthId === undefined && authProvider !== 'WORKBUDDY'" label="接入方式">
          <el-radio-group v-model="authMethod" @change="upstreamKey = ''">
            <el-radio-button value="oauth">账号授权</el-radio-button>
            <el-radio-button value="api">API Key</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="!reauthId && !pending && proxies.length" label="出站代理">
          <el-select v-model="authProxy"><el-option :value="0" label="直连" />
            <el-option v-for="proxy in proxies" :key="proxy.id" :label="proxy.name" :value="proxy.id" />
          </el-select>
        </el-form-item>
        <template v-if="authMethod === 'api'">
          <el-form-item v-if="reauthId === undefined" label="账号名称" required>
            <el-input v-model="keyName" maxlength="128" autocomplete="off" placeholder="输入账号名称" />
          </el-form-item>
          <el-form-item :label="authProvider === 'ANTHROPIC' ? 'Anthropic API Key' : 'OpenAI API Key'" required>
            <el-input v-model="upstreamKey" type="password" show-password autocomplete="new-password" placeholder="输入 API Key" />
          </el-form-item>
        </template>
        <el-form-item v-if="authProvider === 'WORKBUDDY'" label="账号站点"><el-select v-model="realm"><el-option label="国内站" value="cn" /><el-option label="国际站" value="global" /></el-select></el-form-item>
      </el-form>
      <el-form v-else label-position="top">
        <p class="form-hint">{{ pending.ticket ? '复制链接，在浏览器中完成授权，再返回管理台确认登录。' : authProvider === 'ANTHROPIC' ? '打开授权链接，在 Claude 完成登录后，将页面显示的验证码粘贴到下方。' : '复制链接，在浏览器中完成授权，然后将 localhost:1455 回调页的完整地址粘贴到下方。回调页无法连接时，仍可从地址栏复制。' }}</p>
        <el-form-item label="授权链接">
          <el-input :model-value="pending.url" readonly>
            <template #append><el-button @click="copyAuthLink">复制链接</el-button></template>
          </el-input>
        </el-form-item>
        <el-form-item v-if="pending.state" :label="authProvider === 'ANTHROPIC' ? 'Claude 授权验证码' : 'OAuth 回调地址'">
          <el-input v-model="callback" type="textarea" :rows="2" :placeholder="authProvider === 'ANTHROPIC' ? '粘贴授权验证码' : 'http://localhost:1455/auth/callback?code=…&state=…'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="loginDialog = false">关闭</el-button>
        <el-button v-if="!pending && reauthId === undefined && authProvider !== 'ANTHROPIC' && authMethod === 'oauth'" :icon="Upload"
          @click="loginDialog = false; openImport(authProvider)">{{ authProvider === 'WORKBUDDY' ? '导入凭证 JSON' : '导入账号 JSON' }}</el-button>
        <el-button v-if="authMethod === 'api'" type="primary" :loading="busy"
          :disabled="!upstreamKey.trim() || (reauthId === undefined && !keyName.trim())" @click="saveApiKey">保存</el-button>
        <el-button v-else-if="!pending" type="primary" :loading="busy" @click="begin()">生成授权链接</el-button>
        <el-button v-else-if="pending.ticket" type="primary" :loading="busy" @click="confirmWork">已完成登录</el-button>
        <template v-else>
          <el-button :disabled="busy" @click="begin()">重新生成链接</el-button>
          <el-button type="primary" :loading="busy" :disabled="!callback.trim()" @click="confirmOAuth">完成授权</el-button>
        </template>
      </template>
    </el-dialog>
    <el-dialog v-model="importDialog" :title="importProvider === 'WORKBUDDY' ? '导入 WorkBuddy 授权' : '导入 ChatGPT 账号'"
      width="min(560px, 94vw)" destroy-on-close @closed="accountInput = ''; importResult = undefined">
      <p class="form-hint">{{ importProvider === 'WORKBUDDY' ? '选择或粘贴单个 WorkBuddy2API 账号 JSON。' : '上传或粘贴 Cockpit Tools 或 Sub2API 导出的 JSON。系统自动识别格式，导入 ChatGPT / Codex OAuth 账号。' }} 凭证会加密存储。</p>
      <input type="file" accept=".json,application/json" aria-label="选择账号 JSON 文件" @change="readAccountFile" />
      <el-input v-model="accountInput" type="textarea" :rows="6" aria-label="账号 JSON" style="margin-top: 12px" />
      <div v-if="importResult" role="status">
        <p>已导入或更新 {{ importResult.ids.length }} 个，跳过 {{ importResult.skipped }} 个，失败 {{ importResult.errors.length }} 个。</p>
        <ul v-if="importResult.errors.length || importResult.warnings.length"><li v-for="(issue, index) in [...importResult.errors, ...importResult.warnings]" :key="index">第 {{ issue.row }} 个账号 · {{ issue.message }}</li></ul>
      </div>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button v-if="importResult?.ids.length === 1" @click="configureImported">配置账号</el-button>
        <el-button type="primary" :loading="busy" :disabled="!accountInput.trim()" @click="importAccount">导入</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="editDialog" title="编辑账号" width="min(640px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="saveEdit()">
        <div class="form-grid">
          <el-form-item label="账号名称"><el-input v-model="accountForm.name" maxlength="128" /></el-form-item>
          <el-form-item label="状态"><el-switch v-model="accountForm.enabled" active-text="已启用" inactive-text="已停用" /></el-form-item>
          <el-form-item label="默认分组"><el-input :model-value="groups.find(group => group.automatic && group.provider === editing?.provider)?.name ?? ''" readonly /></el-form-item>
          <el-form-item label="授权到期时间"><el-input :model-value="editing ? expiry(editing) : ''" readonly /></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="accountForm.options.notes" type="textarea" :rows="2" maxlength="512" /></el-form-item>
        <el-form-item label="出站代理">
          <el-select v-model="accountForm.options.proxyId" filterable>
            <el-option :value="0" label="直连" />
            <el-option v-for="proxy in proxies" :key="proxy.id" :label="`${proxy.name} · ${proxy.host}:${proxy.port}`" :value="proxy.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="自定义分组">
          <el-select v-model="accountForm.options.groupIds" multiple filterable collapse-tags collapse-tags-tooltip placeholder="选择同平台分组">
            <el-option v-for="group in editingGroups" :key="group.id" :label="group.name" :value="group.id" />
          </el-select>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="优先级">
            <el-input-number v-model="accountForm.options.priority" :precision="0" :min="0" :max="10000" />
            <div class="form-hint">数值越小，越优先调用。</div>
          </el-form-item>
          <el-form-item label="最大并发"><el-input-number v-model="accountForm.options.concurrency" :precision="0" :min="1" :max="1000" /></el-form-item>
          <el-form-item label="参与调度"><el-switch v-model="accountForm.options.schedulable" /></el-form-item>
        </div>
        <div class="mapping-panel">
          <div class="mapping-head"><h3>模型映射</h3><el-button :icon="Refresh" :loading="fetchingModels" @click="discover">读取上游模型</el-button></div>
          <p class="form-hint">左边是客户端使用的模型名，右边是此账号实际调用的上游模型。</p>
          <el-select v-if="!accountForm.mappings.length && mappingTemplates.length" :model-value="undefined" :disabled="busy"
            aria-label="复用模型映射" filterable placeholder="从同平台账号复制模型映射" @change="copyMappings">
            <el-option v-for="account in mappingTemplates" :key="account.id" :label="account.name" :value="account.id" />
          </el-select>
          <div v-for="(mapping, index) in accountForm.mappings" :key="index" class="mapping-row">
            <el-input v-model="mapping.model" maxlength="64" placeholder="客户端模型名" :aria-label="`客户端模型名 ${index + 1}`" />
            <span aria-hidden="true">→</span>
            <el-autocomplete v-model="mapping.upstreamModel" maxlength="128" placeholder="上游模型" :aria-label="`上游模型 ${index + 1}`"
              :fetch-suggestions="suggestModels" />
            <el-button link type="danger" :aria-label="`移除第 ${index + 1} 个映射`" @click="accountForm.mappings.splice(index, 1)">移除</el-button>
          </div>
          <div class="mapping-actions">
            <el-button plain :icon="Plus" :disabled="accountForm.mappings.length >= 100" @click="accountForm.mappings.push({model:'', upstreamModel:''})">添加映射</el-button>
            <el-button v-if="discovered.length" link type="primary" @click="addDiscoveredModels">添加可用模型</el-button>
          </div>
        </div>
      </el-form>
      <template #footer>
        <div class="page-actions dialog-actions">
          <el-button @click="editDialog = false">取消</el-button>
          <el-button :loading="busy" :disabled="!canSave || !accountForm.enabled || !accountForm.mappings.length" @click="saveEdit(true)">保存并创建密钥</el-button>
          <el-button type="primary" :loading="busy" :disabled="!canSave" @click="saveEdit()">保存账号</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.account-actions { display: flex; align-items: center; gap: 12px; white-space: nowrap; }
.dispatch-actions { gap: 0; }
.account-toolbar { display: flex; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; gap: 10px; padding: 14px 16px; }
.account-filters { display: grid; grid-template-columns: minmax(138px, 206px) repeat(3, minmax(94px, 116px)); gap: 8px; padding: 0; border: 0; }
.account-filters :deep(.el-input), .account-filters :deep(.el-select) { width: 100%; }
.column-options { display: grid; }
.account-table :deep(.el-table__header .cell) { white-space: nowrap; }
.account-tools { flex: 0 0 auto; }
.workbuddy-credits { font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.capacity-pill { font-size: 12px; font-variant-numeric: tabular-nums; color: var(--el-text-color-secondary); white-space: nowrap; }
.capacity-full { color: var(--el-color-warning); }
.dispatch-tag { margin-inline-start: 8px; color: var(--el-color-primary); font-size: 12px; font-weight: 600; white-space: nowrap; }
</style>
