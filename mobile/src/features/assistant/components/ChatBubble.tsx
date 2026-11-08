import { ActivityIndicator } from 'react-native'

import { AppText, Button, Card } from '@/components/ui'
import type { ChatItem } from '@/features/assistant/useAssistantChat'
import { colors } from '@/theme'

interface Props {
  item: ChatItem
  onOpenSources: (item: ChatItem) => void
}

/** Figma 3.9: user and assistant turns are identical white cards told apart by their title. */
export function ChatBubble({ item, onOpenSources }: Props) {
  const isUser = item.role === 'user'
  const first = item.sources[0]
  return (
    <Card testID={`chat-${item.role}-${item.key}`}>
      <AppText variant="label">{isUser ? 'Bạn' : 'Trợ lý VMarket'}</AppText>
      <AppText selectable>{item.content}</AppText>
      {!isUser && first ? (
        <Button
          testID={`chat-sources-${item.key}`}
          title={`Nguồn: ${first.title}`}
          kind="secondary"
          onPress={() => onOpenSources(item)}
        />
      ) : null}
    </Card>
  )
}

export function TypingBubble() {
  return (
    <Card testID="chat-typing">
      <AppText variant="label">Trợ lý VMarket</AppText>
      <ActivityIndicator color={colors.primary600} />
    </Card>
  )
}
