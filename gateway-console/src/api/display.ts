import type { AccountRoutingMode } from './types'

export const accountRoutingModes: Record<AccountRoutingMode, { label: string; hint: string }> = {
  ORDER: { label: '按顺序', hint: '首选账号置顶，之后依次尝试备用账号。' },
  SMART: { label: '智能', hint: '同一会话优先使用上次成功的账号。新会话参考 Codex 额度重置时间，缺少额度数据时按账号顺序尝试。' },
  ROTATE: { label: '轮转', hint: '每个请求轮换起始账号，遇到不可用账号时继续尝试。' }
}

export const parseIps = (value: string) => [...new Set(value.split(/[\s,，]+/).filter(Boolean))]

const formatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short', hourCycle: 'h23' })
export function displayTime(seconds?: number | null, empty = '—') {
  return seconds ? formatter.format(seconds * 1000) : empty
}
