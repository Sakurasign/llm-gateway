<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api/client'
import type { CodexWakeupInput, CodexWakeupOverview, CodexWakeupTask, UpstreamAccount } from '../api/types'

const open = defineModel<boolean>({ required: true })
const props = defineProps<{ accounts: UpstreamAccount[] }>()
const overview = ref<CodexWakeupOverview>({ tasks: [], history: [] })
const editing = ref<CodexWakeupInput>()
const editingId = ref<string>()
const busy = ref(false)
const error = ref('')
const models = ref<string[]>([])
const tab = ref('tasks')
const accountNames = computed(() => new Map(props.accounts.map(account => [account.id, account.name])))
const codex = computed(() => props.accounts.filter(account => account.provider === 'CODEX_SUBSCRIPTION' && account.realm !== 'api'))
const kinds = { daily: '每天', weekly: '每周', interval: '固定间隔', quota_reset: '额度重置后', startup: '网关启动后' }
const windows = { either: '任一额度窗口', '5h': '5 小时额度', week: '周额度' }
const weekdays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const date = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'short', hourCycle: 'h23' })
const time = (value?: number | null) => value ? date.format(value * 1000) : '—'
const name = (id: number) => accountNames.value.get(id) ?? `账号 #${id}`
const scheduleLabel = ({ schedule: s }: CodexWakeupInput) => {
  if (s.kind === 'daily') return `每天 ${s.time}`
  if (s.kind === 'weekly') return `${(s.days ?? []).map(day => weekdays[day - 1]).join('、')} ${s.time}`
  if (s.kind === 'interval') return `每 ${s.hours} 小时`
  if (s.kind === 'startup') return `网关启动后 ${s.delay} 分钟`
  return `${windows[s.window ?? 'either']}重置后 ${s.delay} 分钟`
}
let timer: number | undefined
let loading = false

async function load(showError = true) {
  if (loading) return
  loading = true
  try { overview.value = await api.codexWakeups() }
  catch (failure) { if (showError) error.value = failure instanceof Error ? failure.message : '无法加载唤醒任务' }
  finally { loading = false }
}
watch(open, active => {
  window.clearInterval(timer)
  editing.value = undefined
  error.value = ''
  tab.value = 'tasks'
  if (!active) return
  void load()
  timer = window.setInterval(() => { if (!editing.value && !busy.value) void load(false) }, 4000)
}, { immediate: true })
onUnmounted(() => window.clearInterval(timer))

function edit(task?: CodexWakeupTask) {
  error.value = ''
  models.value = []
  editingId.value = task?.id
  editing.value = task ? JSON.parse(JSON.stringify(task.input)) as CodexWakeupInput : {
    name: '', enabled: false, accountIds: [], model: '', prompt: 'hi', effort: '',
    schedule: { kind: 'daily', time: '09:00', days: [], hours: 4, delay: 0, window: 'either' }
  }
}
async function loadModels() {
  const id = editing.value?.accountIds[0]
  if (!id) return
  try { models.value = (await api.accountModels(id)).data }
  catch (failure) { ElMessage.error(failure instanceof Error ? failure.message : '无法获取模型') }
}
async function act(action: () => Promise<unknown>, success: string) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await action()
    editing.value = undefined
    await load()
    ElMessage.success(success)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '操作失败'
  } finally { busy.value = false }
}
function save() {
  const input = editing.value
  if (input) void act(() => api.saveCodexWakeup(input, editingId.value), '任务已保存')
}
async function remove(task: CodexWakeupTask) {
  try { await ElMessageBox.confirm(`删除任务「${task.input.name}」？`, '删除定时任务', { type: 'warning' }) }
  catch { return }
  await act(() => api.deleteCodexWakeup(task.id), '任务已删除')
}
</script>

