import { useQuery } from '@tanstack/react-query'
import { useCallback, useRef, useState } from 'react'

import {
  fetchConversationMessages,
  sendChatMessage,
  type ChatIntent,
  type ChatSource,
} from '@/api/chat'
import { getApiError } from '@/api/client'
import { uuid } from '@/lib/uuid'

export interface ChatItem {
  key: string
  role: 'user' | 'assistant'
  content: string
  intent?: ChatIntent | null
  sources: ChatSource[]
  loginRequired?: boolean
}

interface OlderPages {
  items: ChatItem[]
  /** undefined = not paged yet (use the first page's cursor). */
  cursor?: string | null
}

/**
 * Chat state for one conversation. Past messages come from the server (only for an opened
 * conversation); messages of this session are kept locally so guests, who have no history, work
 * the same way. A failed send keeps the question in `failed` (Figma 3.61).
 */
export function useAssistantChat(initialConversationId: string | null) {
  const [conversationId, setConversationId] = useState(initialConversationId)
  const [local, setLocal] = useState<ChatItem[]>([])
  const [older, setOlder] = useState<OlderPages>({ items: [] })
  const [sending, setSending] = useState(false)
  const [failed, setFailed] = useState<string | null>(null)
  const [loadingOlder, setLoadingOlder] = useState(false)
  const busy = useRef(false)

  const history = useQuery({
    queryKey: ['assistant', 'messages', initialConversationId],
    queryFn: () => fetchConversationMessages(initialConversationId as string),
    enabled: !!initialConversationId,
    staleTime: 0,
  })

  const base: ChatItem[] = (history.data?.messages ?? []).map((m) => ({
    key: m.id,
    role: m.role,
    content: m.content,
    intent: m.intent,
    sources: m.sources ?? [],
  }))
  const cursor = older.cursor === undefined ? (history.data?.nextCursor ?? null) : older.cursor
  const items = [...older.items, ...base, ...local]

  const loadOlder = useCallback(async () => {
    if (!initialConversationId || !cursor || loadingOlder) return
    setLoadingOlder(true)
    try {
      const page = await fetchConversationMessages(initialConversationId, cursor)
      const mapped: ChatItem[] = page.messages.map((m) => ({
        key: m.id,
        role: m.role,
        content: m.content,
        intent: m.intent,
        sources: m.sources ?? [],
      }))
      setOlder((o) => ({ items: [...mapped, ...o.items], cursor: page.nextCursor }))
    } finally {
      setLoadingOlder(false)
    }
  }, [initialConversationId, cursor, loadingOlder])

  const send = useCallback(
    async (text: string) => {
      const message = text.trim()
      if (!message || busy.current) return
      busy.current = true
      setSending(true)
      setFailed(null)
      const userKey = uuid()
      setLocal((l) => [...l, { key: userKey, role: 'user', content: message, sources: [] }])
      try {
        let id = conversationId
        let reply
        try {
          reply = await sendChatMessage(message, id)
        } catch (e) {
          // The conversation vanished (or never belonged to this user): start a new one.
          if (!id || getApiError(e).code !== 'CONVERSATION_NOT_FOUND') throw e
          id = null
          reply = await sendChatMessage(message, null)
        }
        setConversationId(reply.conversationId ?? id)
        setLocal((l) => [
          ...l,
          {
            key: reply.messageId ?? uuid(),
            role: 'assistant',
            content: reply.reply,
            intent: reply.intent,
            sources: reply.sources,
            loginRequired: reply.loginRequired,
          },
        ])
      } catch {
        setLocal((l) => l.filter((i) => i.key !== userKey))
        setFailed(message)
      } finally {
        busy.current = false
        setSending(false)
      }
    },
    [conversationId],
  )

  return {
    items,
    conversationId,
    sending,
    failed,
    clearFailed: () => setFailed(null),
    send,
    historyLoading: !!initialConversationId && history.isPending,
    historyError: history.isError ? history.error : null,
    refetchHistory: history.refetch,
    hasOlder: !!cursor,
    loadingOlder,
    loadOlder,
  }
}
