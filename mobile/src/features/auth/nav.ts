import type { Href } from 'expo-router'

import { safeReturnTo } from '@/lib/safeReturnTo'

export type AuthRoute =
  | 'login'
  | 'register'
  | 'verify-email'
  | 'forgot-password'
  | 'check-email'
  | 'reset-password'
  | 'google-cancelled'
  | 'locked'
  | 'session-expired'

type Params = Record<string, string | undefined>

/** Href to an auth screen; undefined/empty params are dropped. */
export function authHref(route: AuthRoute, params: Params = {}): Href {
  const clean = Object.fromEntries(Object.entries(params).filter(([, v]) => v))
  return { pathname: `/(auth)/${route}`, params: clean } as unknown as Href
}

/** Validated `returnTo` route param, to be forwarded through the whole auth flow. */
export function returnToParam(value: string | string[] | undefined): string | undefined {
  return safeReturnTo(value) ?? undefined
}
