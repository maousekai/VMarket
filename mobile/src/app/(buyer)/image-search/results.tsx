import { useQuery } from '@tanstack/react-query'
import { router, type Href } from 'expo-router'
import { ActivityIndicator } from 'react-native'

import { searchByImage } from '@/api/imageSearch'
import { AppText, Button, InfoCard, Screen } from '@/components/ui'
import { FailureView } from '@/features/imageSearch/components/FailureView'
import { ImageBox } from '@/features/imageSearch/components/ImageBox'
import { NoImage } from '@/features/imageSearch/components/NoImage'
import { SimilarProducts } from '@/features/imageSearch/components/SimilarProducts'
import { classifyFailure } from '@/features/imageSearch/errors'
import { useImageSearch, type SelectedImage } from '@/features/imageSearch/ImageSearchContext'
import { formatMegabytes, isTooLarge } from '@/features/imageSearch/imageFile'
import { href, openProduct } from '@/features/imageSearch/navigation'
import { useImageSource } from '@/features/imageSearch/useImageSource'
import { colors } from '@/theme'

/** Frames 3.44-3.48 - one screen driven by the request state. */
export default function ResultsScreen() {
  const { image } = useImageSearch()
  if (!image) return <NoImage title="Tìm bằng ảnh" />
  return <Results image={image} />
}

function Results({ image }: { image: SelectedImage }) {
  const { filter, setFilter } = useImageSearch()
  const { busy, pickFromLibrary } = useImageSource()
  const oversized = isTooLarge(image)

  const query = useQuery({
    queryKey: ['image-search', image.id, filter],
    queryFn: ({ signal }) => searchByImage(image.uri, filter, signal),
    enabled: !oversized,
    retry: false,
    refetchOnWindowFocus: false,
  })

  const failure = query.isError ? classifyFailure(query.error) : null
  const tooLarge = oversized || failure === 'tooLarge'

  const pickAnother = async () => {
    const next = await pickFromLibrary()
    if (next && !isTooLarge(next)) router.dismissTo(href('preview'))
  }
  const newSearch = () => router.dismissTo(href('index'))
  const hasFilter =
    filter.categoryId !== undefined ||
    filter.minPrice !== undefined ||
    filter.maxPrice !== undefined

  if (!tooLarge && query.isFetching) {
    return (
      <Screen title="Đang tìm sản phẩm" testID="image-search-loading">
        <AppText variant="body">Đang xử lý ảnh đã chọn</AppText>
        <InfoCard
          title="Tìm sản phẩm tương đồng"
          body="Giữ màn hình mở trong khi VMarket tìm sản phẩm."
        >
          <ActivityIndicator color={colors.primary600} accessibilityLabel="Đang tìm" />
        </InfoCard>
        <Button testID="image-search-cancel" title="Hủy tìm kiếm" onPress={() => router.back()} />
      </Screen>
    )
  }

  if (tooLarge) {
    return (
      <Screen
        title="Ảnh vượt giới hạn"
        onBack={() => router.back()}
        testID="image-search-too-large"
      >
        <AppText variant="body">
          {oversized ? `Ảnh đã chọn: ${formatMegabytes(image.size)}` : 'Ảnh đã chọn: trên 10 MB'}
        </AppText>
        <InfoCard title="Tối đa 10 MB" body="Chọn ảnh nhỏ hơn để tiếp tục tìm kiếm." />
        <Button
          testID="image-search-pick-another"
          title="Chọn ảnh khác"
          onPress={pickAnother}
          loading={busy}
        />
      </Screen>
    )
  }

  if (failure) {
    return (
      <Screen title="Chưa xử lý được ảnh" onBack={() => router.back()} testID="image-search-error">
        <FailureView
          failure={failure}
          onRetry={() => void query.refetch()}
          onPickAnother={pickAnother}
        />
      </Screen>
    )
  }

  const hits = query.data?.results ?? []

  if (hits.length === 0) {
    return (
      <Screen
        title="Chưa tìm thấy sản phẩm"
        onBack={() => router.back()}
        testID="image-search-empty"
      >
        <AppText variant="body">Không có kết quả phù hợp với ảnh này</AppText>
        <InfoCard
          title="Thử ảnh rõ hơn"
          body="Chụp riêng sản phẩm với đủ ánh sáng, tránh nền có nhiều vật thể."
        />
        <Button title="Chọn ảnh khác" onPress={newSearch} />
        {hasFilter ? (
          <Button title="Xóa bộ lọc" kind="secondary" onPress={() => setFilter({})} />
        ) : null}
        <Button
          title="Tìm bằng từ khóa"
          kind="secondary"
          onPress={() => router.push('/search' as Href)}
        />
      </Screen>
    )
  }

  return (
    <Screen title="Sản phẩm tương đồng" onBack={() => router.back()} testID="image-search-results">
      <AppText variant="body">Kết quả theo ảnh đã chọn</AppText>
      <ImageBox uri={image.uri} height={220} />
      <SimilarProducts hits={hits} onOpen={openProduct} />
      <Button
        testID="image-search-filter"
        title="Lọc giá và danh mục"
        kind="secondary"
        onPress={() => router.push(href('filter'))}
      />
      <Button title="Tìm bằng ảnh khác" kind="secondary" onPress={newSearch} />
    </Screen>
  )
}
