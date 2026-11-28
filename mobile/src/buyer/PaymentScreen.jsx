import { useCallback, useEffect, useState } from 'react'
import { View } from 'react-native'
import { WebView } from 'react-native-webview'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { confirmVnpayReturn, createPayment, getPaymentByOrder } from '../api/shopApi'
import { Body, Button, Card, Label, Loading, Notice, Price, Screen } from '../components/ui'
import { makeStyles } from '../theme'
import BuyerNav from './BuyerNav'
import { formatPrice, PAYMENT_STATUS_LABELS, shortOrderId } from './catalog'

const PAID = ['SUCCEEDED', 'AWAITING_WEBHOOK', 'RECONCILING']
const AWAITING = ['CREATED', 'CREATING', 'PENDING']
/** VNPay chuyen ve trang web /payment/vnpay-return: app bat URL nay trong WebView. */
const RETURN_PATH = /\/payment\/vnpay-return(\?[^#]*)?/

export const vnpayReturnSearch = (url) => RETURN_PATH.exec(url || '')?.[1] || null

function useCountdown(deadline) {
  const [now, setNow] = useState(Date.now)
  const end = deadline ? Date.parse(deadline) : NaN
  const active = Number.isFinite(end) && end > now
  useEffect(() => {
    if (!active) return undefined
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [active])
  if (!Number.isFinite(end)) return ''
  const seconds = Math.max(0, Math.floor((end - now) / 1000))
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

/**
 * Thanh toan VNPay tung don (frame 3.7, 3.30-3.35; Figma ghi PayOS, backend dung VNPay). Mo trang
 * VNPay trong WebView, bat URL tra ve va gui cho payment-service xac thuc chu ky.
 */
export default function PaymentScreen({ orderId, queue }) {
  const styles = useStyles()
  const [state, setState] = useState({ status: 'loading', payment: null, error: '' })
  const [paying, setPaying] = useState(false)
  const [checking, setChecking] = useState(false)
  const position = Math.max(0, queue.indexOf(orderId))
  const next = queue[position + 1]
  const countdown = useCountdown(state.payment?.linkExpiresAt || state.payment?.expiresAt)

  const prepare = useCallback(async () => {
    setState({ status: 'loading', payment: null, error: '' })
    try {
      setState({ status: 'ready', payment: await createPayment(orderId), error: '' })
    } catch (failure) {
      setState({ status: 'error', payment: null, error: getApiError(failure).message })
    }
  }, [orderId])

  useEffect(() => {
    prepare()
  }, [prepare])

  async function check() {
    setChecking(true)
    try {
      const payment = await getPaymentByOrder(orderId)
      setState({ status: 'ready', payment, error: '' })
    } catch (failure) {
      setState((previous) => ({
        ...previous,
        status: 'unverified',
        error: getApiError(failure).message,
      }))
    } finally {
      setChecking(false)
    }
  }

  async function returned(search) {
    setPaying(false)
    setChecking(true)
    try {
      const payment = await confirmVnpayReturn(search)
      setState({ status: 'ready', payment, error: '' })
    } catch (failure) {
      setState((previous) => ({
        ...previous,
        status: 'unverified',
        error: getApiError(failure).message,
      }))
    } finally {
      setChecking(false)
    }
  }

  const payment = state.payment
  const title = queue.length > 1 ? `Thanh toán · Đơn ${position + 1}/${queue.length}` : 'Thanh toán'

  if (paying && payment?.checkoutUrl)
    return (
      <View style={styles.flex}>
        <Screen title="Thanh toán VNPay" scroll={false}>
          <WebView
            source={{ uri: payment.checkoutUrl }}
            style={styles.flex}
            startInLoadingState
            onShouldStartLoadWithRequest={(request) => {
              const search = vnpayReturnSearch(request.url)
              if (search === null) return true
              returned(search)
              return false
            }}
          />
        </Screen>
        <View style={styles.cancel}>
          <Button title="Huỷ, quay lại" variant="secondary" onPress={() => setPaying(false)} />
        </View>
      </View>
    )

  const paid = PAID.includes(payment?.status) || Boolean(payment?.paidAt)
  const awaiting = payment && !paid && AWAITING.includes(payment.status)

  return (
    <Screen title={paid ? 'Thanh toán thành công' : title} footer={<BuyerNav active="cart" />}>
      <Label>
        {shortOrderId(orderId)}
        {payment ? ` · ${formatPrice(payment.amount)}` : ''}
      </Label>
      {state.status === 'loading' || checking ? (
        <Loading label="Đang kiểm tra thanh toán…" />
      ) : null}
      {state.status === 'error' ? (
        <>
          <Notice tone="danger">{state.error}</Notice>
          <Button title="Thử lại" onPress={prepare} />
        </>
      ) : null}
      {state.status === 'unverified' ? (
        <Card title="Chưa kiểm tra được">
          <Body>
            {`Chưa xác nhận được kết quả thanh toán (${state.error}). Nếu đã trả tiền, đừng thanh toán lại; bấm kiểm tra lại sau ít phút.`}
          </Body>
          <Button title="Kiểm tra lại" onPress={check} />
        </Card>
      ) : null}
      {awaiting && state.status === 'ready' ? (
        <>
          <Price large>{formatPrice(payment.amount)}</Price>
          {countdown ? <Body>{`Thời hạn thanh toán: ${countdown}`}</Body> : null}
          <Card title={PAYMENT_STATUS_LABELS[payment.status] || 'Chờ thanh toán'}>
            <Body>Chờ thanh toán · chỉ cập nhật sau khi giao dịch được xác nhận.</Body>
          </Card>
          <Button
            title="Thanh toán qua VNPay"
            disabled={!payment.checkoutUrl}
            onPress={() => setPaying(true)}
          />
          <Button title="Kiểm tra thanh toán" variant="secondary" onPress={check} />
        </>
      ) : null}
      {paid && state.status === 'ready' ? (
        <Card title="Chờ xác nhận">
          <Body>Giao dịch VNPay đã được xác nhận. Đơn đang chờ gian hàng xác nhận.</Body>
        </Card>
      ) : null}
      {payment?.status === 'EXPIRED' ? (
        <Card title="Thanh toán đã hết hạn">
          <Body>Đơn đã hết thời hạn thanh toán và được huỷ; hàng giữ cho đơn đã được trả lại.</Body>
        </Card>
      ) : null}
      {payment?.status === 'FAILED' ? (
        <Card title="Thanh toán thất bại">
          <Body>VNPay báo giao dịch chưa thành công. Bạn có thể thử thanh toán lại.</Body>
          <Button title="Thử thanh toán lại" onPress={prepare} />
        </Card>
      ) : null}
      {next && (paid || payment?.status === 'EXPIRED') ? (
        <>
          <Card title={`Đơn ${position + 2} chưa thanh toán`}>
            <Body>{shortOrderId(next)}</Body>
          </Card>
          <Button
            title={`Thanh toán đơn ${position + 2}`}
            onPress={() =>
              router.replace({
                pathname: '/payment/[orderId]',
                params: { orderId: next, queue: queue.join(',') },
              })
            }
          />
        </>
      ) : null}
      {next && awaiting ? (
        <Button
          title={`Đơn ${position + 2} · thanh toán sau`}
          variant="secondary"
          onPress={() =>
            router.replace({
              pathname: '/payment/[orderId]',
              params: { orderId: next, queue: queue.join(',') },
            })
          }
        />
      ) : null}
      <Button
        title="Xem đơn hàng"
        variant="secondary"
        onPress={() => router.push({ pathname: '/orders/[id]', params: { id: orderId } })}
      />
    </Screen>
  )
}

const useStyles = makeStyles((colors) => ({
  flex: { flex: 1, backgroundColor: colors.background },
  cancel: { padding: 16, backgroundColor: colors.surface },
}))
