import { useQueryClient } from '@tanstack/react-query'
import { useLocalSearchParams, useRouter } from 'expo-router'
import { useState } from 'react'

import { getApiError, newIdempotencyKey } from '@/api/client'
import { deleteAddress, type Address } from '@/api/user'
import {
  AppText,
  Button,
  EmptyState,
  ErrorState,
  Screen,
  Skeleton,
  useToast,
} from '@/components/ui'
import { AddressCard } from '@/features/account/components/AddressCard'
import {
  useAccountKeys,
  useAddresses,
  useGoToAccount,
  useRefreshAccount,
} from '@/features/account/hooks'
import { useGuestRedirect } from '@/features/account/useGuestRedirect'

export default function AddressDeleteScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const router = useRouter()
  const toast = useToast()
  useGuestRedirect()
  const query = useAddresses()
  const refresh = useRefreshAccount()
  const client = useQueryClient()
  const keys = useAccountKeys()
  const goToAccount = useGoToAccount()
  const [deleting, setDeleting] = useState(false)
  // One key per user action; a retry after a network error reuses it.
  const [key] = useState(newIdempotencyKey)
  const address = query.data?.find((a) => a.id === id)

  async function confirm() {
    if (deleting || !id) return
    setDeleting(true)
    try {
      await deleteAddress(id, key)
    } catch (e) {
      const err = getApiError(e)
      // Already removed elsewhere: the goal of the action is met.
      if (err.code !== 'ADDRESS_NOT_FOUND') {
        toast.show({ type: 'error', message: err.message })
        setDeleting(false)
        return
      }
    }
    client.setQueryData<Address[]>(keys.addresses, (old) => old?.filter((a) => a.id !== id))
    toast.show({ type: 'success', message: 'Đã xóa địa chỉ.' })
    goToAccount()
    // The backend may have moved the default to another address: always refetch the list.
    refresh.addresses()
  }

  return (
    <Screen title="Xóa địa chỉ?" onBack={() => router.back()} testID="address-delete">
      {query.isPending ? (
        <Skeleton height={160} />
      ) : query.isError ? (
        <ErrorState error={query.error} heading="Chưa tải được địa chỉ" onRetry={query.refetch} />
      ) : !address ? (
        <EmptyState
          heading="Không tìm thấy địa chỉ"
          body="Địa chỉ này có thể đã bị xóa."
          primaryLabel="Về tài khoản"
          onPrimary={goToAccount}
        />
      ) : (
        <>
          <AppText variant="body">Bạn sẽ cần thêm địa chỉ khi đặt hàng</AppText>
          <AddressCard
            title="Địa chỉ đã chọn"
            address={address}
            compact
            note={
              address.isDefault && query.data.length > 1
                ? 'Đây là địa chỉ mặc định. Sau khi xóa, một địa chỉ còn lại sẽ được đặt làm mặc định.'
                : undefined
            }
          />
          <Button
            testID="address-delete-confirm"
            title="Xóa địa chỉ"
            onPress={confirm}
            loading={deleting}
          />
          <Button
            testID="address-delete-keep"
            title="Giữ địa chỉ"
            kind="secondary"
            onPress={() => router.back()}
            disabled={deleting}
          />
        </>
      )}
    </Screen>
  )
}
