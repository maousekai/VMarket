import { Ionicons } from '@expo/vector-icons'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  View,
} from 'react-native'

import { AppText, Button, ErrorState, Screen, Skeleton } from '@/components/ui'
import { NoResults } from '@/features/catalog/components/NoResults'
import { ResultItem } from '@/features/catalog/components/ResultItem'
import { SearchBox } from '@/features/catalog/components/SearchBox'
import { SortSheet } from '@/features/catalog/components/SortSheet'
import { useProductSearch } from '@/features/catalog/hooks'
import { filterHref, searchHref } from '@/features/catalog/routes'
import { activeFilterCount, parseQuery, sortLabel, toParams } from '@/features/catalog/utils'
import { colors, layout, radius } from '@/theme'

export default function SearchResultsScreen() {
  const params = useLocalSearchParams()
  const query = parseQuery(params)
  const search = useProductSearch(query)
  const [sortOpen, setSortOpen] = useState(false)

  const items = (() => {
    const seen = new Set<string>()
    return (search.data?.pages ?? [])
      .flatMap((p) => p.items)
      .filter((p) => !seen.has(p.id) && !!seen.add(p.id))
  })()
  const empty = search.isSuccess && items.length === 0
  const filters = activeFilterCount(query)

  const editSearch = () => router.dismissTo(searchHref({ keyword: query.keyword }))

  const header = (
    <View style={styles.header}>
      <SearchBox testID="results-search" value={query.keyword} onPress={editSearch} />
      {empty ? null : (
        <>
          <AppText variant="h2">Sản phẩm phù hợp</AppText>
          <Button
            testID="results-filter"
            title={`${filters ? `Lọc (${filters})` : 'Lọc'} · Giá, đánh giá, gian hàng`}
            kind="secondary"
            onPress={() => router.push(filterHref(toParams(query)))}
          />
          <Pressable
            testID="results-sort"
            accessibilityRole="button"
            onPress={() => setSortOpen(true)}
            style={styles.sort}
          >
            <AppText color="textSecondary" style={styles.sortText}>
              Sắp xếp: {sortLabel(query.sort ?? 'RELEVANCE')}
            </AppText>
            <Ionicons name="chevron-down" size={18} color={colors.textSecondary} />
          </Pressable>
        </>
      )}
    </View>
  )

  return (
    <Screen
      testID="results-screen"
      title={empty ? 'Tìm kiếm' : 'Kết quả tìm kiếm'}
      onBack={() => router.back()}
      scroll={false}
      contentStyle={styles.screen}
    >
      <FlatList
        data={items}
        keyExtractor={(p) => p.id}
        renderItem={({ item }) => <ResultItem product={item} />}
        ListHeaderComponent={header}
        ItemSeparatorComponent={Separator}
        contentContainerStyle={styles.list}
        keyboardShouldPersistTaps="handled"
        refreshControl={
          <RefreshControl
            refreshing={search.isRefetching && !search.isFetchingNextPage}
            onRefresh={() => void search.refetch()}
          />
        }
        onEndReachedThreshold={0.5}
        onEndReached={() => {
          if (search.hasNextPage && !search.isFetchingNextPage) void search.fetchNextPage()
        }}
        ListEmptyComponent={
          search.isPending ? (
            <View style={styles.stack}>
              <Skeleton height={180} radius={radius.md} />
              <Skeleton height={21} width="60%" />
              <Skeleton height={48} radius={radius.md} />
            </View>
          ) : search.isError ? (
            <ErrorState error={search.error} onRetry={() => void search.refetch()} />
          ) : (
            <View style={styles.stack}>
              <NoResults categoryId={query.categoryId} hasKeyword={!!query.keyword} />
            </View>
          )
        }
        ListFooterComponent={
          search.isFetchingNextPage ? (
            <ActivityIndicator style={styles.footer} color={colors.primary600} />
          ) : search.isFetchNextPageError ? (
            <Button
              title="Thử lại"
              kind="secondary"
              onPress={() => void search.fetchNextPage()}
              style={styles.footer}
            />
          ) : undefined
        }
      />
      <SortSheet
        visible={sortOpen}
        value={query.sort ?? 'RELEVANCE'}
        onClose={() => setSortOpen(false)}
        onSelect={(sort) => {
          setSortOpen(false)
          router.setParams({ sort: sort === 'RELEVANCE' ? undefined : sort })
        }}
      />
    </Screen>
  )
}

const Separator = () => <View style={styles.separator} />

const styles = StyleSheet.create({
  screen: { padding: 0 },
  list: { padding: layout.screenPaddingX, paddingBottom: layout.screenPaddingX * 2 },
  header: { gap: 12, marginBottom: 12 },
  stack: { gap: 12 },
  separator: { height: 24 },
  footer: { marginVertical: 16 },
  sort: {
    flexDirection: 'row',
    alignItems: 'center',
    height: layout.controlHeight,
    paddingHorizontal: 16,
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
  },
  sortText: { flex: 1 },
})
