<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api } from '../api/client'
import { protocols } from '../api/protocols'
import type { ChannelInfo, ModelRouteInfo, RoutePayload, VirtualModelInfo } from '../api/types'

const rows = ref<VirtualModelInfo[]>([])
const channels = ref<ChannelInfo[]>([])
const version = ref(0)
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const search = ref('')
const dialog = ref(false)
const id = ref<number>()
const form = ref<Omit<RoutePayload, 'channelId'> & { channelId?: number }>({ virtualModel: '', upstreamModel: '', weight: 100, priceRatio: 1 })
const filtered = computed(() => rows.value.flatMap(model => model.routes.map(route => ({ ...route, virtualModel: model.name })))
  .filter(route => [route.virtualModel, route.upstreamModel, route.channelName].some(value => value.toLowerCase().includes(search.value.trim().toLowerCase()))))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [models, upstreams] = await Promise.all([api.models(), api.channels()])
    rows.value = models.data
    channels.value = upstreams.data
    version.value = models.version
  } catch (e) { error.value = (e as Error).message }
  finally { loading.value = false }
}
function edit(route?: ModelRouteInfo & {virtualModel:string}) {
  id.value = route?.id
  form.value = route ? { virtualModel: route.virtualModel, upstreamModel: route.upstreamModel, channelId: route.channelId,
    weight: route.weight, priceRatio: route.priceRatio } : { virtualModel: '', upstreamModel: '', weight: 100, priceRatio: 1 }
  dialog.value = true
}
async function save() {
  const channelId = form.value.channelId
  if (channelId === undefined || !form.value.virtualModel.trim() || !form.value.upstreamModel.trim() || saving.value) return
  saving.value = true
  try { await api.saveRoute(version.value, { ...form.value, channelId }, id.value); ElMessage.success('模型绑定已发布'); dialog.value = false; await load() }
  catch (e) { ElMessage.error((e as Error).message) }
  finally { saving.value = false }
}
async function remove(route: ModelRouteInfo) {
  try { await ElMessageBox.confirm(`解除「${route.upstreamModel}」的模型绑定？`, '移除绑定', { type: 'warning' }) }
  catch { return }
  try { await api.deleteRoute(version.value, route.id); ElMessage.success('模型绑定已移除'); await load() }
  catch (e) { ElMessage.error((e as Error).message) }
}
onMounted(load)
</script>

<template>
  <section class="workspace-page" aria-labelledby="models-heading">
    <div class="page-heading">
      <div><h1 id="models-heading">通用渠道模型</h1><p>账号专用的模型映射在账号管理中编辑。</p></div>
      <el-button type="primary" :icon="Plus" :disabled="!channels.length" @click="edit()">新增绑定</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="page-error" />
    <div class="panel">
      <div class="panel-head"><div><h2>模型绑定</h2><span class="dim">{{ filtered.length }} 条 · v{{ version }}</span></div><el-button class="icon-action" :icon="Refresh" aria-label="刷新模型" @click="load" /></div>
      <div class="filter-bar"><el-input v-model="search" placeholder="搜索模型或渠道" :prefix-icon="Search" clearable aria-label="搜索模型" /></div>
      <p class="table-scroll-hint">横向滑动可查看状态和操作</p>
      <el-table v-loading="loading" :data="filtered" empty-text="没有匹配的模型绑定">
        <el-table-column prop="virtualModel" label="客户端模型" min-width="155" />
        <el-table-column prop="upstreamModel" label="上游模型" min-width="155" />
        <el-table-column label="渠道" min-width="148"><template #default="{ row }">{{ row.channelName }}<span class="dim model-protocol">{{ protocols[row.protocol as keyof typeof protocols]?.name }}</span></template></el-table-column>
        <el-table-column prop="weight" label="权重" width="72" />
        <el-table-column label="状态" width="92"><template #default="{ row }"><span class="state-label" :class="{ enabled: row.enabled }"><i />{{ row.enabled ? '已启用' : '已停用' }}</span></template></el-table-column>
        <el-table-column label="操作" width="115"><template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">移除</el-button></template></el-table-column>
      </el-table>
    </div>
    <el-dialog v-model="dialog" :title="id ? '编辑模型绑定' : '新增模型绑定'" width="min(520px, 94vw)" destroy-on-close>
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="客户端模型名称" required><el-input v-model="form.virtualModel" maxlength="64" placeholder="例如 gpt-work" /></el-form-item>
        <el-form-item label="上游渠道" required><el-select v-model="form.channelId" placeholder="选择上游渠道"><el-option v-for="row in channels" :key="row.id" :label="row.name" :value="row.id" /></el-select></el-form-item>
        <el-form-item label="上游模型 ID" required><el-input v-model="form.upstreamModel" maxlength="128" placeholder="填写提供方实际支持的型号" /></el-form-item>
        <div class="form-grid"><el-form-item label="权重"><el-input-number v-model="form.weight" :min="1" /></el-form-item><el-form-item label="价格倍率"><el-input-number v-model="form.priceRatio" :min="0.01" :step="0.05" /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!form.virtualModel.trim() || !form.upstreamModel.trim() || !form.channelId" @click="save">保存并发布</el-button></template>
    </el-dialog>
  </section>
</template>
