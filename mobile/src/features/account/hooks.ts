import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRouter } from 'expo-router'
import { useCallback, useEffect } from 'react'

import { getMyProfile, listAddresses } from '@/api/user'
import { useAuth } from '@/auth/AuthProvider'

import { accountRoutes } from './routes'

/** Query keys are scoped by user so one account never sees cached data of another. */
export function useAccountKeys() {
  const { user } = useAuth()
  const userId = user?.userId ?? 'guest'
  return {
    profile: ['user', userId, 'profile'] as const,
    addresses: ['user', userId, 'addresses'] as const,
  }
}

export function useProfile() {
  const { status } = useAuth()
  const keys = useAccountKeys()
  return useQuery({
    queryKey: keys.profile,
    queryFn: getMyProfile,
    enabled: status === 'authenticated',
  })
}

export function useAddresses() {
  const { status } = useAuth()
  const keys = useAccountKeys()
  return useQuery({
    queryKey: keys.addresses,
    queryFn: listAddresses,
    enabled: status === 'authenticated',
  })
}

/** The default address can move on create/delete, so callers always refetch the whole list. */
export function useRefreshAccount() {
  const client = useQueryClient()
  const keys = useAccountKeys()
  // Checkout (features/checkout/hooks) caches the same list under ['addresses']: refresh both.
  const addresses = useCallback(
    () =>
      Promise.all([
        client.invalidateQueries({ queryKey: keys.addresses }),
        client.invalidateQueries({ queryKey: ['addresses'] }),
      ]),
    [client, keys.addresses],
  )
  const profile = useCallback(
    () => client.invalidateQueries({ queryKey: keys.profile }),
    [client, keys.profile],
  )
  return { addresses, profile }
}

/** Unverified (PENDING) accounts must verify their email before editing the profile. */
export function useVerifiedOnly(): boolean {
  const { isPending } = useAuth()
  const router = useRouter()
  useEffect(() => {
    if (isPending) router.replace(accountRoutes.verifyEmail)
  }, [isPending, router])
  return !isPending
}

/** Back to the account tab, whatever the stack looks like. */
export function useGoToAccount() {
  const router = useRouter()
  return useCallback(() => router.navigate(accountRoutes.home), [router])
}
