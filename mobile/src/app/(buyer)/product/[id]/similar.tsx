import { router, useLocalSearchParams } from 'expo-router'
import { FlatList, RefreshControl, StyleSheet, View } from 'react-native'

import { EmptyState, ErrorState, Screen, Skeleton } from '@/components/ui'
import { ResultItem } from '@/features/catalog/components/ResultItem'
import { useSimilarProducts } from '@/features/catalog/hooks'
import { layout, radius } from '@/theme'

export default function SimilarProductsScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const { detail, similar } = useSimilarProducts(id)
  const items = similar.data ?? []
  const failed = detail.isError
  const loading = detail.isPending || (similar.isPending && !failed)

  return (
    <Screen
      testID="similar-screen"
      title="Sản phẩm tương tự"
      onBack={() => router.back()}
      scroll={false}
      contentStyle={styles.screen}
    >
      <FlatList
        data={items}
        keyExtractor={(p) => p.id}
        renderItem={({ item }) => <ResultItem product={item} />}
        ItemSeparatorComponent={Separator}
        contentContainerStyle={styles.list}
        refreshControl={
          <RefreshControl
            refreshing={similar.isRefetching || detail.isRefetching}
            onRefresh={() => {
              void detail.refetch()
              void similar.refetch()
            }}
          />
        }
        ListEmptyComponent={
          loading ? (
            <View style={styles.stack}>
              <Skeleton height={180} radius={radius.md} />
              <Skeleton height={21} width="60%" />
              <Skeleton height={48} radius={radius.md} />
            </View>
          ) : failed ? (
            <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />
          ) : (
            <EmptyState
              testID="similar-empty"
              heading="Chưa có sản phẩm tương tự"
              title="Chưa có sản phẩm tương tự"
              body="Hãy xem thêm các sản phẩm khác trên VMarket."
              secondaryLabel="Về trang chủ"
              onSecondary={() => router.replace('/(buyer)')}
            />
          )
        }
      />
    </Screen>
  )
}

const Separator = () => <View style={styles.separator} />

const styles = StyleSheet.create({
  screen: { padding: 0 },
  list: { padding: layout.screenPaddingX, paddingBottom: layout.screenPaddingX * 2 },
  stack: { gap: 12 },
  separator: { height: 24 },
})
