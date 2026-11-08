import { useState } from 'react'

import { useAuth } from '@/auth/AuthProvider'
import { Button, ConfirmDialog } from '@/components/ui'

/** "Đăng xuất" for the shipper area (the account tab only exists for buyers). */
export function ShipperLogout({ hasActiveDelivery }: { hasActiveDelivery: boolean }) {
  const { logout } = useAuth()
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)

  const confirm = async () => {
    if (busy) return
    setBusy(true)
    try {
      await logout()
    } finally {
      setBusy(false)
      setOpen(false)
    }
  }

  return (
    <>
      <Button
        testID="shipper-logout"
        title="Đăng xuất"
        kind="neutral"
        onPress={() => setOpen(true)}
      />
      <ConfirmDialog
        visible={open}
        title="Đăng xuất?"
        message={
          hasActiveDelivery
            ? 'Bạn còn đơn đang giao. Đăng xuất sẽ dừng chia sẻ vị trí cho các đơn này.'
            : 'Bạn sẽ cần đăng nhập lại để nhận đơn giao.'
        }
        confirmLabel="Đăng xuất"
        cancelLabel="Ở lại"
        loading={busy}
        onConfirm={confirm}
        onCancel={() => setOpen(false)}
      />
    </>
  )
}
