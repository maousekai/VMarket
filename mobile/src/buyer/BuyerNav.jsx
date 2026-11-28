import { router } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { BottomNav } from '../components/ui'

const TABS = [
  { key: 'home', title: 'Trang chủ', href: '/' },
  { key: 'cart', title: 'Giỏ hàng', href: '/cart' },
  { key: 'account', title: 'Tài khoản', href: '/account' },
]

/**
 * Thanh dieu huong nguoi mua cho cac man nam ngoai nhom tab (chi tiet san pham, don hang...): Figma
 * hien thanh nay o moi man, voi tab tuong ung dang chon.
 */
export default function BuyerNav({ active = 'home' }) {
  const insets = useSafeAreaInsets()
  const state = { index: TABS.findIndex((tab) => tab.key === active), routes: TABS }
  const descriptors = Object.fromEntries(TABS.map((tab) => [tab.key, { options: tab }]))
  return (
    <BottomNav
      state={state}
      descriptors={descriptors}
      insets={insets}
      navigateToTab={(key) => router.navigate(TABS.find((tab) => tab.key === key).href)}
    />
  )
}
