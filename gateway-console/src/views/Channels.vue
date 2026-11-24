<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowUp, Box, CircleCheck, Connection, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { protocols } from '../api/protocols'
import type { ChannelInfo, ChannelPayload, ChannelProtocol } from '../api/types'

const rows = ref<ChannelInfo[]>([])
const version = ref(0)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const search = ref('')
const filter = ref<ChannelProtocol | 'all'>('all')
const dialog = ref(false)
const importing = ref(false)
const yaml = ref('')
const editing = ref<number>()
const defaults = (): ChannelPayload => ({ name: '', protocol: 'OPENAI_RESPONSES', baseUrl: '', apiKey: '', priority: 100,
  weight: 100, maxInflight: 200, timeoutMs: 60000, passthrough: true, enabled: true, apiKeyInQuery: false })
const form = ref(defaults())
const managed = computed(() => ['WORKBUDDY','CODEX_SUBSCRIPTION'].includes(form.value.protocol))
const enabled = computed(() => rows.value.filter(row => row.enabled).length)
const visible = computed(() => rows.value.filter(row => (filter.value === 'all' || row.protocol === filter.value)
  && [row.name, row.baseUrl, protocols[row.protocol]?.name].some(value => value?.toLowerCase().includes(search.value.trim().toLowerCase()))))

watch(() => form.value.protocol, protocol => {
  if (protocol === 'WORKBUDDY') form.value.baseUrl = 'https://copilot.tencent.com'
  if (protocol === 'CODEX_SUBSCRIPTION') form.value.baseUrl = 'https://chatgpt.com/backend-api/codex'
  if (managed.value) { form.value.apiKey = ''; form.value.apiKeyInQuery = false }
})

async function load() {
  loading.value = true
  error.value = ''
  try { const result = await api.channels(); rows.value = result.data; version.value = result.version }
  catch (e) { error.value = (e as Error).message }
  finally { loading.value = false }
}
function edit(channel?: ChannelInfo) {
  editing.value = channel?.id
  form.value = channel ? { ...channel, apiKey: '' } : defaults()
  dialog.value = true
}
async function action(run: () => Promise<unknown>, message: string) {
  saving.value = true
  try { await run(); ElMessage.success(message); await load(); return true }
  catch (e) { ElMessage.error((e as Error).message); return false }
  finally { saving.value = false }
}
async function save() {
  if (!form.value.name.trim() || !form.value.baseUrl.trim()) { ElMessage.warning('请填写渠道名称和上游地址'); return }
  if (await action(() => api.saveChannel(version.value, form.value, editing.value), '渠道已发布')) dialog.value = false
}
async function remove(row: ChannelInfo) {
  try { await ElMessageBox.confirm(`删除渠道「${row.name}」？仍有关联模型时，需先移除绑定。`, '删除渠道', { type: 'warning' }) }
  catch { return }
  await action(() => api.deleteChannel(version.value, row.id), '渠道已删除')
}
async function publishYaml() {
  if (!yaml.value.trim()) return
  if (await action(() => api.importConfig(version.value, yaml.value), 'YAML 配置已发布')) { importing.value = false; yaml.value = '' }
}
onMounted(load)
</script>

