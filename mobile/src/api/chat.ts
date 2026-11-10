import { api } from '@/api/client'

export type ChatIntent = 'answer' | 'out_of_scope' | 'handoff' | 'order_lookup' | 'small_talk'

export interface ChatSource {
  id: string
  title: string
  source: string
  docId: string
}

export interface ChatReply {
  conversationId: string | null
  messageId: string | null
  reply: string
  intent: ChatIntent
  sources: ChatSource[]
  handoff: { suggested: boolean; ticketId: string | null }
  loginRequired: boolean
}

export interface ChatMessage {
  id: string
  turnId: string | null
  role: 'user' | 'assistant'
  content: string
  intent: ChatIntent | null
  sources: ChatSource[]
  createdAt: string
}

export interface ChatMessagePage {
  conversationId: string
  messages: ChatMessage[]
  hasMore: boolean
  nextCursor: string | null
}

export interface ChatConversation {
  id: string
  title: string
  messageCount: number
  createdAt: string
  updatedAt: string
}

/** Body is exactly `{message, conversationId}`: the service rejects any other field (userId). */
export async function sendChatMessage(message: string, conversationId: string | null) {
  const body = conversationId ? { message, conversationId } : { message }
  return (await api.post<ChatReply>('/api/ai/chat/messages', body)).data
}

export async function fetchConversations() {
  const data = (await api.get<{ conversations: ChatConversation[] }>('/api/ai/chat/conversations'))
    .data
  return data.conversations
}

export async function fetchConversationMessages(conversationId: string, before?: string) {
  return (
    await api.get<ChatMessagePage>(`/api/ai/chat/conversations/${conversationId}/messages`, {
      params: { limit: 100, before },
    })
  ).data
}
