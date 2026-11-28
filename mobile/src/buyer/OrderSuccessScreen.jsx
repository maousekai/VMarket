import { router } from 'expo-router'
import { Body, Button, Card, Heading, Lines, Screen } from '../components/ui'
import BuyerNav from './BuyerNav'
import { shortOrderId } from './catalog'

/** Dat COD thanh cong (frame 3.37). */
export default function OrderSuccessScreen({ ids }) {
  return (
    <Screen title="Đặt hàng thành công" footer={<BuyerNav active="cart" />}>
      <Heading>{ids.length > 1 ? `Đã đặt ${ids.length} đơn COD` : 'Đã đặt đơn COD'}</Heading>
      <Card title="Chờ gian hàng xác nhận">
        <Lines lines={ids.map((id) => `Đơn ${shortOrderId(id)}`)} />
        <Body>Bạn thanh toán tiền mặt khi nhận hàng. Phí vận chuyển: miễn phí.</Body>
      </Card>
      {ids.map((id, index) => (
        <Button
          key={id}
          title={ids.length > 1 ? `Xem đơn ${index + 1}` : 'Xem đơn hàng'}
          variant="secondary"
          onPress={() => router.push({ pathname: '/orders/[id]', params: { id } })}
        />
      ))}
      <Button title="Về trang chủ" onPress={() => router.navigate('/')} />
    </Screen>
  )
}
