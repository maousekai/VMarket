import { useCallback, useEffect, useRef, useState } from 'react'
import { Pressable, Text } from 'react-native'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { listNotifications, markAllNotificationsRead, markNotificationRead } from '../api/shopApi'
import { displayDate } from '../deliveryStatus'
import { makeStyles, radius, spacing, type } from '../theme'
import { Body, Button, Caption, Loading, Notice, Screen } from './ui'

/** Link cua Notification Service -> man trong app ("/orders/<id>" -> chi tiet don). */
export function notificationTarget(link, role) {
  if (!link || typeof link !== 'string') return ''
  const orderId = /^\/orders\/([A-Za-z0-9_-]+)/.exec(link)?.[1]
  if (role === 'shipper') return link.startsWith('/shipper') ? '/' : ''
  return orderId ? `/orders/${orderId}` : ''
}

/** Thong bao trong app (frame 3.94 nguoi mua, 3.95 shipper). */
export default function NotificationsScreen({ role = 'buyer' }) {
  const styles = useStyles()
  const [state, setState] = useState({ items: [], page: 0, more: false, loading: true, error: '' })
  const request = useRef(null)

  const load = useCallback(async (page = 0) => {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setState((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const data = await listNotifications({ page, size: 20 }, controller.signal)
      if (controller.signal.aborted) return
      const items = data?.items || data?.content || []
      setState((previous) => ({
        items: [
          ...new Map(
            [...(page ? previous.items : []), ...items].map((item) => [item.id, item]),
          ).values(),
        ],
        page,
        more: page + 1 < (data?.totalPages ?? 1),
        loading: false,
        error: '',
      }))
    } catch (failure) {
      if (controller.signal.aborted) return
      setState((previous) => ({ ...previous, loading: false, error: getApiError(failure).message }))
    }
  }, [])

  useEffect(() => {
    load()
    return () => request.current?.abort()
  }, [load])

  async function open(item) {
    if (!item.read) {
      setState((previous) => ({
        ...previous,
        items: previous.items.map((row) => (row.id === item.id ? { ...row, read: true } : row)),
      }))
      markNotificationRead(item.id).catch(() => {})
    }
    const target = notificationTarget(item.link, role)
    if (target) router.push(target)
  }

  async function readAll() {
    try {
      await markAllNotificationsRead()
      setState((previous) => ({
        ...previous,
        items: previous.items.map((row) => ({ ...row, read: true })),
      }))
    } catch (failure) {
      setState((previous) => ({ ...previous, error: getApiError(failure).message }))
    }
  }

  return (
    <Screen
      title="Thông báo"
      role={role}
      refreshing={state.loading && !state.items.length}
      onRefresh={() => load()}
    >
      <Caption>Cập nhật của bạn</Caption>
      {state.error ? <Notice tone="danger">{state.error}</Notice> : null}
      {!state.loading && !state.items.length && !state.error && (
        <Body muted>Bạn chưa có thông báo.</Body>
      )}
      {state.items.map((item) => (
        <Pressable
          key={item.id}
          accessibilityRole="button"
          accessibilityLabel={`${item.read ? '' : 'Chưa đọc. '}${item.title}`}
          onPress={() => open(item)}
          style={[styles.item, !item.read && styles.unread]}
        >
          <Text style={styles.title}>
            {item.read ? '' : 'Chưa đọc · '}
            {item.title}
          </Text>
          {item.message ? <Text style={styles.message}>{item.message}</Text> : null}
          {item.createdAt ? <Text style={styles.time}>{displayDate(item.createdAt)}</Text> : null}
        </Pressable>
      ))}
      {state.loading && <Loading />}
      {state.more && !state.loading && (
        <Button title="Tải thêm" variant="secondary" onPress={() => load(state.page + 1)} />
      )}
      {state.items.some((item) => !item.read) && (
        <Button title="Đánh dấu tất cả đã đọc" variant="secondary" onPress={readAll} />
      )}
    </Screen>
  )
}

const useStyles = makeStyles((colors) => ({
  item: {
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: spacing(4),
    gap: spacing(1),
    backgroundColor: colors.surface,
  },
  unread: { borderColor: colors.primary },
  title: { ...type.button, color: colors.primaryText },
  message: { ...type.body, color: colors.text },
  time: { ...type.caption, color: colors.muted },
}))
