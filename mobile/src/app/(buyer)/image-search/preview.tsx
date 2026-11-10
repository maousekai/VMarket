import { router } from 'expo-router'

import { AppText, Button, Screen } from '@/components/ui'
import { ImageBox } from '@/features/imageSearch/components/ImageBox'
import { NoImage } from '@/features/imageSearch/components/NoImage'
import { useImageSearch } from '@/features/imageSearch/ImageSearchContext'
import { formatMegabytes, isTooLarge } from '@/features/imageSearch/imageFile'
import { href } from '@/features/imageSearch/navigation'
import { useImageSource } from '@/features/imageSearch/useImageSource'

/** Frame 3.43 - preview the photo before searching. */
export default function PreviewScreen() {
  const { image } = useImageSearch()
  const { busy, pickFromLibrary } = useImageSource()

  if (!image) return <NoImage title="Xem trước ảnh" />

  const pickAnother = async () => {
    const next = await pickFromLibrary()
    if (next && isTooLarge(next)) router.push(href('results'))
  }

  return (
    <Screen title="Xem trước ảnh" onBack={() => router.back()} testID="image-search-preview">
      <AppText variant="body">{`Ảnh đã chọn · ${formatMegabytes(image.size)}`}</AppText>
      <ImageBox testID="image-search-preview-image" uri={image.uri} height={220} />
      <Button
        testID="image-search-submit"
        title="Tìm sản phẩm tương đồng"
        onPress={() => router.push(href('results'))}
        disabled={busy}
      />
      <Button
        testID="image-search-pick-another"
        title="Chọn ảnh khác"
        kind="secondary"
        onPress={pickAnother}
        loading={busy}
      />
    </Screen>
  )
}
