import { useIsFocused } from 'expo-router'
import { useEffect, useRef } from 'react'

import { useAuth } from '@/auth/AuthProvider'
import { useRequireAuth } from '@/auth/useRequireAuth'

/**
 * Screens that need a session: guests are sent to login once (on the transition to guest, and
 * only by the focused screen) and come back afterwards. Not re-fired when this screen regains
 * focus (e.g. the user backs out of login) or when the global pathname changes.
 */
export function useGuestRedirect(enabled = true) {
  const { status } = useAuth()
  const requireAuth = useRequireAuth()
  const focused = useIsFocused()
  const redirected = useRef(false)
  useEffect(() => {
    if (status !== 'guest') {
      redirected.current = false
      return
    }
    if (!enabled || !focused || redirected.current) return
    redirected.current = true
    requireAuth()
  }, [enabled, focused, status, requireAuth])
}
