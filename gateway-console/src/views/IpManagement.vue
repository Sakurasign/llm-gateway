<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { parseIps } from '../api/display'
import type { AccountGroup, GatewayKey } from '../api/types'
import IpRulesFields from '../components/IpRulesFields.vue'
import AccountProxies from '../components/AccountProxies.vue'

const tab = ref('proxies')

const keys = ref<GatewayKey[]>([])
const groups = ref<AccountGroup[]>([])
const search = ref('')
const groupId = ref<number>()
const filter = ref<'limited' | 'unlimited'>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const dialog = ref(false)
const editing = ref<GatewayKey>()
const allow = ref('')
const deny = ref('')
const groupNames = computed(() => new Map(groups.value.map(group => [group.id, group.name])))
const visible = computed(() => keys.value.filter(key =>
  (!groupId.value || key.groupId === groupId.value) &&
  (!filter.value || Boolean(key.ipAllow.length || key.ipDeny.length) === (filter.value === 'limited')) &&
  [key.name, key.prefix, key.lastUsedIp ?? '', ...key.ipAllow, ...key.ipDeny].some(value => value.toLowerCase().includes(search.value.trim().toLowerCase()))
))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [keyList, groupList] = await Promise.all([api.keys(), api.groups()])
    keys.value = keyList.data
    groups.value = groupList.data
  } catch (e) { error.value = (e as Error).message }
  finally { loading.value = false }
}
function edit(key: GatewayKey) {
  editing.value = key
  allow.value = key.ipAllow.join('\n')
  deny.value = key.ipDeny.join('\n')
  dialog.value = true
}
async function save() {
  if (!editing.value || saving.value) return
  saving.value = true
  try {
    await api.saveKeyIps(editing.value.id, parseIps(allow.value), parseIps(deny.value))
    dialog.value = false
    await load()
    ElMessage.success('IP 规则已保存')
  } catch (e) { ElMessage.error((e as Error).message) }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <section class="workspace-page" aria-labelledby="ips-heading">
    <div class="page-heading"><h1 id="ips-heading">IP 管理</h1></div>
    <el-tabs v-model="tab" aria-label="IP 管理分类">
      <el-tab-pane label="出站代理" name="proxies" />
      <el-tab-pane label="密钥 IP 规则" name="keys" />
    </el-tabs>
    <AccountProxies v-if="tab === 'proxies'" />
    <template v-else>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-error" />
    <div class="panel">
      <div class="filter-bar management-filters">
        <el-input v-model="search" placeholder="搜索密钥或 IP" aria-label="搜索密钥或 IP" :prefix-icon="Search" clearable />
        <el-select v-model="groupId" placeholder="全部分组" aria-label="分组筛选" clearable>
          <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
        </el-select>
        <el-select v-model="filter" placeholder="全部限制状态" aria-label="IP 限制筛选" clearable>
          <el-option label="已限制" value="limited" /><el-option label="不限" value="unlimited" />
        </el-select>
        <el-button class="refresh-action" :icon="Refresh" aria-label="刷新 IP 规则" @click="load" />
      </div>
      <p class="table-scroll-hint">横向滑动可查看 IP 规则和操作</p>
      <el-table v-loading="loading" :data="visible" row-key="id" empty-text="暂无匹配的密钥">
        <el-table-column prop="name" label="密钥名称" min-width="156" sortable />
        <el-table-column label="分组" min-width="120"><template #default="{ row }">{{ groupNames.get(row.groupId) ?? '旧账号绑定' }}</template></el-table-column>
        <el-table-column label="最近访问 IP" min-width="156"><template #default="{ row }"><span class="mono">{{ row.lastUsedIp ?? '—' }}</span></template></el-table-column>
        <el-table-column label="允许名单" min-width="160"><template #default="{ row }"><span class="mono ip-preview" :title="row.ipAllow.join(', ')">{{ row.ipAllow.join(', ') || '不限' }}</span></template></el-table-column>
        <el-table-column label="拒绝名单" min-width="160"><template #default="{ row }"><span class="mono ip-preview" :title="row.ipDeny.join(', ')">{{ row.ipDeny.join(', ') || '—' }}</span></template></el-table-column>
        <el-table-column label="操作" width="115"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑 IP 规则</el-button></template></el-table-column>
      </el-table>
      <div class="management-count dim">共 {{ visible.length }} 把密钥</div>
    </div>
    <el-dialog v-model="dialog" title="编辑 IP 规则" width="min(520px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="密钥名称"><el-input :model-value="editing?.name" readonly /></el-form-item>
        <IpRulesFields v-model:allow="allow" v-model:deny="deny" />
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存规则</el-button>
      </template>
    </el-dialog>
    </template>
  </section>
</template>
