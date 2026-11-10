import type { ReactNode } from 'react'

import { useAuth } from '@/auth/AuthProvider'
import { useRequireAuth } from '@/auth/useRequireAuth'
import { EmptyState, Skeleton } from '@/components/ui'

/** Renders children for signed-in users; guests get a login prompt, auth bootstrap a skeleton. */
export function AuthGate({ children }: { children: ReactNode }) {
  const { status } = useAuth()
  const requireAuth = useRequireAuth()
  if (status === 'loading') return <Skeleton height={96} />
  if (status === 'guest') {
    return (
      <EmptyState
        heading="Cần đăng nhập"
        title="Bạn chưa đăng nhập"
        body="Đăng nhập để xem và quản lý đơn hàng của bạn."
        primaryLabel="Đăng nhập"
        onPrimary={() => requireAuth()}
      />
    )
  }
  return <>{children}</>
}
