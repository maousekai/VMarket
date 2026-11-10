import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'

import {
  fetchNotifications,
  fetchUnreadCount,
  markAllNotificationsRead,
  markNotificationRead,
  type NotificationPage,
} from '@/api/notifications'
import { useAuth } from '@/auth/AuthProvider'

const LIST_KEY = ['notifications', 'list'] as const
const UNREAD_KEY = ['notifications', 'unread'] as const

/** Unread badge count for headers; 0 for guests. Refreshes every minute and on mark-read. */
export function useUnreadCount() {
  const { status } = useAuth()
  const query = useQuery({
    queryKey: UNREAD_KEY,
    queryFn: fetchUnreadCount,
    enabled: status === 'authenticated',
    refetchInterval: 60_000,
  })
  return status === 'authenticated' ? (query.data ?? 0) : 0
}

export function useNotificationList(enabled: boolean) {
  return useInfiniteQuery({
    enabled,
    queryKey: LIST_KEY,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => fetchNotifications(pageParam),
    getNextPageParam: (last: NotificationPage) =>
      last.page + 1 < last.totalPages ? last.page + 1 : undefined,
    select: (data) => data.pages.flatMap((p) => p.items),
  })
}

export function useMarkNotificationRead() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: markNotificationRead,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ['notifications'] })
    },
  })
}

export function useMarkAllNotificationsRead() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: markAllNotificationsRead,
    onSettled: () => {
      void qc.invalidateQueries({ queryKey: ['notifications'] })
    },
  })
}
