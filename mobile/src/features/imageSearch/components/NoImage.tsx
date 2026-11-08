import { router } from 'expo-router'

import { EmptyState, Screen } from '@/components/ui'
import { goBackOrHome, href } from '@/features/imageSearch/navigation'

/** Shown when a screen is opened without a selected photo (deep link, state lost). */
export function NoImage({ title }: { title: string }) {
  return (
    <Screen title={title} onBack={goBackOrHome}>
      <EmptyState
        testID="image-search-no-image"
        heading="Tìm bằng ảnh"
        title="Chưa có ảnh được chọn"
        body="Chụp hoặc chọn một ảnh sản phẩm để bắt đầu."
        primaryLabel="Chọn ảnh"
        onPrimary={() => router.replace(href('index'))}
      />
    </Screen>
  )
}
