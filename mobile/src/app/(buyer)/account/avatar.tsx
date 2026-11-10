import { useQueryClient } from '@tanstack/react-query'
import * as ImagePicker from 'expo-image-picker'
import { useRouter } from 'expo-router'
import { useState } from 'react'
import { Platform } from 'react-native'

import { getApiError, newIdempotencyKey } from '@/api/client'
import { profileBody, updateMyProfile } from '@/api/user'
import { AppText, Button, ErrorState, InfoCard, Screen, Skeleton, useToast } from '@/components/ui'
import {
  AvatarUploadUnavailableError,
  avatarFileError,
  uploadAvatar,
} from '@/features/account/avatar'
import { Avatar } from '@/features/account/components/Avatar'
import {
  useAccountKeys,
  useGoToAccount,
  useProfile,
  useVerifiedOnly,
} from '@/features/account/hooks'
import { useGuestRedirect } from '@/features/account/useGuestRedirect'

type Phase = 'pick' | 'preview' | 'failed'

const PICK_OPTIONS: ImagePicker.ImagePickerOptions = {
  mediaTypes: ['images'],
  allowsEditing: true,
  aspect: [1, 1],
  quality: 0.8,
}

export default function AvatarScreen() {
  const router = useRouter()
  const toast = useToast()
  const client = useQueryClient()
  const keys = useAccountKeys()
  const goToAccount = useGoToAccount()
  const verified = useVerifiedOnly()
  useGuestRedirect()
  const profile = useProfile()
  const [phase, setPhase] = useState<Phase>('pick')
  const [asset, setAsset] = useState<ImagePicker.ImagePickerAsset | null>(null)
  const [failure, setFailure] = useState('')
  const [saving, setSaving] = useState(false)

  async function pick(source: 'library' | 'camera') {
    try {
      if (source === 'camera') {
        const permission = await ImagePicker.requestCameraPermissionsAsync()
        if (!permission.granted) {
          setFailure('Cho phép VMarket dùng camera trong Cài đặt để chụp ảnh.')
          setPhase('failed')
          return
        }
      }
      const result =
        source === 'camera'
          ? await ImagePicker.launchCameraAsync(PICK_OPTIONS)
          : await ImagePicker.launchImageLibraryAsync(PICK_OPTIONS)
      if (result.canceled) return
      const picked = result.assets[0]
      const problem = avatarFileError(picked)
      if (problem) {
        setFailure(problem)
        setPhase('failed')
        return
      }
      setAsset(picked)
      setPhase('preview')
    } catch (e) {
      setFailure(getApiError(e).message)
      setPhase('failed')
    }
  }

  async function save() {
    if (!asset || !profile.data || saving) return
    setSaving(true)
    try {
      const avatarUrl = await uploadAvatar(asset)
      const saved = await updateMyProfile(
        profileBody(profile.data, { avatarUrl }),
        newIdempotencyKey(),
      )
      client.setQueryData(keys.profile, saved)
      toast.show({ type: 'success', message: 'Ảnh đại diện đã cập nhật' })
      goToAccount()
    } catch (e) {
      setFailure(
        e instanceof AvatarUploadUnavailableError
          ? `${e.message} Ảnh đại diện cũ vẫn được giữ.`
          : getApiError(e).message,
      )
      setPhase('failed')
    } finally {
      setSaving(false)
    }
  }

  const back = () => router.back()

  if (!verified)
    return (
      <Screen title="Đổi ảnh đại diện" onBack={back}>
        <Skeleton height={96} />
      </Screen>
    )

  if (phase === 'preview' && asset)
    return (
      <Screen
        title="Xem trước ảnh đại diện"
        onBack={() => setPhase('pick')}
        testID="avatar-preview"
      >
        <AppText variant="body">
          {asset.fileName ? `${asset.fileName} · Ảnh đã chọn` : 'Ảnh đã chọn'}
        </AppText>
        <Avatar uri={asset.uri} testID="avatar-preview-image" />
        <InfoCard
          title="Ảnh đại diện"
          body="Kiểm tra ảnh trước khi lưu. Bạn có thể chọn lại ảnh."
        />
        {profile.isError ? (
          <ErrorState
            error={profile.error}
            heading="Chưa tải được hồ sơ"
            onRetry={profile.refetch}
          />
        ) : null}
        <Button
          testID="avatar-save"
          title="Lưu ảnh đại diện"
          onPress={save}
          loading={saving}
          disabled={!profile.data}
        />
        <Button
          testID="avatar-pick-other"
          title="Chọn ảnh khác"
          kind="secondary"
          onPress={() => pick('library')}
          disabled={saving}
        />
        <Button
          testID="avatar-back"
          title="Quay lại hồ sơ"
          kind="secondary"
          onPress={back}
          disabled={saving}
        />
      </Screen>
    )

  if (phase === 'failed')
    return (
      <Screen title="Chưa cập nhật được ảnh" onBack={() => setPhase('pick')} testID="avatar-failed">
        <AppText variant="body">Ảnh đại diện cũ vẫn được giữ</AppText>
        <InfoCard
          testID="avatar-failure"
          title="Không tải được ảnh"
          body={failure || 'Kiểm tra kết nối và chọn lại tệp ảnh hợp lệ rồi thử lại.'}
        />
        <Button testID="avatar-pick" title="Chọn ảnh" onPress={() => pick('library')} />
        <Button testID="avatar-back" title="Quay lại hồ sơ" kind="secondary" onPress={back} />
      </Screen>
    )

  return (
    <Screen title="Đổi ảnh đại diện" onBack={back} testID="avatar-pick-screen">
      <AppText variant="body">Chọn ảnh từ thiết bị</AppText>
      <InfoCard
        title="Ảnh đại diện"
        body="Ảnh sẽ hiển thị trên hồ sơ cá nhân. Bạn có thể chọn ảnh khác trước khi lưu."
      />
      <Button testID="avatar-pick" title="Chọn ảnh" onPress={() => pick('library')} />
      {Platform.OS === 'web' ? null : (
        <Button
          testID="avatar-camera"
          title="Chụp ảnh"
          kind="secondary"
          onPress={() => pick('camera')}
        />
      )}
      <Button testID="avatar-back" title="Quay lại hồ sơ" kind="secondary" onPress={back} />
    </Screen>
  )
}
