import { Stack } from 'expo-router'

// Nguoi mua: Stack boc nhom tab (Trang chu / Gio hang / Tai khoan). Cac man chi tiet nam ngoai tab
// nen mo duoc tu bat ky dau, ke ca deep link vmarket://delivery/<orderId> (review PR 68 lan 4,
// muc 1.1: man theo doi truoc day la tab an nen bi day ve Trang chu).
export default function BuyerLayout() {
  return <Stack screenOptions={{ headerShown: false }} />
}
