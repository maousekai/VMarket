import { useRouter } from 'expo-router'
import { useState } from 'react'
import { View } from 'react-native'

import { getApiError, newIdempotencyKey } from '@/api/client'
import { setDefaultAddress, type Address } from '@/api/user'
import { AppText, Button, ErrorState, Skeleton, useToast } from '@/components/ui'

import { useAddresses, useRefreshAccount } from '../hooks'
import { accountRoutes } from '../routes'
import { AddressCard } from './AddressCard'

/** Address section of the account home: default first, per-card actions, empty state (3.54). */
export function AddressList() {
  const router = useRouter()
  const toast = useToast()
  const query = useAddresses()
  const refresh = useRefreshAccount()
  const [busyId, setBusyId] = useState<string | null>(null)

  async function makeDefault(address: Address) {
    if (busyId) return
    setBusyId(address.id)
    try {
      await setDefaultAddress(address.id, newIdempotencyKey())
      await refresh.addresses()
      toast.show({ type: 'success', message: 'Đã đặt địa chỉ mặc định.' })
    } catch (e) {
      toast.show({ type: 'error', message: getApiError(e).message })
    } finally {
      setBusyId(null)
    }
  }

  if (query.isPending) return <Skeleton height={112} />
  if (query.isError)
    return (
      <ErrorState error={query.error} heading="Chưa tải được địa chỉ" onRetry={query.refetch} />
    )

  const addresses = query.data
  const addButton = (
    <Button
      testID="account-address-add"
      title="Thêm địa chỉ"
      onPress={() => router.push(accountRoutes.addressNew)}
    />
  )

  if (addresses.length === 0)
    return (
      <View style={{ gap: 12 }} testID="account-address-empty">
        <AppText variant="body">Thêm địa chỉ để nhận hàng</AppText>
        {addButton}
      </View>
    )

  const single = addresses.length === 1
  return (
    <View style={{ gap: 12 }}>
      {addresses.map((address) => {
        const edit = () => router.push(accountRoutes.addressEdit(address.id))
        const remove = () => router.push(accountRoutes.addressDelete(address.id))
        return (
          <View key={address.id} style={{ gap: 12 }}>
            <AddressCard
              testID={`account-address-${address.id}`}
              title={address.isDefault ? 'Địa chỉ mặc định' : 'Địa chỉ khác'}
              address={address}
            >
              {single ? null : (
                <View style={{ flexDirection: 'row', gap: 8 }}>
                  <Button
                    title="Sửa"
                    kind="secondary"
                    size="M"
                    onPress={edit}
                    style={{ flex: 1 }}
                  />
                  <Button
                    title="Xóa"
                    kind="secondary"
                    size="M"
                    onPress={remove}
                    style={{ flex: 1 }}
                  />
                  {address.isDefault ? null : (
                    <Button
                      testID={`account-address-default-${address.id}`}
                      title="Mặc định"
                      kind="secondary"
                      size="M"
                      loading={busyId === address.id}
                      disabled={!!busyId && busyId !== address.id}
                      onPress={() => makeDefault(address)}
                      style={{ flex: 1 }}
                    />
                  )}
                </View>
              )}
            </AddressCard>
            {single ? (
              <>
                <Button
                  testID="account-address-edit"
                  title="Sửa địa chỉ"
                  kind="secondary"
                  onPress={edit}
                />
                <Button
                  testID="account-address-delete"
                  title="Xóa địa chỉ"
                  kind="secondary"
                  onPress={remove}
                />
              </>
            ) : null}
          </View>
        )
      })}
      {addButton}
    </View>
  )
}
