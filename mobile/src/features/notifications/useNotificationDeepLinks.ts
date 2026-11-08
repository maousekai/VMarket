import * as Notifications from 'expo-notifications'
import { useRouter, type Href } from 'expo-router'
import { useEffect, useRef } from 'react'

import { useAuth } from '@/auth/AuthProvider'
import { notificationHref } from '@/features/notifications/deepLink'

/**
 * Opens the right screen when the user taps a push notification (also from a cold start). Call it
 * once inside the authenticated app tree (see the report for the exact spot).
 */
export function useNotificationDeepLinks() {
  const router = useRouter()
  const { status, isShipper } = useAuth()
  const response = Notifications.useLastNotificationResponse()
  const handled = useRef<string | null>(null)

  useEffect(() => {
    if (!response || status !== 'authenticated') return
    if (response.actionIdentifier !== Notifications.DEFAULT_ACTION_IDENTIFIER) return
    const id = response.notification.request.identifier
    if (handled.current === id) return
    handled.current = id

    const data = response.notification.request.content.data as Record<string, string | undefined>
    const href = notificationHref(isShipper ? 'shipper' : 'buyer', data.type, data)
    router.push(href ?? (`/(${isShipper ? 'shipper' : 'buyer'})/notifications` as Href))
  }, [response, status, isShipper, router])
}