<template>
  <section class="workspace-page" aria-labelledby="channels-heading">
    <div class="page-heading">
      <div><h1 id="channels-heading">上游渠道</h1><p>配置提供方、连接凭证和请求优先级。</p></div>
      <div class="page-actions">
        <router-link class="el-button" to="/models">通用模型配置</router-link>
        <el-button :icon="Upload" @click="importing = true">导入 YAML</el-button>
        <el-button type="primary" :icon="Plus" @click="edit()">新增渠道</el-button>
      </div>
    </div>
    <div class="summary-grid">
      <div class="summary-card"><div class="summary-icon blue"><el-icon><Box /></el-icon></div><div><span>全部渠道</span><strong>{{ rows.length }}</strong></div></div>
      <div class="summary-card"><div class="summary-icon jade"><el-icon><CircleCheck /></el-icon></div><div><span>已启用</span><strong>{{ enabled }}</strong></div></div>
      <div class="summary-card"><div class="summary-icon violet"><el-icon><Connection /></el-icon></div><div><span>配置版本</span><strong>{{ version ? `v${version}` : '—' }}</strong></div></div>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-error" />
    <div class="panel">
      <div class="panel-head"><div><h2>渠道列表</h2><span class="dim">{{ visible.length }} 个渠道</span></div><el-button class="icon-action" :icon="Refresh" aria-label="刷新渠道" @click="load" /></div>
      <div class="filter-bar">
        <el-input v-model="search" placeholder="搜索渠道或上游地址" :prefix-icon="Search" clearable aria-label="搜索渠道" />
        <el-select v-model="filter" aria-label="按协议筛选">
          <el-option label="全部协议" value="all" />
          <el-option v-for="(item, key) in protocols" :key="key" :label="item.name" :value="key" />
        </el-select>
      </div>
      <div v-loading="loading" class="provider-list">
        <div v-for="row in visible" :key="row.id" class="provider-row">
          <span class="provider-mark" :class="protocols[row.protocol]?.color">{{ protocols[row.protocol]?.mark }}</span>
          <div class="provider-name"><strong>{{ row.name }}</strong><span class="mono dim">{{ row.baseUrl }}</span></div>
          <span class="protocol-label">{{ protocols[row.protocol]?.name ?? row.protocol }}</span>
          <span class="state-label" :class="{ enabled: row.enabled }"><i />{{ row.enabled ? '已启用' : '已停用' }}</span>
          <div class="row-actions">
            <el-button size="small" @click="edit(row)">编辑</el-button>
            <el-dropdown trigger="click">
              <el-button size="small" :icon="ArrowUp" :aria-label="`更多操作 ${row.name}`" />
              <template #dropdown><el-dropdown-menu>
                <el-dropdown-item @click="action(() => api.statusChannel(version, row.id, !row.enabled), '状态已发布')">{{ row.enabled ? '停用' : '启用' }}</el-dropdown-item>
                <el-dropdown-item divided @click="remove(row)">删除</el-dropdown-item>
              </el-dropdown-menu></template>
            </el-dropdown>
          </div>
        </div>
        <el-empty v-if="!loading && !visible.length" :image-size="72" :description="rows.length ? '没有匹配的渠道' : '还没有配置渠道'" />
      </div>
    </div>

    <el-dialog v-model="dialog" :title="editing ? '编辑渠道' : '新增渠道'" width="min(560px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="save">
        <div class="form-grid">
          <el-form-item label="渠道名称" required><el-input v-model="form.name" maxlength="64" placeholder="例如 WorkBuddy" /></el-form-item>
          <el-form-item label="协议" required><el-select v-model="form.protocol"><el-option v-for="(item, key) in protocols" :key="key" :label="item.name" :value="key" /></el-select></el-form-item>
        </div>
        <el-form-item label="上游根地址" required><el-input v-model="form.baseUrl" :disabled="managed" placeholder="https://api.openai.com" /></el-form-item>
        <el-form-item v-if="!managed" label="上游 API Key"><el-input v-model="form.apiKey" type="password" show-password :placeholder="editing ? '留空保留现有凭证' : '写入后加密保存'" /></el-form-item>
        <p v-else class="form-hint">在 <router-link to="/accounts" @click="dialog = false">账号池</router-link> 添加授权账号。</p>
        <div class="form-grid">
          <el-form-item label="优先级"><el-input-number v-model="form.priority" :min="0" /></el-form-item>
          <el-form-item label="权重"><el-input-number v-model="form.weight" :min="1" /></el-form-item>
          <el-form-item label="并发上限"><el-input-number v-model="form.maxInflight" :min="1" :max="100000" /></el-form-item>
          <el-form-item label="超时（毫秒）"><el-input-number v-model="form.timeoutMs" :min="100" :max="600000" :step="1000" /></el-form-item>
        </div>
        <div class="checkboxes"><el-checkbox v-model="form.enabled">启用渠道</el-checkbox><el-checkbox v-model="form.passthrough">允许透传</el-checkbox><el-checkbox v-if="form.protocol === 'GEMINI'" v-model="form.apiKeyInQuery">Query 鉴权</el-checkbox></div>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存并发布</el-button></template>
    </el-dialog>
    <el-dialog v-model="importing" title="导入 YAML" width="min(620px, 94vw)" destroy-on-close>
      <p class="form-hint">按渠道 ID 和模型绑定更新，每次导入作为一个配置版本发布。</p>
      <el-input v-model="yaml" type="textarea" :rows="11" placeholder="gateway:" aria-label="YAML 配置" />
      <template #footer><el-button @click="importing = false; yaml = ''">取消</el-button><el-button type="primary" :loading="saving" :disabled="!yaml.trim()" @click="publishYaml">导入并发布</el-button></template>
    </el-dialog>
  </section>
</template>
