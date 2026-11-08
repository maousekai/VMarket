import { useMemo, useRef, useState } from 'react'

import { getApiError, newIdempotencyKey } from '@/api/client'
import { createAddress, setDefaultAddress, updateAddress, type Address } from '@/api/user'
import { AppText, Button, InfoCard, TextField } from '@/components/ui'

import { DA_NANG_PROVINCE, DA_NANG_WARD_GROUPS } from '../daNangAddress'
import { addressBody, validateAddress, type AddressValues } from '../validation'
import { PickerSheet, type PickerGroup } from './PickerSheet'

export interface SavedAddress {
  address: Address
  /** Set when the address was saved but making it the default failed. */
  defaultError: string | null
}

interface Props {
  /** Present when editing. */
  address?: Address
  /** No address exists yet: the backend makes the first one the default. */
  isFirst: boolean
  intro: string
  onSaved: (result: SavedAddress) => void
  onBack: () => void
}

type Picker = 'ward' | 'province' | 'default' | null
type Field = keyof AddressValues

const WARD_GROUPS: PickerGroup<string>[] = DA_NANG_WARD_GROUPS.map((g) => ({
  label: g.label,
  options: g.wards.map((w) => ({ value: w, label: w })),
}))
const PROVINCE_GROUPS: PickerGroup<string>[] = [
  { options: [{ value: DA_NANG_PROVINCE, label: DA_NANG_PROVINCE }] },
]
const DEFAULT_GROUPS: PickerGroup<string>[] = [
  {
    options: [
      { value: 'yes', label: 'Có' },
      { value: 'no', label: 'Không' },
    ],
  },
]

export function AddressForm({ address, isFirst, intro, onSaved, onBack }: Props) {
  const forcedDefault = isFirst || !!address?.isDefault
  const [values, setValues] = useState<AddressValues>({
    recipientName: address?.recipientName ?? '',
    phone: address?.phone ?? '',
    streetAddress: address?.streetAddress ?? '',
    ward: address?.ward ?? '',
    province: address?.province || DA_NANG_PROVINCE,
    makeDefault: forcedDefault,
  })
  const [touched, setTouched] = useState<Partial<Record<Field, boolean>>>({})
  const [serverErrors, setServerErrors] = useState<Partial<Record<Field, string>>>({})
  const [failure, setFailure] = useState<{ title: string; message: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const [picker, setPicker] = useState<Picker>(null)
  // A retry of the same request reuses its key; any edit starts a new request.
  const attempt = useRef<{ signature: string; key: string } | null>(null)

  const clientErrors = useMemo(() => validateAddress(values), [values])
  const invalid = Object.keys(clientErrors).length > 0

  function setField<K extends Field>(name: K, value: AddressValues[K]) {
    setValues((v) => ({ ...v, [name]: value }))
    setServerErrors((e) => ({ ...e, [name]: undefined }))
    setFailure(null)
  }

  const touch = (name: Field) => setTouched((t) => ({ ...t, [name]: true }))
  const errorOf = (name: Field) =>
    serverErrors[name] || (touched[name] ? clientErrors[name] : undefined)

  async function submit() {
    if (saving || invalid) return
    const body = addressBody(values)
    const signature = JSON.stringify({ id: address?.id, body })
    if (attempt.current?.signature !== signature)
      attempt.current = { signature, key: newIdempotencyKey() }
    setSaving(true)
    setFailure(null)
    let saved: Address
    try {
      saved = address
        ? await updateAddress(address.id, body, attempt.current.key)
        : await createAddress(body, attempt.current.key)
    } catch (e) {
      const err = getApiError(e)
      setServerErrors(err.fieldErrors)
      setFailure({
        title: err.status === 400 ? 'Địa chỉ không hợp lệ' : 'Chưa lưu được địa chỉ',
        message: err.message,
      })
      setSaving(false)
      return
    }
    let defaultError: string | null = null
    if (values.makeDefault && !forcedDefault) {
      try {
        saved = await setDefaultAddress(saved.id, newIdempotencyKey())
      } catch (e) {
        defaultError = getApiError(e).message
      }
    }
    setSaving(false)
    onSaved({ address: saved, defaultError })
  }

  return (
    <>
      <AppText variant="body">{intro}</AppText>
      {failure ? (
        <InfoCard testID="address-failure" title={failure.title} body={failure.message} />
      ) : null}
      <TextField
        testID="address-recipient"
        label="Người nhận"
        required
        placeholder="Nhập họ tên"
        value={values.recipientName}
        onChangeText={(t) => setField('recipientName', t)}
        onBlur={() => touch('recipientName')}
        error={errorOf('recipientName')}
        maxLength={100}
        autoComplete="name"
        disabled={saving}
      />
      <TextField
        testID="address-phone"
        label="Số điện thoại"
        required
        placeholder="Nhập số điện thoại"
        value={values.phone}
        onChangeText={(t) => setField('phone', t)}
        onBlur={() => touch('phone')}
        error={errorOf('phone')}
        keyboardType="phone-pad"
        autoComplete="tel"
        maxLength={15}
        disabled={saving}
      />
      <TextField
        testID="address-street"
        label="Số nhà, tên đường"
        required
        placeholder="Nhập địa chỉ"
        value={values.streetAddress}
        onChangeText={(t) => setField('streetAddress', t)}
        onBlur={() => touch('streetAddress')}
        error={errorOf('streetAddress')}
        maxLength={255}
        disabled={saving}
      />
      <TextField
        testID="address-ward"
        picker
        label="Phường / xã"
        required
        placeholder="Chọn phường / xã"
        value={values.ward}
        onPress={() => setPicker('ward')}
        error={errorOf('ward')}
        disabled={saving}
      />
      <TextField
        testID="address-province"
        picker
        label="Tỉnh / thành"
        required
        placeholder="Chọn tỉnh / thành"
        value={values.province}
        helper="Hiện chỉ giao hàng trong Thành phố Đà Nẵng."
        onPress={() => setPicker('province')}
        error={errorOf('province')}
        disabled={saving}
      />
      <TextField
        testID="address-default"
        picker
        label="Địa chỉ mặc định"
        value={values.makeDefault ? 'Có' : 'Không'}
        helper={forcedDefault ? 'Địa chỉ này đang là địa chỉ mặc định.' : undefined}
        onPress={() => setPicker('default')}
        disabled={saving || forcedDefault}
      />
      <Button
        testID="address-save"
        title="Lưu địa chỉ"
        onPress={submit}
        loading={saving}
        disabled={invalid}
      />
      <Button
        testID="address-back"
        title="Quay lại"
        kind="secondary"
        onPress={onBack}
        disabled={saving}
      />

      <PickerSheet
        testID="ward-sheet"
        visible={picker === 'ward'}
        title="Chọn phường / xã"
        groups={WARD_GROUPS}
        value={values.ward}
        onSelect={(v) => {
          setField('ward', v)
          touch('ward')
        }}
        onClose={() => setPicker(null)}
      />
      <PickerSheet
        testID="province-sheet"
        visible={picker === 'province'}
        title="Chọn tỉnh / thành"
        groups={PROVINCE_GROUPS}
        value={values.province}
        onSelect={(v) => setField('province', v)}
        onClose={() => setPicker(null)}
      />
      <PickerSheet
        testID="default-sheet"
        visible={picker === 'default'}
        title="Địa chỉ mặc định"
        groups={DEFAULT_GROUPS}
        value={values.makeDefault ? 'yes' : 'no'}
        onSelect={(v) => setField('makeDefault', v === 'yes')}
        onClose={() => setPicker(null)}
      />
    </>
  )
}