<template>
  <el-dialog v-model="open" title="定时唤醒" width="min(980px, 94vw)" destroy-on-close>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="wakeup-error" />
    <el-form v-if="editing" label-position="top" @submit.prevent="save">
      <div class="wakeup-fields">
        <el-form-item label="任务名称" required><el-input v-model="editing.name" maxlength="80" /></el-form-item>
        <el-form-item label="启用定时唤醒"><el-switch v-model="editing.enabled" aria-label="启用定时唤醒" /></el-form-item>
        <el-form-item label="ChatGPT / Codex 账号" required>
          <el-select v-model="editing.accountIds" multiple filterable collapse-tags placeholder="选择账号" @change="models = []">
            <el-option v-for="account in codex" :key="account.id" :label="account.name" :value="account.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="上游模型" required>
          <div class="wakeup-model">
            <el-select v-model="editing.model" filterable allow-create default-first-option placeholder="填写或选择模型">
              <el-option v-for="model in models" :key="model" :label="model" :value="model" />
            </el-select>
            <el-button :disabled="!editing.accountIds.length" @click="loadModels">获取模型</el-button>
          </div>
        </el-form-item>
        <el-form-item label="触发方式" required>
          <el-select v-model="editing.schedule.kind" aria-label="触发方式">
            <el-option v-for="(label, value) in kinds" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editing.schedule.kind === 'daily' || editing.schedule.kind === 'weekly'" label="触发时间（北京时间）" required>
          <el-time-picker v-model="editing.schedule.time" format="HH:mm" value-format="HH:mm" :clearable="false" />
        </el-form-item>
        <el-form-item v-if="editing.schedule.kind === 'weekly'" label="星期" required class="wakeup-wide">
          <el-checkbox-group v-model="editing.schedule.days">
            <el-checkbox v-for="(label, index) in weekdays" :key="label" :value="index + 1">{{ label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-if="editing.schedule.kind === 'interval'" label="间隔（小时）" required>
          <el-input-number v-model="editing.schedule.hours" :min="1" :max="168" :precision="0" />
        </el-form-item>
        <el-form-item v-if="editing.schedule.kind === 'quota_reset'" label="额度窗口">
          <el-select v-model="editing.schedule.window">
            <el-option v-for="(label, value) in windows" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editing.schedule.kind === 'quota_reset' || editing.schedule.kind === 'startup'" label="延迟（分钟）">
          <el-input-number v-model="editing.schedule.delay" :min="0" :max="1440" :precision="0" />
        </el-form-item>
        <el-form-item label="推理强度">
          <el-select v-model="editing.effort">
            <el-option label="默认" value="" /><el-option label="低" value="low" />
            <el-option label="中" value="medium" /><el-option label="高" value="high" /><el-option label="极高" value="xhigh" />
          </el-select>
        </el-form-item>
        <el-form-item label="唤醒提示词" class="wakeup-wide">
          <el-input v-model="editing.prompt" type="textarea" :rows="2" maxlength="1000" placeholder="hi" />
        </el-form-item>
      </div>
    </el-form>
    <el-tabs v-else v-model="tab">
      <el-tab-pane label="定时任务" name="tasks">
        <p class="form-hint">任务由网关执行。每次唤醒会向所选账号发送一条请求，使用该账号的模型额度。</p>
        <div class="page-actions wakeup-actions"><el-button type="primary" @click="edit()">新建任务</el-button></div>
        <el-table :data="overview.tasks" row-key="id" empty-text="暂无定时唤醒任务" max-height="400">
          <el-table-column label="任务" min-width="164"><template #default="{ row }"><strong>{{ row.input.name }}</strong><div class="dim">{{ row.input.model }}</div></template></el-table-column>
          <el-table-column label="账号" min-width="135"><template #default="{ row }">{{ row.input.accountIds.map(name).join('、') }}</template></el-table-column>
          <el-table-column label="触发规则" min-width="153"><template #default="{ row }">{{ scheduleLabel(row.input) }}</template></el-table-column>
          <el-table-column label="下次执行（北京时间）" min-width="154"><template #default="{ row }">{{ row.running ? '执行中' : !row.input.enabled ? '已停用' : time(row.nextRunAt) }}</template></el-table-column>
          <el-table-column label="操作" width="184"><template #default="{ row }">
            <el-button link type="primary" :disabled="busy || row.running" @click="act(() => api.runCodexWakeup(row.id), '唤醒已提交')">立即唤醒</el-button>
            <el-button link type="primary" :disabled="busy || row.running" @click="edit(row)">编辑</el-button>
            <el-button link type="danger" :disabled="busy || row.running" @click="remove(row)">删除</el-button>
          </template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="执行记录" name="history">
        <el-table :data="overview.history" empty-text="暂无执行记录" max-height="420">
          <el-table-column label="执行时间（北京时间）" min-width="150"><template #default="{ row }">{{ time(row.runAt) }}</template></el-table-column>
          <el-table-column label="任务与账号" min-width="170"><template #default="{ row }"><strong>{{ row.name }}</strong><div class="dim">{{ name(row.accountId) }}</div></template></el-table-column>
          <el-table-column label="触发方式" width="115"><template #default="{ row }">{{ row.trigger === 'manual' ? '立即唤醒' : kinds[row.trigger as keyof typeof kinds] }}</template></el-table-column>
          <el-table-column label="结果" width="85"><template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'" effect="plain">{{ row.success ? '成功' : '失败' }}</el-tag></template></el-table-column>
          <el-table-column prop="message" label="回复或失败原因" min-width="215" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <template v-if="editing">
        <el-button @click="editing = undefined; error = ''">返回</el-button>
        <el-button type="primary" :loading="busy" :disabled="!editing.name.trim() || !editing.model.trim() || !editing.accountIds.length" @click="save">保存任务</el-button>
      </template>
      <el-button v-else @click="open = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.wakeup-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 20px; }
.wakeup-wide { grid-column: 1 / -1; }
.wakeup-model { display: flex; gap: 8px; width: 100%; }
.wakeup-model .el-select { flex: 1; }
.wakeup-actions, .wakeup-error { margin-bottom: 12px; }
</style>
