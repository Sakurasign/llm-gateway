export const protocols = {
  OPENAI_RESPONSES: { name: 'OpenAI Responses', mark: 'OR', color: 'jade' },
  OPENAI_CHAT: { name: 'OpenAI Chat', mark: 'OC', color: 'blue' },
  ANTHROPIC: { name: 'Anthropic', mark: 'A', color: 'rust' },
  GEMINI: { name: 'Gemini', mark: 'G', color: 'blue' },
  WORKBUDDY: { name: 'WorkBuddy', mark: 'W', color: 'amber' },
  CODEX_SUBSCRIPTION: { name: 'ChatGPT 订阅', mark: 'C', color: 'violet' }
} as const

export const accountPlatforms = {
  CODEX_SUBSCRIPTION: 'OpenAI / GPT',
  WORKBUDDY: protocols.WORKBUDDY.name,
  ANTHROPIC: 'Claude'
} as const
