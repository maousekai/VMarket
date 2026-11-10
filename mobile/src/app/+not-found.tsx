import { router } from 'expo-router'

import { EmptyState, Screen } from '@/components/ui'

/** Unknown URL (typed deep link, stale link): Vietnamese copy and a way home. */
export default function NotFoundScreen() {
  return (
    <Screen title="Không tìm thấy trang">
      <EmptyState
        heading="Không tìm thấy trang"
        title="Trang này không tồn tại"
        body="Đường dẫn có thể đã cũ hoặc bị nhập sai."
        primaryLabel="Về trang chủ"
        onPrimary={() => router.replace('/(buyer)')}
      />
    </Screen>
  )
}
