import { useQueryClient } from '@tanstack/react-query'
import { useRouter } from 'expo-router'
import { useState } from 'react'
import { View } from 'react-native'

import { useAuth } from '@/auth/AuthProvider'
import {
  AppText,
  Button,
  EmptyState,
  ErrorState,
  InfoCard,
  Screen,
  Skeleton,
} from '@/components/ui'
import { AddressList } from '@/features/account/components/AddressList'
import { Avatar } from '@/features/account/components/Avatar'
import { MenuRow } from '@/features/account/components/MenuRow'
import { useProfile, useRefreshAccount } from '@/features/account/hooks'
import { accountRoutes } from '@/features/account/routes'

function GuestPrompt() {
  const router = useRouter()
  return (
    <EmptyState
      testID="account-guest"
      heading="Bạn chưa đăng nhập"
      title="Đăng nhập để tiếp tục"
      body="Đăng nhập để xem đơn hàng, quản lý địa chỉ và nhận thông báo."
      primaryLabel="Đăng nhập"
      onPrimary={() => router.push({ pathname: '/(auth)/login', params: { returnTo: '/account' } })}
      secondaryLabel="Đăng ký"
      onSecondary={() => router.push(accountRoutes.register)}
    />
  )
}

function AccountContent() {
  const router = useRouter()
  const { user, isPending, logout } = useAuth()
  const client = useQueryClient()
  const profile = useProfile()
  const [loggingOut, setLoggingOut] = useState(false)

  async function signOut() {
    if (loggingOut) return
    setLoggingOut(true)
    const userId = user?.userId
    await logout()
    client.removeQueries({ queryKey: ['user', userId] })
    setLoggingOut(false)
  }

  return (
    <>
      {profile.isPending ? (
        <Skeleton height={96} />
      ) : profile.isError ? (
        <ErrorState error={profile.error} heading="Chưa tải được hồ sơ" onRetry={profile.refetch} />
      ) : (
        <View style={{ gap: 12 }}>
          {profile.data.avatarUrl ? <Avatar uri={profile.data.avatarUrl} /> : null}
          <AppText variant="h2" testID="account-name">
            {profile.data.fullName || user?.username}
          </AppText>
          <AppText variant="body" color="textSecondary" testID="account-email">
            {user?.email}
          </AppText>
        </View>
      )}
      {isPending ? (
        <InfoCard
          testID="account-pending"
          title="Email chưa được xác minh"
          body="Xác minh email để chỉnh sửa hồ sơ."
          onPress={() => router.push(accountRoutes.verifyEmail)}
        />
      ) : null}
      <Button
        testID="account-edit-profile"
        title="Chỉnh sửa hồ sơ"
        kind="secondary"
        onPress={() => router.push(accountRoutes.profileEdit)}
      />
      <Button
        testID="account-change-password"
        title="Đổi mật khẩu"
        kind="secondary"
        onPress={() => router.push(accountRoutes.changePassword)}
      />
      <MenuRow
        testID="account-orders"
        label="Đơn hàng của tôi"
        onPress={() => router.push(accountRoutes.orders)}
      />
      <MenuRow
        testID="account-notifications"
        label="Thông báo"
        onPress={() => router.push(accountRoutes.notifications)}
      />
      <MenuRow
        testID="account-assistant"
        label="Trợ lý AI"
        onPress={() => router.push(accountRoutes.assistant)}
      />
      <AddressList />
      <Button
        testID="account-logout"
        title="Đăng xuất"
        kind="neutral"
        loading={loggingOut}
        onPress={signOut}
      />
    </>
  )
}

export default function AccountScreen() {
  const { status } = useAuth()
  const refresh = useRefreshAccount()
  const [refreshing, setRefreshing] = useState(false)

  async function onRefresh() {
    setRefreshing(true)
    await Promise.all([refresh.profile(), refresh.addresses()])
    setRefreshing(false)
  }

  return (
    <Screen
      testID="account-screen"
      title="Tài khoản"
      refreshing={refreshing}
      onRefresh={status === 'authenticated' ? onRefresh : undefined}
    >
      {status === 'loading' ? (
        <Skeleton height={96} />
      ) : status === 'guest' ? (
        <GuestPrompt />
      ) : (
        <AccountContent />
      )}
    </Screen>
  )
}
