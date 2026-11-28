import { Stack } from 'expo-router'
import { ShipperProvider } from '../../shipper/ShipperProvider'

// Phan he Shipper (cung app, vai tro SHIPPER). Stack boc nhom tab de man chi tiet / thong bao
// khong phai la tab; ShipperProvider giu danh sach chuyen va GPS chung cho moi man.
export default function ShipperLayout() {
  return (
    <ShipperProvider>
      <Stack screenOptions={{ headerShown: false }}>
        <Stack.Screen name="(tabs)" />
        <Stack.Screen name="assignment/[id]" />
        <Stack.Screen name="notifications" />
      </Stack>
    </ShipperProvider>
  )
}
