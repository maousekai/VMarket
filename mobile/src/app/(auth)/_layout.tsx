import { useState } from 'react'
import { Redirect, Stack, useGlobalSearchParams, type Href } from 'expo-router'

import { useAuth } from '@/auth/AuthProvider'
import { safeReturnTo } from '@/lib/safeReturnTo'

/**
 * Auth stack. Authenticated + verified users are bounced out: to `?returnTo=` when present
 * (set by useRequireAuth), otherwise to their home area. So login screens do NOT need to navigate
 * after a successful login(); the redirect happens here. PENDING users stay (verify-email).
 */
export default function AuthLayout() {
  const { status, isShipper, isPending } = useAuth()
  const { returnTo: liveReturnTo } = useGlobalSearchParams<{ returnTo?: string }>()
  // Latch it: once login succeeds the first Redirect changes the focused route, the live param
  // disappears and a second render would otherwise fall back to the home area.
  const [latched, setLatched] = useState(liveReturnTo)
  if (liveReturnTo && liveReturnTo !== latched) setLatched(liveReturnTo)
  const returnTo = liveReturnTo ?? latched
  if (status === 'authenticated' && !isPending) {
    const target = safeReturnTo(returnTo)
    if (target && !isShipper) return <Redirect href={target as Href} />
    return <Redirect href={isShipper ? '/(shipper)' : '/(buyer)'} />
  }
  return <Stack screenOptions={{ headerShown: false }} />
}
