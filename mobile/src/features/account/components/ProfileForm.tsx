import { useRouter } from 'expo-router'
import { useRef, useState } from 'react'

import { getApiError, newIdempotencyKey } from '@/api/client'
import { profileBody, updateMyProfile, type Profile } from '@/api/user'
import { AppText, Button, InfoCard, Screen, TextField } from '@/components/ui'

import { isoToDisplay, maskDate } from '../dates'
import { useAccountKeys, useGoToAccount } from '../hooks'
import { accountRoutes } from '../routes'
import {
  GENDER_OPTIONS,
  genderLabel,
  profileChanges,
  validateProfile,
  type ProfileValues,
} from '../validation'
import { PickerSheet } from './PickerSheet'
import { useQueryClient } from '@tanstack/react-query'

type Phase = 'form' | 'saved' | 'failed'

const GENDER_GROUPS = [{ options: GENDER_OPTIONS }]

/** Edit profile (3.50) with the in-place result states "Đã lưu thay đổi" (3.55) / "Chưa lưu được" (3.56). */
export function ProfileForm({ profile }: { profile: Profile }) {
  const router = useRouter()
  const client = useQueryClient()
  const keys = useAccountKeys()
  const goToAccount = useGoToAccount()
  const [values, setValues] = useState<ProfileValues>({
    fullName: profile.fullName ?? '',
    phone: profile.phone ?? '',
    dateOfBirth: isoToDisplay(profile.dateOfBirth),
    gender: profile.gender,
  })
  const [errors, setErrors] = useState<Partial<Record<keyof ProfileValues, string>>>({})
  const [phase, setPhase] = useState<Phase>('form')
  const [failureMessage, setFailureMessage] = useState('')
  const [saving, setSaving] = useState(false)
  const [genderOpen, setGenderOpen] = useState(false)
  // A retry of the same request reuses its key; any edit starts a new request.
  const attempt = useRef<{ signature: string; key: string } | null>(null)

  function setField<K extends keyof ProfileValues>(name: K, value: ProfileValues[K]) {
    setValues((v) => ({ ...v, [name]: value }))
    setErrors((e) => ({ ...e, [name]: undefined }))
  }

  async function save() {
    if (saving) return
    const found = validateProfile(values)
    setErrors(found)
    if (Object.keys(found).length) {
      setPhase('form')
      return
    }
    // The backend replaces the whole profile: unchanged fields (avatarUrl) are sent again.
    const body = profileBody(profile, profileChanges(values))
    const signature = JSON.stringify(body)
    if (attempt.current?.signature !== signature)
      attempt.current = { signature, key: newIdempotencyKey() }
    setSaving(true)
    try {
      const saved = await updateMyProfile(body, attempt.current.key)
      client.setQueryData(keys.profile, saved)
      setPhase('saved')
    } catch (e) {
      const err = getApiError(e)
      if (err.status === 400 && Object.keys(err.fieldErrors).length) {
        setErrors(err.fieldErrors)
        setPhase('form')
      } else {
        setFailureMessage(err.message)
        setPhase('failed')
      }
    } finally {
      setSaving(false)
    }
  }

  if (phase === 'saved')
    return (
      <Screen title="Đã lưu thay đổi" onBack={goToAccount} testID="profile-saved">
        <AppText variant="body">Thông tin đã được cập nhật</AppText>
        <Button testID="profile-saved-home" title="Về tài khoản" onPress={goToAccount} />
      </Screen>
    )

  if (phase === 'failed')
    return (
      <Screen title="Chưa lưu được" onBack={() => setPhase('form')} testID="profile-failed">
        <AppText variant="body">Thay đổi của bạn vẫn được giữ</AppText>
        <InfoCard
          title="Thử lại khi có mạng"
          body={failureMessage || 'Kiểm tra kết nối rồi thử lưu lại.'}
        />
        <Button testID="profile-retry" title="Thử lại" onPress={save} loading={saving} />
        <Button
          testID="profile-failed-back"
          title="Quay lại hồ sơ"
          kind="secondary"
          onPress={() => setPhase('form')}
        />
      </Screen>
    )

  return (
    <Screen title="Sửa hồ sơ" onBack={() => router.back()} testID="profile-edit">
      <AppText variant="body">Thông tin cá nhân</AppText>
      <TextField
        testID="profile-name"
        label="Họ tên"
        value={values.fullName}
        onChangeText={(t) => setField('fullName', t)}
        error={errors.fullName}
        maxLength={100}
        autoComplete="name"
        disabled={saving}
      />
      <TextField
        testID="profile-phone"
        label="Số điện thoại"
        value={values.phone}
        onChangeText={(t) => setField('phone', t)}
        error={errors.phone}
        keyboardType="phone-pad"
        autoComplete="tel"
        maxLength={15}
        disabled={saving}
      />
      <TextField
        testID="profile-dob"
        label="Ngày sinh"
        placeholder="dd/mm/yyyy"
        value={values.dateOfBirth}
        onChangeText={(t) => setField('dateOfBirth', maskDate(t))}
        error={errors.dateOfBirth}
        keyboardType="number-pad"
        maxLength={10}
        disabled={saving}
      />
      <TextField
        testID="profile-gender"
        picker
        label="Giới tính"
        placeholder="Chọn giới tính"
        value={genderLabel(values.gender)}
        onPress={() => setGenderOpen(true)}
        error={errors.gender}
        disabled={saving}
      />
      <Button testID="profile-save" title="Lưu hồ sơ" onPress={save} loading={saving} />
      <Button
        testID="profile-avatar"
        title="Đổi ảnh đại diện"
        kind="secondary"
        onPress={() => router.push(accountRoutes.avatar)}
        disabled={saving}
      />
      <Button
        testID="profile-back"
        title="Quay lại"
        kind="secondary"
        onPress={() => router.back()}
        disabled={saving}
      />
      <PickerSheet
        testID="gender-sheet"
        visible={genderOpen}
        title="Giới tính"
        groups={GENDER_GROUPS}
        value={values.gender}
        onSelect={(g) => setField('gender', g)}
        onClose={() => setGenderOpen(false)}
      />
    </Screen>
  )
}
