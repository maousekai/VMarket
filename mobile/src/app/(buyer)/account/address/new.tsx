import { useRouter } from 'expo-router'
import { useState } from 'react'

import { getApiError, newIdempotencyKey } from '@/api/client'
import { setDefaultAddress, type Address } from '@/api/user'
import { AppText, Button, ErrorState, InfoCard, Screen, Skeleton, useToast } from '@/components/ui'
import { AddressCard } from '@/features/account/components/AddressCard'
import { AddressForm, type SavedAddress } from '@/features/account/components/AddressForm'
import { useAddresses, useGoToAccount, useRefreshAccount } from '@/features/account/hooks'
import { useGuestRedirect } from '@/features/account/useGuestRedirect'

type Result =
  | { phase: 'added'; saved: Address; current: Address | null; defaultError: string | null }
  | { phase: 'defaultChanged'; saved: Address; previous: Address | null }

export default function AddressNewScreen() {
  const router = useRouter()
  const toast = useToast()
  useGuestRedirect()
  const query = useAddresses()
  const refresh = useRefreshAccount()
  const goToAccount = useGoToAccount()
  const [result, setResult] = useState<Result | null>(null)
  const [promoting, setPromoting] = useState(false)

  const current = query.data?.find((a) => a.isDefault) ?? null

  async function onSaved({ address, defaultError }: SavedAddress) {
    await refresh.addresses()
    if (address.isDefault && current)
      setResult({ phase: 'defaultChanged', saved: address, previous: current })
    else setResult({ phase: 'added', saved: address, current, defaultError })
  }

  async function makeNewDefault(saved: Address, previous: Address | null) {
    if (promoting) return
    setPromoting(true)
    try {
      const updated = await setDefaultAddress(saved.id, newIdempotencyKey())
      await refresh.addresses()
      setResult({ phase: 'defaultChanged', saved: updated, previous })
    } catch (e) {
      toast.show({ type: 'error', message: getApiError(e).message })
    } finally {
      setPromoting(false)
    }
  }

  if (result?.phase === 'added') {
    const { saved, current: existing, defaultError } = result
    return (
      <Screen title="Đã thêm địa chỉ" onBack={goToAccount} testID="address-added">
        {defaultError ? <InfoCard title="Chưa đặt được làm mặc định" body={defaultError} /> : null}
        {existing ? <AddressCard title="Địa chỉ mặc định" address={existing} compact /> : null}
        <AddressCard
          title="Địa chỉ vừa thêm"
          address={saved}
          compact
          note={saved.isDefault ? 'Đây là địa chỉ mặc định.' : undefined}
        />
        {saved.isDefault ? null : (
          <Button
            testID="address-make-default"
            title="Đặt địa chỉ mới làm mặc định"
            kind="secondary"
            loading={promoting}
            onPress={() => makeNewDefault(saved, existing)}
          />
        )}
        <Button
          testID="address-added-home"
          title="Về tài khoản"
          kind="secondary"
          onPress={goToAccount}
        />
      </Screen>
    )
  }

  if (result?.phase === 'defaultChanged') {
    const { saved, previous } = result
    return (
      <Screen title="Địa chỉ mặc định đã đổi" onBack={goToAccount} testID="address-default-changed">
        <AddressCard title="Địa chỉ mặc định mới" address={saved} compact />
        {previous && previous.id !== saved.id ? (
          <AddressCard
            title="Địa chỉ trước đó"
            address={previous}
            compact
            note="Không còn là địa chỉ mặc định."
          />
        ) : null}
        <AppText variant="body">Thay đổi này không sửa địa chỉ của các đơn đã đặt.</AppText>
        <Button
          testID="address-default-home"
          title="Về tài khoản"
          kind="secondary"
          onPress={goToAccount}
        />
      </Screen>
    )
  }

  return (
    <Screen title="Thêm địa chỉ" onBack={() => router.back()} testID="address-new">
      {query.isPending ? (
        <Skeleton height={320} />
      ) : query.isError ? (
        <ErrorState error={query.error} heading="Chưa tải được địa chỉ" onRetry={query.refetch} />
      ) : (
        <AddressForm
          isFirst={query.data.length === 0}
          intro="Nhập thông tin người nhận"
          onSaved={onSaved}
          onBack={() => router.back()}
        />
      )}
    </Screen>
  )
}
