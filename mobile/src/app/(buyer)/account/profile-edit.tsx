import { useRouter } from 'expo-router'

import { useAuth } from '@/auth/AuthProvider'
import { ErrorState, Screen, Skeleton } from '@/components/ui'
import { ProfileForm } from '@/features/account/components/ProfileForm'
import { useProfile, useVerifiedOnly } from '@/features/account/hooks'
import { useGuestRedirect } from '@/features/account/useGuestRedirect'

export default function ProfileEditScreen() {
  const router = useRouter()
  const { status } = useAuth()
  const verified = useVerifiedOnly()
  useGuestRedirect()
  const profile = useProfile()

  if (status === 'authenticated' && verified && profile.data)
    return <ProfileForm key={profile.data.userId} profile={profile.data} />

  return (
    <Screen title="Sửa hồ sơ" onBack={() => router.back()}>
      {profile.isError ? (
        <ErrorState error={profile.error} heading="Chưa tải được hồ sơ" onRetry={profile.refetch} />
      ) : (
        <Skeleton height={240} />
      )}
    </Screen>
  )
}
