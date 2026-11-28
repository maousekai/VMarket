import { useCallback, useRef, useState } from 'react'
import { Alert } from 'react-native'
import { router, useFocusEffect } from 'expo-router'
import { getApiError } from '../api/client'
import {
  changeMyPassword,
  createAddress,
  deleteAddress,
  getMyProfile,
  listMyAddresses,
  newIdempotencyKey,
  setDefaultAddress,
  updateAddress,
  updateMyProfile,
} from '../api/shopApi'
import { useAuth } from '../auth/AuthProvider'
import {
  Badge,
  Body,
  Button,
  Caption,
  Card,
  Choice,
  Field,
  Heading,
  Label,
  Lines,
  Loading,
  Notice,
  Screen,
} from '../components/ui'
import { DA_NANG_PROVINCE, DA_NANG_WARDS } from '../data/daNangAddress'
import BuyerNav from './BuyerNav'
import { formatAddress } from './CartScreen'
import { setCheckout, useCheckout } from './checkoutStore'

const PHONE = /^(0|\+84)\d{9,10}$/

/** Tai khoan nguoi mua (frame 3.10, 3.87, 3.88). */
export function AccountScreen() {
  const { session, logout } = useAuth()
  const [profile, setProfile] = useState(null)
  const [addresses, setAddresses] = useState([])
  const [error, setError] = useState('')
  const [signingOut, setSigningOut] = useState(false)

  useFocusEffect(
    useCallback(() => {
      let current = true
      Promise.all([getMyProfile(), listMyAddresses()])
        .then(([loaded, list]) => {
          if (!current) return
          setProfile(loaded)
          setAddresses(list || [])
          setError('')
        })
        .catch((failure) => current && setError(getApiError(failure).message))
      return () => {
        current = false
      }
    }, []),
  )

  const fallback = addresses.find((address) => address.isDefault) || addresses[0]
  return (
    <Screen title="Tài khoản">
      <Heading>{profile?.fullName || session?.username || 'Tài khoản'}</Heading>
      <Caption>{profile?.email || session?.email || ''}</Caption>
      {error ? <Notice tone="danger">{error}</Notice> : null}
      <Button title="Chỉnh sửa hồ sơ" variant="secondary" onPress={() => router.push('/profile')} />
      <Button title="Đổi mật khẩu" variant="secondary" onPress={() => router.push('/password')} />
      {fallback ? (
        <Card title="Địa chỉ mặc định">
          <Lines
            lines={[`${fallback.recipientName} · ${fallback.phone}`, formatAddress(fallback)]}
          />
        </Card>
      ) : (
        <Card title="Chưa có địa chỉ">
          <Body>Thêm địa chỉ nhận hàng để đặt hàng nhanh hơn.</Body>
        </Card>
      )}
      <Button
        title={addresses.length > 1 ? `Sổ địa chỉ · ${addresses.length} địa chỉ` : 'Sổ địa chỉ'}
        variant="secondary"
        onPress={() => router.push('/addresses')}
      />
      <Button
        title="Thêm địa chỉ"
        onPress={() => router.push({ pathname: '/addresses/[id]', params: { id: 'new' } })}
      />
      <Button title="Đơn hàng của tôi" variant="secondary" onPress={() => router.push('/orders')} />
      <Button title="Thông báo" variant="secondary" onPress={() => router.push('/notifications')} />
      <Button
        title="Đăng xuất"
        variant="secondary"
        loading={signingOut}
        onPress={async () => {
          setSigningOut(true)
          await logout()
        }}
      />
    </Screen>
  )
}

