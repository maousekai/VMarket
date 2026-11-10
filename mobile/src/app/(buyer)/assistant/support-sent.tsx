import { router, useLocalSearchParams } from 'expo-router'

import { AppText, Button, InfoCard, Screen } from '@/components/ui'

export default function AssistantSupportSentScreen() {
  const { ticketId, content } = useLocalSearchParams<{ ticketId?: string; content?: string }>()

  return (
    <Screen
      testID="assistant-support-sent"
      title="Đã gửi yêu cầu"
      onBack={() => router.back()}
      footer={
        <Button testID="support-sent-back" title="Về hội thoại" onPress={() => router.back()} />
      }
    >
      <AppText>
        Yêu cầu hỗ trợ của bạn đã được ghi nhận{ticketId ? ` (mã #${ticketId})` : ''}
      </AppText>
      <InfoCard title="Nội dung" body={content} />
    </Screen>
  )
}
