import { useQuery } from '@tanstack/react-query'
import { router } from 'expo-router'
import { useEffect, useState } from 'react'

import { fetchConversations } from '@/api/chat'
import { useAuth } from '@/auth/AuthProvider'
import { useRequireAuth } from '@/auth/useRequireAuth'
import { AppText, Button, ErrorState, InfoCard, Screen, Skeleton } from '@/components/ui'
import { assistantHref } from '@/features/assistant/routes'
import { lastUsedLabel } from '@/features/assistant/history'
import { colors } from '@/theme'

function openConversation(id: string | null) {
  router.dismissTo(assistantHref('', { conversationId: id ?? '' }))
}

export default function AssistantHistoryScreen() {
  const { status } = useAuth()
  const requireAuth = useRequireAuth()
  const [selected, setSelected] = useState<string | null>(null)
  const q = useQuery({
    queryKey: ['assistant', 'conversations'],
    queryFn: fetchConversations,
    enabled: status === 'authenticated',
    staleTime: 0,
  })

  useEffect(() => {
    if (status === 'guest') requireAuth('/assistant/history')
  }, [status, requireAuth])

  const conversations = q.data ?? []
  const current = selected ?? conversations[0]?.id ?? null

  return (
    <Screen
      testID="assistant-history-screen"
      title="Lịch sử hội thoại"
      onBack={() => router.back()}
      refreshing={q.isRefetching}
      onRefresh={() => void q.refetch()}
    >
      {status !== 'authenticated' || q.isPending ? (
        <>
          <Skeleton height={88} />
          <Skeleton height={88} />
        </>
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <>
          <AppText>
            {conversations.length === 0 ? 'Bạn chưa có hội thoại nào.' : 'Hội thoại của bạn'}
          </AppText>
          {conversations.map((c) => (
            <InfoCard
              key={c.id}
              testID={`conversation-${c.id}`}
              title={c.title}
              body={`Lần gần nhất: ${lastUsedLabel(c.updatedAt)}\n${c.messageCount} tin nhắn`}
              onPress={() => setSelected(c.id)}
              style={
                c.id === current
                  ? { borderColor: colors.primary600, backgroundColor: colors.primary50 }
                  : undefined
              }
            />
          ))}
          {current ? (
            <Button
              testID="history-open"
              title="Mở hội thoại"
              onPress={() => openConversation(current)}
            />
          ) : null}
          <Button
            testID="history-new"
            title="Hội thoại mới"
            kind="secondary"
            onPress={() => openConversation(null)}
          />
        </>
      )}
    </Screen>
  )
}
