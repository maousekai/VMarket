import { Ionicons } from '@expo/vector-icons'
import { Image } from 'expo-image'
import { router, useLocalSearchParams } from 'expo-router'
import { useMemo, useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  View,
} from 'react-native'

import type { RatingSummary, Review } from '@/api/reviews'
import { AppText, Button, Card, EmptyState, ErrorState, Screen, Skeleton } from '@/components/ui'
import { useProductReviews, useRatingSummary } from '@/features/catalog/hooks'
import { similarHref } from '@/features/catalog/routes'
import { formatDate } from '@/lib/format'
import { colors, layout, radius } from '@/theme'

const STARS = [5, 4, 3, 2, 1] as const

/** Public reviews of one product: rating summary, star filter and a paged list. */
export default function ProductReviewsScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const [rating, setRating] = useState<number | undefined>()
  const summary = useRatingSummary(id)
  const reviews = useProductReviews(id, rating)
  const items = useMemo(() => reviews.data?.pages.flatMap((p) => p.items) ?? [], [reviews.data])
  const emptyTitle = rating ? `Chưa có đánh giá ${rating} sao` : 'Chưa có đánh giá'

  return (
    <Screen
      testID="reviews-screen"
      title="Đánh giá sản phẩm"
      onBack={() => router.back()}
      scroll={false}
      contentStyle={styles.screen}
    >
      <FlatList
        data={items}
        keyExtractor={(r) => r.id}
        renderItem={({ item }) => <ReviewRow review={item} />}
        ItemSeparatorComponent={Separator}
        contentContainerStyle={styles.list}
        onEndReachedThreshold={0.4}
        onEndReached={() => {
          if (reviews.hasNextPage && !reviews.isFetchingNextPage) void reviews.fetchNextPage()
        }}
        refreshControl={
          <RefreshControl
            refreshing={reviews.isRefetching && !reviews.isFetchingNextPage}
            onRefresh={() => {
              void summary.refetch()
              void reviews.refetch()
            }}
          />
        }
        ListHeaderComponent={
          <View style={styles.header}>
            {summary.data ? <Summary summary={summary.data} /> : null}
            <View style={styles.chips}>
              <Chip
                label="Tất cả"
                active={rating === undefined}
                onPress={() => setRating(undefined)}
              />
              {STARS.map((n) => (
                <Chip
                  key={n}
                  label={`${n} sao`}
                  active={rating === n}
                  onPress={() => setRating(n)}
                />
              ))}
            </View>
          </View>
        }
        ListFooterComponent={
          reviews.isFetchingNextPage ? <ActivityIndicator style={styles.footer} /> : undefined
        }
        ListEmptyComponent={
          reviews.isPending ? (
            <View style={styles.stack}>
              <Skeleton height={96} radius={radius.md} />
              <Skeleton height={96} radius={radius.md} />
            </View>
          ) : reviews.isError ? (
            <ErrorState error={reviews.error} onRetry={() => void reviews.refetch()} />
          ) : (
            <EmptyState
              testID="reviews-empty"
              heading={emptyTitle}
              title={emptyTitle}
              body="Đánh giá của người đã mua sản phẩm sẽ hiển thị tại đây."
              primaryLabel="Xem sản phẩm tương tự"
              onPrimary={() => router.push(similarHref(id))}
            />
          )
        }
      />
      {items.length > 0 ? (
        <View style={styles.cta}>
          <Button
            testID="reviews-similar"
            title="Xem sản phẩm tương tự"
            kind="secondary"
            onPress={() => router.push(similarHref(id))}
          />
        </View>
      ) : null}
    </Screen>
  )
}

function Summary({ summary }: { summary: RatingSummary }) {
  return (
    <Card testID="reviews-summary">
      <View style={styles.averageRow}>
        <AppText variant="h2">{summary.count > 0 ? summary.average.toFixed(1) : '–'}</AppText>
        <AppText color="textSecondary">{`${summary.count} đánh giá`}</AppText>
      </View>
      {STARS.map((n) => {
        const count = summary.distribution?.[String(n)] ?? 0
        const share = summary.count > 0 ? (count / summary.count) * 100 : 0
        return (
          <View key={n} style={styles.barRow}>
            <AppText variant="caption" color="textSecondary">
              {`${n}★`}
            </AppText>
            <View style={styles.barTrack}>
              <View style={[styles.barFill, { width: `${share}%` }]} />
            </View>
            <AppText variant="caption" color="textSecondary">
              {String(count)}
            </AppText>
          </View>
        )
      })}
    </Card>
  )
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <Pressable
      testID={`reviews-filter-${label}`}
      accessibilityRole="button"
      accessibilityState={{ selected: active }}
      onPress={onPress}
      style={[styles.chip, active && styles.chipActive]}
    >
      <AppText variant="label" color={active ? 'textInverse' : 'text'}>
        {label}
      </AppText>
    </Pressable>
  )
}

function ReviewRow({ review }: { review: Review }) {
  return (
    <Card testID={`review-${review.id}`}>
      <View style={styles.starsRow}>
        {[1, 2, 3, 4, 5].map((n) => (
          <Ionicons
            key={n}
            name={n <= review.rating ? 'star' : 'star-outline'}
            size={16}
            color={colors.warning}
          />
        ))}
        <AppText variant="caption" color="textSecondary">
          {`  ${formatDate(review.createdAt)}`}
        </AppText>
      </View>
      {review.comment ? <AppText>{review.comment}</AppText> : null}
      {review.imageUrls?.length ? (
        <View style={styles.thumbs}>
          {review.imageUrls.slice(0, 4).map((uri) => (
            <Image key={uri} source={{ uri }} style={styles.thumb} contentFit="cover" />
          ))}
        </View>
      ) : null}
    </Card>
  )
}

const Separator = () => <View style={styles.separator} />

const styles = StyleSheet.create({
  screen: { padding: 0 },
  list: { padding: layout.screenPaddingX, paddingBottom: layout.screenPaddingX * 2 },
  header: { gap: 12, marginBottom: 16 },
  stack: { gap: 12 },
  separator: { height: 12 },
  footer: { marginVertical: 16 },
  cta: { paddingHorizontal: layout.screenPaddingX, paddingBottom: layout.screenPaddingX },
  averageRow: { flexDirection: 'row', alignItems: 'baseline', gap: 8 },
  barRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  barTrack: {
    flex: 1,
    height: 6,
    borderRadius: 3,
    backgroundColor: colors.surfaceMuted,
    overflow: 'hidden',
  },
  barFill: { height: 6, backgroundColor: colors.warning },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 999,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  chipActive: { backgroundColor: colors.primary600, borderColor: colors.primary600 },
  starsRow: { flexDirection: 'row', alignItems: 'center', gap: 2 },
  thumbs: { flexDirection: 'row', gap: 8 },
  thumb: { width: 64, height: 64, borderRadius: 8, backgroundColor: colors.surfaceMuted },
})
