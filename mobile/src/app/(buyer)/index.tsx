import { router } from 'expo-router'
import { ScrollView, StyleSheet, View } from 'react-native'

import {
  AppText,
  Button,
  Card,
  Chip,
  ErrorState,
  InfoCard,
  Screen,
  Skeleton,
} from '@/components/ui'
import { ProductGrid } from '@/features/catalog/components/ProductGrid'
import { SearchBox } from '@/features/catalog/components/SearchBox'
import { useCategories, useHomeFeed, useNewestProducts } from '@/features/catalog/hooks'
import {
  assistantHref,
  imageSearchHref,
  notificationsHref,
  resultsHref,
  searchHref,
} from '@/features/catalog/routes'
import { layout } from '@/theme'

function GridSkeleton() {
  return (
    <View style={styles.skeletons}>
      <Skeleton height={220} width="48%" />
      <Skeleton height={220} width="48%" />
    </View>
  )
}

export default function HomeScreen() {
  const categories = useCategories()
  const feed = useHomeFeed()
  const newest = useNewestProducts()
  const refreshing = categories.isRefetching || feed.isRefetching || newest.isRefetching

  const refresh = () => {
    void categories.refetch()
    void feed.refetch()
    void newest.refetch()
  }

  const roots = (categories.data ?? []).filter((c) => c.active !== false)
  const feedItems = feed.data?.items ?? []
  const newItems = (newest.data ?? []).filter((p) => !feedItems.some((f) => f.id === p.id))

  return (
    <Screen
      testID="home-screen"
      title="VMarket · Đà Nẵng"
      refreshing={refreshing}
      onRefresh={refresh}
    >
      <SearchBox testID="home-search" onPress={() => router.push(searchHref())} />
      <Button
        testID="home-notifications"
        title="Thông báo"
        kind="secondary"
        icon="notifications-outline"
        style={styles.leftAligned}
        onPress={() => router.push(notificationsHref)}
      />
      <Button
        testID="home-image-search"
        title="Tìm bằng ảnh"
        kind="secondary"
        icon="camera-outline"
        onPress={() => router.push(imageSearchHref)}
      />

      <AppText variant="h2">Chọn hàng địa phương</AppText>
      <AppText color="textSecondary">Đặc sản · Hải sản · Đồ dùng mỗi ngày</AppText>

      {roots.length ? (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.chips}
          style={styles.chipScroll}
        >
          {roots.map((c) => (
            <Chip
              key={c.id}
              testID={`home-category-${c.slug}`}
              label={c.name}
              onPress={() => router.push(resultsHref({ categoryId: c.id }))}
            />
          ))}
        </ScrollView>
      ) : null}

      <InfoCard
        testID="home-explore"
        title="Khám phá Đà Nẵng"
        body="Duyệt sản phẩm theo danh mục và gian hàng."
        onPress={() => router.push(resultsHref())}
      />

      {feed.isPending ? (
        <GridSkeleton />
      ) : feed.isError ? (
        <ErrorState testID="home-error" error={feed.error} onRetry={() => void feed.refetch()} />
      ) : feedItems.length ? (
        <>
          <AppText variant="h3">
            {feed.data?.personal ? 'Gợi ý cho bạn' : 'Bán chạy cho người mới'}
          </AppText>
          <ProductGrid items={feedItems} testID="home-feed" />
        </>
      ) : (
        <Card testID="home-empty">
          <AppText variant="label">Chưa có sản phẩm</AppText>
          <AppText color="textSecondary">Sản phẩm mới sẽ xuất hiện tại đây.</AppText>
        </Card>
      )}

      {newItems.length ? (
        <>
          <AppText variant="h3">Sản phẩm mới</AppText>
          <ProductGrid items={newItems} testID="home-newest" />
        </>
      ) : null}

      <Button
        testID="home-assistant"
        title="Hỏi trợ lý VMarket"
        kind="secondary"
        onPress={() => router.push(assistantHref)}
      />
    </Screen>
  )
}

const styles = StyleSheet.create({
  leftAligned: { justifyContent: 'flex-start' },
  // Bleed to the screen edges so the chip row scrolls under the 20px page padding.
  chipScroll: { marginHorizontal: -layout.screenPaddingX },
  chips: { paddingHorizontal: layout.screenPaddingX, gap: 8 },
  skeletons: { flexDirection: 'row', justifyContent: 'space-between' },
})
