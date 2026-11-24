<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { accountRoutingModes } from '../api/display'
import { accountPlatforms } from '../api/protocols'
import type { AccountGroup, AccountGroupInput, AccountRoutingMode, UpstreamAccount } from '../api/types'

type Provider = UpstreamAccount['provider']
const providers = Object.keys(accountPlatforms) as Provider[]
const rows = ref<AccountGroup[]>([])
const accounts = ref<UpstreamAccount[]>([])
const search = ref('')
const provider = ref<Provider>()
const state = ref<'enabled' | 'disabled'>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dialog = ref(false)
const editing = ref<AccountGroup>()
const form = ref<AccountGroupInput>({ name: '', description: '', provider: providers[0], enabled: true, sortOrder: 100, accountIds: [], routingMode: 'ORDER' })
const candidates = computed(() => accounts.value.filter(account => account.provider === form.value.provider))
const visible = computed(() => rows.value.filter(group =>
  (!provider.value || group.provider === provider.value) &&
  (!state.value || group.enabled === (state.value === 'enabled')) &&
  [group.name, group.description ?? ''].some(text => text.toLowerCase().includes(search.value.trim().toLowerCase()))
))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [groupList, accountList] = await Promise.all([api.groups(), api.accounts()])
    rows.value = groupList.data
    accounts.value = accountList.data
  } catch (e) { error.value = (e as Error).message }
  finally { loading.value = false }
}
function open(group?: AccountGroup, copy = false) {
  editing.value = copy ? undefined : group
  form.value = group ? {
    name: copy ? `${group.name.slice(0, 57)} 副本` : group.name,
    description: group.description ?? '', provider: group.provider,
    enabled: group.enabled, sortOrder: group.sortOrder, accountIds: [...group.accountIds], routingMode: group.routingMode ?? 'ORDER'
  } : { name: '', description: '', provider: provider.value ?? providers[0], enabled: true, sortOrder: 100, accountIds: [], routingMode: 'ORDER' }
  dialog.value = true
}
async function save() {
  if (saving.value || !form.value.name.trim()) return
  saving.value = true
  try {
    await api.saveGroup({ ...form.value, name: form.value.name.trim() }, editing.value?.id)
    dialog.value = false
    await load()
    ElMessage.success('分组已保存')
  } catch (e) { ElMessage.error((e as Error).message) }
  finally { saving.value = false }
}
async function remove(group: AccountGroup) {
  try { await ElMessageBox.confirm(`删除「${group.name}」？`, '删除分组', { type: 'warning' }) }
  catch { return }
  try { await api.deleteGroup(group.id); await load(); ElMessage.success('分组已删除') }
  catch (e) { ElMessage.error((e as Error).message) }
}
onMounted(load)
</script>

<template>
  <section class="workspace-page" aria-labelledby="groups-heading">
    <div class="page-heading">
      <h1 id="groups-heading">分组管理</h1>
      <el-button type="primary" :icon="Plus" @click="open()">创建分组</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-error" />
    <div class="panel">
      <div class="filter-bar management-filters">
        <el-input v-model="search" placeholder="搜索分组" aria-label="搜索分组" :prefix-icon="Search" clearable />
        <el-select v-model="provider" placeholder="全部平台" aria-label="平台筛选" clearable>
          <el-option v-for="item in providers" :key="item" :label="accountPlatforms[item]" :value="item" />
        </el-select>
        <el-select v-model="state" placeholder="全部状态" aria-label="状态筛选" clearable>
          <el-option label="已启用" value="enabled" /><el-option label="已停用" value="disabled" />
        </el-select>
        <el-button class="refresh-action" :icon="Refresh" aria-label="刷新分组" @click="load" />
      </div>
      <p class="table-scroll-hint">横向滑动可查看分组操作</p>
      <el-table v-loading="loading" :data="visible" row-key="id" empty-text="没有匹配的分组">
        <el-table-column label="名称" min-width="170" sortable prop="name">
          <template #default="{ row }"><strong>{{ row.name }}</strong><div v-if="row.description" class="dim">{{ row.description }}</div></template>
        </el-table-column>
        <el-table-column label="平台" min-width="140">
          <template #default="{ row }"><el-tag effect="light">{{ accountPlatforms[row.provider as Provider] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="调度策略" width="108">
          <template #default="{ row }">{{ accountRoutingModes[(row.routingMode ?? 'ORDER') as AccountRoutingMode].label }}</template>
        </el-table-column>
        <el-table-column label="账号" min-width="100" prop="activeAccounts" sortable>
          <template #default="{ row }">{{ row.activeAccounts }} / {{ row.accountIds.length }} <small v-if="row.automatic" class="dim">自动</small></template>
        </el-table-column>
        <el-table-column label="密钥" width="85" prop="keyCount" sortable />
        <el-table-column label="排序" width="85" prop="sortOrder" sortable />
        <el-table-column label="状态" width="108">
          <template #default="{ row }"><span class="state-label" :class="{ enabled: row.enabled }"><i />{{ row.enabled ? '已启用' : '已停用' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="178">
          <template #default="{ row }">
            <el-button link type="primary" @click="open(row)">编辑</el-button>
            <el-button link @click="open(row, true)">复制</el-button>
            <el-button v-if="!row.automatic" link type="danger" :disabled="row.keyCount > 0" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="management-count dim">共 {{ visible.length }} 个分组</div>
    </div>
    <el-dialog v-model="dialog" :title="editing ? '编辑分组' : '创建分组'" width="min(560px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="分组名称"><el-input v-model="form.name" maxlength="64" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="255" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="平台">
            <el-select v-model="form.provider" :disabled="Boolean(editing)" @change="form.accountIds = []">
              <el-option v-for="item in providers" :key="item" :label="accountPlatforms[item]" :value="item" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" :max="10000" :precision="0" /></el-form-item>
        </div>
        <el-form-item label="调度策略">
          <el-select v-model="form.routingMode" aria-label="调度策略">
            <el-option v-for="(mode, value) in accountRoutingModes" :key="value" :label="mode.label" :value="value" />
          </el-select>
          <p class="form-hint">{{ accountRoutingModes[form.routingMode].hint }}</p>
        </el-form-item>
        <el-form-item v-if="editing?.automatic" label="账号成员"><p class="form-hint">自动纳入该平台的所有账号。</p></el-form-item>
        <el-form-item v-else label="账号成员">
          <el-select v-model="form.accountIds" multiple collapse-tags collapse-tags-tooltip filterable placeholder="选择同平台账号">
            <el-option v-for="account in candidates" :key="account.id" :label="account.name" :value="account.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.enabled" active-text="已启用" inactive-text="已停用" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!form.name.trim()" @click="save">保存分组</el-button>
      </template>
    </el-dialog>
  </section>
</template>
