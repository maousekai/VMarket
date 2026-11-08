import { Redirect, type Href } from 'expo-router'

import { useAuth } from '@/auth/AuthProvider'

/** Entry redirect: shipper -> (shipper), unverified -> verify-email, everyone else (guests too) -> (buyer). */
export default function Index() {
  const { status, isShipper, isPending } = useAuth()
  if (status === 'loading') return null
  if (isShipper) return <Redirect href="/(shipper)" />
  // verify-email is built by the auth feature owner; cast keeps typed routes happy until it exists.
  if (isPending) return <Redirect href={'/(auth)/verify-email' as Href} />
  return <Redirect href="/(buyer)" />
}
