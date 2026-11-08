import { usePathname, useRouter } from 'expo-router'
import { useCallback } from 'react'

import { useAuth } from '@/auth/AuthProvider'
import { isAuthPath, safeReturnTo } from '@/lib/safeReturnTo'

/**
 * Guard for protected actions in screens that guests may open (add to cart, checkout...).
 *
 *   const requireAuth = useRequireAuth()
 *   const onAdd = () => { if (!requireAuth()) return; addToCart() }
 *
 * For guests it pushes /(auth)/login?returnTo=<current path or the given one> and returns false.
 * Login success is redirected back to `returnTo` by the (auth) layout. It never pushes login
 * while an (auth) screen is already showing, and never uses an (auth) path as returnTo.
 */
export function useRequireAuth() {
  const { status } = useAuth()
  const router = useRouter()
  const pathname = usePathname()
  return useCallback(
    (returnTo?: string): boolean => {
      if (status === 'authenticated') return true
      if (isAuthPath(pathname)) return false
      const target = safeReturnTo(returnTo ?? pathname)
      router.push({
        pathname: '/(auth)/login',
        params: target ? { returnTo: target } : {},
      })
      return false
    },
    [status, router, pathname],
  )
}