/** Sua ho so (frame 3.50; 3.55 da luu; 3.56 chua luu duoc). PUT giu nguyen cac truong khong sua. */
export function ProfileScreen() {
  const [profile, setProfile] = useState(null)
  const [values, setValues] = useState({ fullName: '', phone: '' })
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const attempt = useRef(null)

  useFocusEffect(
    useCallback(() => {
      let current = true
      getMyProfile()
        .then((loaded) => {
          if (!current) return
          setProfile(loaded)
          setValues({ fullName: loaded?.fullName || '', phone: loaded?.phone || '' })
        })
        .catch((failure) => current && setResult({ error: getApiError(failure) }))
      return () => {
        current = false
      }
    }, []),
  )

  const phoneError =
    values.phone.trim() && !PHONE.test(values.phone.trim()) ? 'Số điện thoại không hợp lệ.' : ''

  async function save() {
    const body = {
      fullName: values.fullName.trim() || null,
      phone: values.phone.trim() || null,
      avatarUrl: profile?.avatarUrl ?? null,
      dateOfBirth: profile?.dateOfBirth ?? null,
      gender: profile?.gender ?? null,
    }
    const signature = JSON.stringify(body)
    if (attempt.current?.signature !== signature)
      attempt.current = { signature, key: newIdempotencyKey() }
    setBusy(true)
    setResult(null)
    try {
      setProfile(await updateMyProfile(body, attempt.current.key))
      setResult({ ok: true })
    } catch (failure) {
      setResult({ error: getApiError(failure) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen title="Sửa hồ sơ" footer={<BuyerNav active="account" />}>
      {!profile && !result ? <Loading /> : null}
      <Field
        label="Họ và tên"
        value={values.fullName}
        maxLength={100}
        onChangeText={(fullName) => setValues((previous) => ({ ...previous, fullName }))}
        error={result?.error?.fieldErrors?.fullName}
      />
      <Field
        label="Số điện thoại"
        value={values.phone}
        keyboardType="phone-pad"
        onChangeText={(phone) => setValues((previous) => ({ ...previous, phone }))}
        error={phoneError || result?.error?.fieldErrors?.phone}
      />
      {result?.ok ? (
        <Card title="Đã lưu thay đổi">
          <Body>Hồ sơ của bạn đã được cập nhật.</Body>
        </Card>
      ) : null}
      {result?.error ? (
        <Card title="Chưa lưu được">
          <Body>{`${result.error.message} Bạn có thể thử lưu lại.`}</Body>
        </Card>
      ) : null}
      <Button title="Lưu thay đổi" loading={busy} disabled={Boolean(phoneError)} onPress={save} />
      <Button title="Quay lại" variant="secondary" onPress={() => router.back()} />
    </Screen>
  )
}

/** Doi mat khau (frame 3.51). */
export function PasswordScreen() {
  const [values, setValues] = useState({ current: '', next: '', confirm: '' })
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const set = (key) => (value) => setValues((previous) => ({ ...previous, [key]: value }))
  const mismatch =
    values.confirm && values.confirm !== values.next ? 'Mật khẩu nhập lại chưa khớp.' : ''
  const ready = values.current && values.next.length >= 8 && !mismatch && values.confirm

  async function save() {
    setBusy(true)
    setResult(null)
    try {
      await changeMyPassword({ currentPassword: values.current, newPassword: values.next })
      setValues({ current: '', next: '', confirm: '' })
      setResult({ ok: true })
    } catch (failure) {
      setResult({ error: getApiError(failure) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Screen title="Đổi mật khẩu" footer={<BuyerNav active="account" />}>
      <Field
        label="Mật khẩu hiện tại"
        secure
        value={values.current}
        onChangeText={set('current')}
        error={result?.error?.fieldErrors?.currentPassword}
      />
      <Field
        label="Mật khẩu mới"
        secure
        hint="Ít nhất 8 ký tự."
        value={values.next}
        onChangeText={set('next')}
        error={result?.error?.fieldErrors?.newPassword}
      />
      <Field
        label="Nhập lại mật khẩu mới"
        secure
        value={values.confirm}
        onChangeText={set('confirm')}
        error={mismatch}
      />
      {result?.ok ? <Notice tone="success">Đã đổi mật khẩu.</Notice> : null}
      {result?.error ? <Notice tone="danger">{result.error.message}</Notice> : null}
      <Button title="Đổi mật khẩu" loading={busy} disabled={!ready} onPress={save} />
      <Button title="Quay lại" variant="secondary" onPress={() => router.back()} />
    </Screen>
  )
}

/**
 * So dia chi (frame 3.54 chua co, 3.53 xoa, 3.86 doi mac dinh). `pick`: chon dia chi giao cho lan
 * dat hang nay roi quay lai gio hang.
 */
export function AddressesScreen({ pick = false }) {
  const [state, setState] = useState({ loading: true, items: [], error: '' })
  const [notice, setNotice] = useState(null)
  const checkout = useCheckout()

  const load = useCallback(async () => {
    try {
      setState({ loading: false, items: (await listMyAddresses()) || [], error: '' })
    } catch (failure) {
      setState((previous) => ({ ...previous, loading: false, error: getApiError(failure).message }))
    }
  }, [])

  useFocusEffect(
    useCallback(() => {
      load()
    }, [load]),
  )

  async function run(action, message) {
    setNotice(null)
    try {
      await action()
      setNotice({ tone: 'success', message })
      await load()
    } catch (failure) {
      setNotice({ tone: 'danger', message: getApiError(failure).message })
    }
  }

  function askDelete(address) {
    Alert.alert('Xoá địa chỉ?', formatAddress(address), [
      { text: 'Giữ lại', style: 'cancel' },
      {
        text: 'Xoá',
        style: 'destructive',
        onPress: () => run(() => deleteAddress(address.id), 'Đã xoá địa chỉ.'),
      },
    ])
  }

  return (
    <Screen
      title={pick ? 'Chọn địa chỉ nhận hàng' : 'Sổ địa chỉ'}
      footer={<BuyerNav active={pick ? 'cart' : 'account'} />}
    >
      {state.loading ? <Loading /> : null}
      {state.error ? <Notice tone="danger">{state.error}</Notice> : null}
      {notice ? <Notice tone={notice.tone}>{notice.message}</Notice> : null}
      {!state.loading && !state.items.length && !state.error ? (
        <Card title="Chưa có địa chỉ">
          <Body>Thêm địa chỉ nhận hàng trong Thành phố Đà Nẵng để đặt hàng.</Body>
        </Card>
      ) : null}
      {state.items.map((address) => (
        <Card key={address.id} title={`${address.recipientName} · ${address.phone}`}>
          <Lines
            lines={[formatAddress(address), address.note ? `Ghi chú: ${address.note}` : null]}
          />
          {address.isDefault ? <Badge label="Mặc định" tone="success" /> : null}
          {pick ? (
            <Button
              title={checkout.addressId === address.id ? 'Đang chọn' : 'Giao đến địa chỉ này'}
              onPress={() => {
                setCheckout({ addressId: address.id })
                router.back()
              }}
            />
          ) : (
            <>
              <Button
                title="Sửa địa chỉ"
                variant="secondary"
                onPress={() =>
                  router.push({ pathname: '/addresses/[id]', params: { id: address.id } })
                }
              />
              {!address.isDefault ? (
                <Button
                  title="Đặt làm mặc định"
                  variant="secondary"
                  onPress={() =>
                    run(() => setDefaultAddress(address.id), 'Địa chỉ mặc định đã đổi.')
                  }
                />
              ) : null}
              <Button title="Xoá địa chỉ" variant="secondary" onPress={() => askDelete(address)} />
            </>
          )}
        </Card>
      ))}
      <Button
        title="Thêm địa chỉ"
        onPress={() => router.push({ pathname: '/addresses/[id]', params: { id: 'new' } })}
      />
    </Screen>
  )
}

const EMPTY = { recipientName: '', phone: '', streetAddress: '', ward: '', note: '' }

/** Them / sua dia chi (frame 3.63, 3.83 da dien, 3.84 khong hop le, 3.85 da them). */
export function AddressFormScreen({ addressId }) {
  const creating = !addressId || addressId === 'new'
  const [values, setValues] = useState(EMPTY)
  const [loaded, setLoaded] = useState(creating)
  const [errors, setErrors] = useState({})
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [wardFilter, setWardFilter] = useState('')
  const [pickingWard, setPickingWard] = useState(false)
  const attempt = useRef(null)

  useFocusEffect(
    useCallback(() => {
      if (creating) return undefined
      let current = true
      listMyAddresses()
        .then((list) => {
          const address = (list || []).find((item) => item.id === addressId)
          if (!current || !address) return
          setValues({
            recipientName: address.recipientName || '',
            phone: address.phone || '',
            streetAddress: address.streetAddress || '',
            ward: address.ward || '',
            note: address.note || '',
          })
          setLoaded(true)
        })
        .catch((failure) => current && setMessage(getApiError(failure).message))
      return () => {
        current = false
      }
    }, [addressId, creating]),
  )

  const set = (key) => (value) => {
    setValues((previous) => ({ ...previous, [key]: value }))
    setErrors((previous) => ({ ...previous, [key]: undefined }))
  }

  function validate() {
    const next = {}
    if (!values.recipientName.trim()) next.recipientName = 'Nhập tên người nhận.'
    if (!PHONE.test(values.phone.trim())) next.phone = 'Số điện thoại không hợp lệ.'
    if (!values.streetAddress.trim()) next.streetAddress = 'Nhập số nhà, tên đường.'
    if (!values.ward) next.ward = 'Chọn phường / xã.'
    setErrors(next)
    return !Object.keys(next).length
  }

  async function save() {
    setMessage('')
    if (!validate()) {
      setMessage('Địa chỉ chưa hợp lệ. Kiểm tra các ô được đánh dấu.')
      return
    }
    const body = {
      recipientName: values.recipientName.trim(),
      phone: values.phone.trim(),
      province: DA_NANG_PROVINCE,
      ward: values.ward,
      streetAddress: values.streetAddress.trim(),
      note: values.note.trim() || null,
    }
    const signature = JSON.stringify({ addressId, body })
    if (attempt.current?.signature !== signature)
      attempt.current = { signature, key: newIdempotencyKey() }
    setBusy(true)
    try {
      const saved = creating
        ? await createAddress(body, attempt.current.key)
        : await updateAddress(addressId, body, attempt.current.key)
      if (creating && saved?.id) setCheckout({ addressId: saved.id })
      Alert.alert(creating ? 'Đã thêm địa chỉ' : 'Đã lưu địa chỉ', formatAddress(saved || body))
      router.back()
    } catch (failure) {
      const error = getApiError(failure)
      setErrors(error.fieldErrors || {})
      setMessage(error.message)
    } finally {
      setBusy(false)
    }
  }

  const wards = DA_NANG_WARDS.filter((ward) =>
    ward.toLowerCase().includes(wardFilter.trim().toLowerCase()),
  )

  return (
    <Screen
      title={creating ? 'Thêm địa chỉ' : 'Sửa địa chỉ'}
      footer={<BuyerNav active="account" />}
    >
      {!loaded ? <Loading /> : null}
      <Field
        label="Tên người nhận"
        value={values.recipientName}
        maxLength={100}
        onChangeText={set('recipientName')}
        error={errors.recipientName}
      />
      <Field
        label="Số điện thoại"
        value={values.phone}
        keyboardType="phone-pad"
        onChangeText={set('phone')}
        error={errors.phone}
      />
      <Field
        label="Số nhà, tên đường"
        value={values.streetAddress}
        maxLength={255}
        onChangeText={set('streetAddress')}
        error={errors.streetAddress}
      />
      <Label>Phường / xã</Label>
      <Choice
        value={values.ward || 'Chọn phường / xã'}
        selected={Boolean(values.ward)}
        onPress={() => setPickingWard((value) => !value)}
      />
      {errors.ward ? <Notice tone="danger">{errors.ward}</Notice> : null}
      {pickingWard ? (
        <Card title="Chọn phường / xã">
          <Field placeholder="Lọc theo tên" value={wardFilter} onChangeText={setWardFilter} />
          {wards.map((ward) => (
            <Choice
              key={ward}
              value={ward}
              selected={values.ward === ward}
              onPress={() => {
                set('ward')(ward)
                setPickingWard(false)
              }}
            />
          ))}
        </Card>
      ) : null}
      <Choice label="Tỉnh / thành" value={`${DA_NANG_PROVINCE} (hiện chỉ giao trong Đà Nẵng)`} />
      <Field label="Ghi chú" value={values.note} maxLength={255} onChangeText={set('note')} />
      {message ? (
        <Card title="Địa chỉ không hợp lệ">
          <Body>{message}</Body>
        </Card>
      ) : null}
      <Button title="Lưu địa chỉ" loading={busy} onPress={save} />
      <Button title="Huỷ" variant="secondary" onPress={() => router.back()} />
    </Screen>
  )
}
