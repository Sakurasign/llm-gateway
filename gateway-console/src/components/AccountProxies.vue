<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import type { AccountProxy, AccountProxyInput } from '../api/types'

const entries = ref<AccountProxy[]>([])
const search = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const editing = ref<AccountProxy>()
const dialog = ref(false)
const statuses = ref<Record<number, string>>({})
const form = ref<AccountProxyInput>({ name: '', protocol: 'http', host: '', port: 8080, username: null })
const visible = computed(() => entries.value.filter(row =>
  `${row.name} ${row.host}`.toLowerCase().includes(search.value.trim().toLowerCase())))

async function load() {
  loading.value = true
  error.value = ''
  try { entries.value = (await api.proxies()).data }
  catch (e) { error.value = (e as Error).message }
  finally { loading.value = false }
}
function open(row?: AccountProxy) {
  editing.value = row
  form.value = row ? { name: row.name, protocol: row.protocol, host: row.host, port: row.port, username: row.username }
    : { name: '', protocol: 'http', host: '', port: 8080, username: null }
  dialog.value = true
}
async function act(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try { await action() }
  catch (e) { ElMessage.error((e as Error).message) }
  finally { busy.value = false }
}
function save() {
  if (!form.value.name.trim() || !form.value.host.trim() || !form.value.port) return
  void act(async () => {
    await api.saveProxy(form.value, editing.value?.id)
    if (editing.value) delete statuses.value[editing.value.id]
    dialog.value = false
    await load()
    ElMessage.success('代理已保存')
  })
}
function test(id: number) {
  void act(async () => {
    statuses.value[id] = '测试中'
    try {
      const result = await api.testProxy(id)
      statuses.value[id] = `已连通 · HTTP ${result.upstreamStatus}`
    } catch (e) {
      statuses.value[id] = '连接失败'
      throw e
    }
  })
}
async function remove(row: AccountProxy) {
  try { await ElMessageBox.confirm(`删除「${row.name}」？`, '删除代理', { type: 'warning' }) }
  catch { return }
  void act(async () => { await api.deleteProxy(row.id); await load(); ElMessage.success('代理已删除') })
}
onMounted(load)
</script>

<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-error" />
  <div class="panel">
    <div class="filter-bar management-filters">
      <el-input v-model="search" placeholder="搜索代理名称或地址" aria-label="搜索代理" :prefix-icon="Search" clearable />
      <el-button class="refresh-action" :icon="Refresh" aria-label="刷新代理" @click="load" />
      <el-button type="primary" :icon="Plus" @click="open()">添加代理</el-button>
    </div>
    <el-table v-loading="loading" :data="visible" row-key="id" empty-text="暂无出站代理">
      <el-table-column prop="name" label="名称" sortable min-width="150" />
      <el-table-column label="代理地址" min-width="200"><template #default="{ row }"><span class="mono">{{ row.protocol }}://{{ row.host.includes(':') ? `[${row.host}]` : row.host }}:{{ row.port }}</span></template></el-table-column>
      <el-table-column label="账号" prop="accounts" width="80" />
      <el-table-column label="连接状态" min-width="145"><template #default="{ row }">{{ statuses[row.id] ?? '未测试' }}</template></el-table-column>
      <el-table-column label="操作" width="162"><template #default="{ row }">
        <el-button link type="primary" @click="open(row)">编辑</el-button>
        <el-button link :disabled="busy" @click="test(row.id)">测试</el-button>
        <el-button link type="danger" :disabled="busy || row.accounts > 0" @click="remove(row)">删除</el-button>
      </template></el-table-column>
    </el-table>
  </div>
  <el-dialog v-model="dialog" :title="editing ? '编辑代理' : '添加代理'" width="min(520px, 94vw)" destroy-on-close>
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="代理名称"><el-input v-model="form.name" maxlength="128" /></el-form-item>
      <el-form-item label="协议"><el-select v-model="form.protocol"><el-option label="HTTP CONNECT" value="http" /><el-option label="SOCKS5" value="socks5" /></el-select></el-form-item>
      <div class="form-grid">
        <el-form-item label="主机"><el-input v-model="form.host" placeholder="IP 或域名" /></el-form-item>
        <el-form-item label="端口"><el-input-number v-model="form.port" :min="1" :max="65535" :precision="0" /></el-form-item>
      </div>
      <el-form-item label="用户名"><el-input v-model="form.username" autocomplete="off" placeholder="可选" /></el-form-item>
      <el-form-item label="密码"><el-input v-model="form.password" type="password" autocomplete="new-password" :placeholder="editing?.hasPassword ? '留空保留已保存密码' : '可选'" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="!form.name.trim() || !form.host.trim() || !form.port" @click="save">保存代理</el-button>
    </template>
  </el-dialog>
</template>
