import { useLocalSearchParams, useRouter } from 'expo-router'

import { EmptyState, ErrorState, Screen, Skeleton, useToast } from '@/components/ui'
import { AddressForm, type SavedAddress } from '@/features/account/components/AddressForm'
import { useAddresses, useGoToAccount, useRefreshAccount } from '@/features/account/hooks'
import { useGuestRedirect } from '@/features/account/useGuestRedirect'

export default function AddressEditScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const router = useRouter()
  const toast = useToast()
  useGuestRedirect()
  const query = useAddresses()
  const refresh = useRefreshAccount()
  const goToAccount = useGoToAccount()
  const address = query.data?.find((a) => a.id === id)

  async function onSaved({ defaultError }: SavedAddress) {
    await refresh.addresses()
    toast.show(
      defaultError
        ? { type: 'error', message: defaultError }
        : { type: 'success', message: 'Đã cập nhật địa chỉ.' },
    )
    goToAccount()
  }

  return (
    <Screen title="Sửa địa chỉ" onBack={() => router.back()} testID="address-edit">
      {query.isPending ? (
        <Skeleton height={320} />
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
        <AddressForm
          key={address.id}
          address={address}
          isFirst={false}
          intro={address.isDefault ? 'Địa chỉ mặc định khi đặt hàng' : 'Nhập thông tin người nhận'}
          onSaved={onSaved}
          onBack={() => router.back()}
        />
      )}
    </Screen>
  )
}
