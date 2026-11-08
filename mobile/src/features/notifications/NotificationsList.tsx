import { router, type Href } from 'expo-router'
import { useCallback } from 'react'
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  View,
} from 'react-native'

import type { AppNotification } from '@/api/notifications'
import { useAuth } from '@/auth/AuthProvider'
import { useGuestRedirect } from '@/features/account/useGuestRedirect'
import {
  AppText,
  Button,
  EmptyState,
  ErrorState,
  Screen,
  Skeleton,
  useToast,
} from '@/components/ui'
import { notificationHref, type NotificationRole } from '@/features/notifications/deepLink'
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotificationList,
  useUnreadCount,
} from '@/features/notifications/useNotifications'
import { getApiError } from '@/api/client'
import { formatRelative } from '@/lib/format'
import { colors, layout, radius } from '@/theme'

/** Backend texts quote the raw 26-char order id; show the short customer-facing code instead. */
const friendlyMessage = (text: string) =>
  text.replace(/[0-9A-HJKMNP-TV-Z]{26}/g, (id) => `#VM-${id.slice(-8)}`)

function NotificationRow({
  item,
  onPress,
}: {
  item: AppNotification
  onPress: (item: AppNotification) => void
}) {
  const unread = !item.read
  return (
    <Pressable
      testID={`notification-${item.id}`}
      accessibilityRole="button"
      accessibilityLabel={`${unread ? 'Chưa đọc. ' : ''}${item.title}. ${friendlyMessage(item.message ?? '')}`}
      onPress={() => onPress(item)}
      style={({ pressed }) => [
        styles.row,
        unread ? styles.rowUnread : styles.rowRead,
        pressed && { backgroundColor: unread ? colors.primary50 : colors.surfaceSubtle },
      ]}
    >
      <AppText variant="button" color={unread ? 'primary600' : 'textSecondary'} numberOfLines={2}>
        {unread ? 'Chưa đọc · ' : ''}
        {item.title}
      </AppText>
      {item.message ? (
        <AppText variant="body" color={unread ? 'text' : 'textSecondary'} numberOfLines={2}>
          {friendlyMessage(item.message)}
        </AppText>
      ) : null}
      <AppText variant="caption" color="textSubtle">
        {formatRelative(item.createdAt)}
      </AppText>
    </Pressable>
  )
}

/** Shared notification centre (UC-20): buyer 3.94 and shipper 3.95 differ only by header role. */
export function NotificationsList({ role }: { role: NotificationRole }) {
  const toast = useToast()
  const { status } = useAuth()
  const signedIn = status === 'authenticated'
  const list = useNotificationList(signedIn)
  const unread = useUnreadCount()
  const markRead = useMarkNotificationRead()
  const markAll = useMarkAllNotificationsRead()

  useGuestRedirect()

  const open = useCallback(
    (item: AppNotification) => {
      if (!item.read) markRead.mutate(item.id)
      const href = notificationHref(role, item.type, { ...item.data })
      if (href) router.push(href as Href)
    },
    [markRead, role],
  )

  const onMarkAll = () => {
    markAll.mutate(undefined, {
      onError: (e) => toast.show({ type: 'error', message: getApiError(e).message }),
    })
  }

  const items = list.data ?? []
  return (
    <Screen
      testID={`notifications-${role}`}
      title="Thông báo"
      role={role}
      onBack={() => router.back()}
      scroll={false}
      contentStyle={styles.body}
    >
      {!signedIn ? null : (
        <FlatList
          data={items}
          keyExtractor={(n) => n.id}
          contentContainerStyle={styles.listContent}
          ItemSeparatorComponent={Separator}
          refreshControl={
            <RefreshControl
              refreshing={list.isRefetching && !list.isFetchingNextPage}
              onRefresh={() => {
                void list.refetch()
              }}
            />
          }
          onEndReachedThreshold={0.4}
          onEndReached={() => {
            if (list.hasNextPage && !list.isFetchingNextPage) void list.fetchNextPage()
          }}
          ListHeaderComponent={<AppText style={styles.intro}>Cập nhật của bạn</AppText>}
          renderItem={({ item }) => <NotificationRow item={item} onPress={open} />}
          ListEmptyComponent={
            list.isPending ? (
              <View style={styles.skeletons}>
                <Skeleton height={96} />
                <Skeleton height={96} />
                <Skeleton height={96} />
              </View>
            ) : list.isError ? (
              <ErrorState
                testID="notifications-error"
                error={list.error}
                onRetry={() => {
                  void list.refetch()
                }}
              />
            ) : (
              <EmptyState
                testID="notifications-empty"
                heading="Chưa có thông báo nào"
                body="Thông báo về đơn hàng của bạn sẽ hiển thị tại đây."
              />
            )
          }
          ListFooterComponent={
            list.isFetchingNextPage ? (
              <ActivityIndicator style={styles.footerSpinner} color={colors.primary600} />
            ) : items.length > 0 ? (
              <Button
                testID="notifications-mark-all"
                title="Đánh dấu tất cả đã đọc"
                kind="secondary"
                loading={markAll.isPending}
                disabled={unread === 0 || markAll.isPending}
                onPress={onMarkAll}
                style={styles.markAll}
              />
            ) : undefined
          }
        />
      )}
    </Screen>
  )
}

function Separator() {
  return <View style={styles.separator} />
}

const styles = StyleSheet.create({
  body: { padding: 0, paddingTop: 0 },
  listContent: { padding: layout.screenPaddingX, paddingTop: layout.screenPaddingTop, flexGrow: 1 },
  intro: { height: 32 },
  separator: { height: 12 },
  skeletons: { gap: 12 },
  footerSpinner: { marginVertical: 16 },
  markAll: { marginTop: 12 },
  row: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 4,
    minHeight: 80,
    justifyContent: 'center',
  },
  rowUnread: { backgroundColor: colors.surface, borderColor: colors.primary600 },
  rowRead: { backgroundColor: colors.surface, borderColor: colors.border },
})
