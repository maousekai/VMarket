import { router, useLocalSearchParams, type Href } from 'expo-router'
import { useRef, useState } from 'react'
import { ActivityIndicator, ScrollView, StyleSheet, View } from 'react-native'

import { useAuth } from '@/auth/AuthProvider'
import { useRequireAuth } from '@/auth/useRequireAuth'
import { AppText, Button, Card, ErrorState, Screen, TextField } from '@/components/ui'
import { ChatBubble, TypingBubble } from '@/features/assistant/components/ChatBubble'
import { assistantHref } from '@/features/assistant/routes'
import { useAssistantChat, type ChatItem } from '@/features/assistant/useAssistantChat'
import { colors, layout } from '@/theme'

const GREETING =
  'Xin chào! Mình là trợ lý VMarket. Mình có thể giải đáp về sản phẩm, chính sách mua hàng – đổi trả và tra cứu đơn hàng của bạn.'
const MAX_MESSAGE = 1000

function Assistant({
  conversationId,
  initialQuestion,
}: {
  conversationId: string | null
  initialQuestion: string
}) {
  const { status } = useAuth()
  const requireAuth = useRequireAuth()
  const chat = useAssistantChat(conversationId)
  const [draft, setDraft] = useState(initialQuestion)
  const scroller = useRef<{ scrollToEnd: (options?: { animated?: boolean }) => void } | null>(null)

  const canSend = draft.trim().length > 0 && !chat.sending
  const lastQuestion =
    chat.failed ?? [...chat.items].reverse().find((i) => i.role === 'user')?.content ?? ''
  const needsLogin = status !== 'authenticated' && chat.items.at(-1)?.loginRequired

  const submit = () => {
    if (!canSend) return
    const text = draft
    setDraft('')
    void chat.send(text)
  }

  const openSources = (item: ChatItem) =>
    router.push(assistantHref('/sources', { sources: JSON.stringify(item.sources) }))

  const openSupport = () => {
    if (!requireAuth('/assistant')) return
    router.push(
      assistantHref('/support', {
        conversationId: chat.conversationId ?? '',
        question: lastQuestion,
      }),
    )
  }

  if (chat.failed !== null) {
    return (
      <Screen
        testID="assistant-error"
        title="Trợ lý chưa trả lời được"
        onBack={() => router.back()}
      >
        <AppText>Kết nối bị gián đoạn</AppText>
        <Card>
          <AppText variant="label">Bạn có thể thử lại</AppText>
          <AppText>Nội dung câu hỏi vẫn được giữ. Nếu cần, hãy chuyển tới hỗ trợ.</AppText>
          <AppText color="textSecondary" numberOfLines={3}>
            “{chat.failed}”
          </AppText>
        </Card>
        <Button
          testID="assistant-retry"
          title="Thử lại"
          onPress={() => void chat.send(chat.failed ?? '')}
        />
        <Button
          testID="assistant-error-support"
          title="Liên hệ hỗ trợ"
          kind="secondary"
          onPress={openSupport}
        />
        <Button
          testID="assistant-error-back"
          title="Về hội thoại"
          kind="neutral"
          onPress={() => {
            setDraft(chat.failed ?? '')
            chat.clearFailed()
          }}
        />
      </Screen>
    )
  }

  return (
    <Screen
      testID="assistant-screen"
      title="Trợ lý VMarket"
      onBack={() => router.back()}
      scroll={false}
      contentStyle={styles.body}
      footer={
        <>
          <TextField
            testID="assistant-input"
            value={draft}
            onChangeText={setDraft}
            placeholder="Nhập câu hỏi…"
            maxLength={MAX_MESSAGE}
            returnKeyType="send"
            multiline
          />
          <Button
            testID="assistant-send"
            title="Gửi"
            disabled={!canSend}
            loading={chat.sending}
            onPress={submit}
          />
        </>
      }
    >
      <ScrollView
        ref={(node) => {
          scroller.current = node
        }}
        style={styles.flex}
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        onContentSizeChange={() => scroller.current?.scrollToEnd({ animated: true })}
      >
        {chat.historyError ? (
          <ErrorState
            error={chat.historyError}
            onRetry={() => void chat.refetchHistory()}
            secondaryLabel="Hội thoại mới"
            onSecondary={() => router.replace(assistantHref(''))}
          />
        ) : chat.historyLoading ? (
          <ActivityIndicator color={colors.primary600} />
        ) : (
          <>
            {chat.hasOlder ? (
              <Button
                testID="assistant-load-older"
                title="Xem tin nhắn cũ hơn"
                kind="neutral"
                loading={chat.loadingOlder}
                onPress={() => void chat.loadOlder()}
              />
            ) : null}
            {chat.items.length === 0 ? (
              <Card testID="assistant-greeting">
                <AppText variant="label">Trợ lý VMarket</AppText>
                <AppText>{GREETING}</AppText>
              </Card>
            ) : null}
            {chat.items.map((item) => (
              <ChatBubble key={item.key} item={item} onOpenSources={openSources} />
            ))}
            {chat.sending ? <TypingBubble /> : null}
            {needsLogin ? (
              <Button
                testID="assistant-login"
                title="Đăng nhập"
                kind="secondary"
                onPress={() => requireAuth('/assistant')}
              />
            ) : null}
          </>
        )}
        <View style={styles.actions}>
          <Button
            testID="assistant-orders"
            title="Xem đơn của tôi"
            kind="secondary"
            onPress={() => {
              if (requireAuth('/orders')) router.push('/orders' as Href)
            }}
          />
          <Button
            testID="assistant-history"
            title="Lịch sử hội thoại"
            kind="secondary"
            onPress={() => {
              if (requireAuth('/assistant/history')) {
                router.push(assistantHref('/history'))
              }
            }}
          />
          <Button
            testID="assistant-support"
            title="Liên hệ hỗ trợ"
            kind="secondary"
            onPress={openSupport}
          />
        </View>
      </ScrollView>
    </Screen>
  )
}

export default function AssistantScreen() {
  const params = useLocalSearchParams<{ conversationId?: string; question?: string }>()
  const conversationId = params.conversationId || null
  // Remount when another conversation is opened (history -> chat).
  return (
    <Assistant
      key={conversationId ?? 'new'}
      conversationId={conversationId}
      initialQuestion={params.question ?? ''}
    />
  )
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  body: { padding: 0, paddingTop: 0 },
  content: {
    padding: layout.screenPaddingX,
    paddingTop: layout.screenPaddingTop,
    gap: layout.stackGap,
  },
  actions: { gap: layout.stackGap },
})
