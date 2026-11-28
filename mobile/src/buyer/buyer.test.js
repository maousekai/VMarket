import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native'
import { Alert } from 'react-native'
import { router } from 'expo-router'
import {
  addCartItem,
  cancelOrder,
  checkoutOrders,
  confirmVnpayReturn,
  createPayment,
  getCart,
  getOrder,
  getPaymentByOrder,
  getProduct,
  getShop,
  listMyAddresses,
  validateCart,
} from '../api/shopApi'
import { notificationTarget } from '../components/NotificationsScreen'
import { Card } from '../components/ui'
import { darkColors, lightColors, ThemeProvider } from '../theme'
import CartScreen from './CartScreen'
import { resetCatalogLookup } from './catalog'
import CheckoutScreen from './CheckoutScreen'
import { resetCheckoutForTests, setCheckout } from './checkoutStore'
import OrderDetailScreen from './OrderDetailScreen'
import PaymentScreen, { vnpayReturnSearch } from './PaymentScreen'
import ProductScreen from './ProductScreen'

jest.mock('../api/shopApi', () => ({
  ...jest.requireActual('../api/shopApi'),
  addCartItem: jest.fn(),
  cancelOrder: jest.fn(),
  checkoutOrders: jest.fn(),
  confirmVnpayReturn: jest.fn(),
  createPayment: jest.fn(),
  getCart: jest.fn(),
  getOrder: jest.fn(),
  getPaymentByOrder: jest.fn(),
  getProduct: jest.fn(),
  getShop: jest.fn(),
  listMyAddresses: jest.fn(),
  removeCartItem: jest.fn(),
  updateCartItem: jest.fn(),
  validateCart: jest.fn(),
}))
jest.mock('react-native-webview', () => ({ WebView: () => null }))

const product = {
  id: 'p1',
  shopId: 'shop-1',
  name: 'Áo Polo Nam Cotton Pique',
  imageUrls: [],
  minPrice: 249000,
  maxPrice: 249000,
  availableStock: 60,
  ratingAverage: 0,
  ratingCount: 0,
  soldCount: 3,
  description: 'Chất liệu cotton pique.',
  variants: [
    { id: 'v1', attributes: { color: 'Navy', size: 'L' }, price: 249000, availableStock: 60 },
    { id: 'v2', attributes: { color: 'Trắng', size: 'M' }, price: 259000, availableStock: 0 },
  ],
}
const address = {
  id: 'addr-1',
  recipientName: 'Nguyễn Minh Tuấn',
  phone: '0905123456',
  streetAddress: '120 Hoàng Diệu',
  ward: 'Phường Hải Châu',
  province: 'Thành phố Đà Nẵng',
  isDefault: true,
}
const cart = {
  groups: [
    {
      shopId: 'shop-1',
      subtotal: 448000,
      items: [
        {
          productId: 'p1',
          variantId: 'v1',
          shopId: 'shop-1',
          quantity: 1,
          unitPrice: 249000,
          lineTotal: 249000,
        },
        {
          productId: 'p1',
          variantId: 'v2',
          shopId: 'shop-1',
          quantity: 1,
          unitPrice: 199000,
          lineTotal: 199000,
        },
      ],
    },
    {
      shopId: 'shop-2',
      subtotal: 120000,
      items: [
        {
          productId: 'p1',
          variantId: null,
          shopId: 'shop-2',
          quantity: 1,
          unitPrice: 120000,
          lineTotal: 120000,
        },
      ],
    },
  ],
  totalQuantity: 3,
  totalAmount: 568000,
}

beforeEach(() => {
  jest.clearAllMocks()
  resetCatalogLookup()
  resetCheckoutForTests()
  getProduct.mockResolvedValue({ product, similarProducts: [] })
  getShop.mockImplementation(async (id) => ({
    id,
    name: id === 'shop-1' ? 'Danang Local Brand' : 'Gốm Mộc',
  }))
  listMyAddresses.mockResolvedValue([address])
  getCart.mockResolvedValue(cart)
})

describe('product detail', () => {
  it('adds the chosen variant and quantity to the cart', async () => {
    addCartItem.mockResolvedValue({})
    await render(<ProductScreen productId="p1" />)
    expect(await screen.findByText('Áo Polo Nam Cotton Pique')).toBeTruthy()
    expect(await screen.findByText('Danang Local Brand · Đà Nẵng')).toBeTruthy()
    expect(screen.getByText('Còn 60 sản phẩm · Số lượng 1')).toBeTruthy()
    await fireEvent.press(screen.getByText('+'))
    await fireEvent.press(screen.getByText('Thêm vào giỏ'))
    await waitFor(() =>
      expect(addCartItem).toHaveBeenCalledWith({
        productId: 'p1',
        variantId: 'v1',
        shopId: 'shop-1',
        quantity: 2,
      }),
    )
    expect(await screen.findByText('Đã thêm vào giỏ hàng.')).toBeTruthy()
  })

  it('shows "out of stock" and blocks adding a sold-out variant', async () => {
    await render(<ProductScreen productId="p1" />)
    await fireEvent.press(await screen.findByText(/Trắng · M/))
    expect(screen.getByText('Sản phẩm hết hàng')).toBeTruthy()
    await fireEvent.press(screen.getByText('Thêm vào giỏ'))
    expect(addCartItem).not.toHaveBeenCalled()
  })
})

describe('cart', () => {
  it('groups by shop, shows free shipping and the delivery address', async () => {
    await render(<CartScreen />)
    expect(await screen.findByText('Danang Local Brand')).toBeTruthy()
    expect(screen.getAllByText('Phí giao: Miễn phí')).toHaveLength(2)
    expect(screen.getByText('Tổng: 568.000đ')).toBeTruthy()
    expect(screen.getByText('120 Hoàng Diệu, Phường Hải Châu, Thành phố Đà Nẵng')).toBeTruthy()
    expect(screen.queryByText(/15\.000/)).toBeNull()
  })

  it('lists price changes instead of continuing to checkout', async () => {
    validateCart.mockResolvedValue({
      ok: false,
      issues: [{ productId: 'p1', variantId: 'v1', message: 'Giá đã tăng lên 259.000đ' }],
    })
    await render(<CartScreen />)
    await fireEvent.press(await screen.findByText('Đặt hàng · 568.000đ'))
    expect(await screen.findByText('Giá đã thay đổi')).toBeTruthy()
    expect(screen.getByText(/Giá đã tăng lên 259\.000đ/)).toBeTruthy()
    expect(router.push).not.toHaveBeenCalledWith('/checkout')
  })

  it('continues to checkout with the chosen address once the cart is valid', async () => {
    validateCart.mockResolvedValue({ ok: true, issues: [] })
    await render(<CartScreen />)
    await fireEvent.press(await screen.findByText('Thanh toán khi nhận hàng (COD)'))
    await fireEvent.press(screen.getByText('Đặt hàng · 568.000đ'))
    await waitFor(() => expect(router.push).toHaveBeenCalledWith('/checkout'))
  })

  it('shows the empty cart state', async () => {
    getCart.mockResolvedValue({ groups: [], totalQuantity: 0, totalAmount: 0 })
    await render(<CartScreen />)
    expect(await screen.findByText('Giỏ hàng trống')).toBeTruthy()
  })

  it('asks for an address when the buyer has none', async () => {
    listMyAddresses.mockResolvedValue([])
    await render(<CartScreen />)
    expect(await screen.findByText(/Chưa có địa chỉ nhận hàng/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Đặt hàng/ }).props.accessibilityState.disabled).toBe(
      true,
    )
  })
})

describe('checkout', () => {
  it('places one COD order per shop and opens the success screen', async () => {
    setCheckout({ addressId: 'addr-1', paymentMethod: 'COD' })
    checkoutOrders.mockResolvedValue([{ id: 'o1' }, { id: 'o2' }])
    await render(<CheckoutScreen />)
    expect(await screen.findByText('2 đơn riêng')).toBeTruthy()
    await fireEvent.press(screen.getByText('Đặt 2 đơn COD'))
    await waitFor(() => expect(checkoutOrders).toHaveBeenCalled())
    const [body, key] = checkoutOrders.mock.calls[0]
    expect(body).toEqual({
      addressId: 'addr-1',
      paymentMethod: 'COD',
      items: [
        { productId: 'p1', variantId: 'v1' },
        { productId: 'p1', variantId: 'v2' },
        { productId: 'p1', variantId: null },
      ],
    })
    expect(key).toMatch(/^[0-9a-f]{16,32}$/)
    expect(router.replace).toHaveBeenCalledWith({
      pathname: '/order-success',
      params: { ids: 'o1,o2' },
    })
  })

  it('reuses the idempotency key when retrying the same order after a network error', async () => {
    setCheckout({ addressId: 'addr-1', paymentMethod: 'COD' })
    checkoutOrders.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue([{ id: 'o1' }])
    await render(<CheckoutScreen />)
    await fireEvent.press(await screen.findByText('Đặt 2 đơn COD'))
    await waitFor(() => expect(checkoutOrders).toHaveBeenCalledTimes(1))
    await fireEvent.press(screen.getByText('Đặt 2 đơn COD'))
    await waitFor(() => expect(checkoutOrders).toHaveBeenCalledTimes(2))
    expect(checkoutOrders.mock.calls[1][1]).toBe(checkoutOrders.mock.calls[0][1])
  })

  it('sends VNPay orders to payment one by one', async () => {
    setCheckout({ addressId: 'addr-1', paymentMethod: 'VNPAY' })
    checkoutOrders.mockResolvedValue([{ id: 'o1' }, { id: 'o2' }])
    await render(<CheckoutScreen />)
    await fireEvent.press(await screen.findByText('Đặt 2 đơn và thanh toán VNPay'))
    await waitFor(() =>
      expect(router.replace).toHaveBeenCalledWith({
        pathname: '/payment/[orderId]',
        params: { orderId: 'o1', queue: 'o1,o2' },
      }),
    )
  })
})

describe('VNPay payment', () => {
  it('extracts the vnp_* query from the return URL', () => {
    expect(
      vnpayReturnSearch(
        'http://localhost:5173/payment/vnpay-return?vnp_TxnRef=1&vnp_ResponseCode=00',
      ),
    ).toBe('?vnp_TxnRef=1&vnp_ResponseCode=00')
    expect(vnpayReturnSearch('https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?x=1')).toBeNull()
  })

  it('shows the amount and offers to pay the next order after success', async () => {
    createPayment.mockResolvedValue({
      orderId: 'o1',
      amount: 448000,
      method: 'VNPAY',
      status: 'PENDING',
      checkoutUrl: 'https://sandbox.vnpayment.vn/pay',
    })
    getPaymentByOrder.mockResolvedValue({
      orderId: 'o1',
      amount: 448000,
      method: 'VNPAY',
      status: 'SUCCEEDED',
      paidAt: '2026-11-28T10:00:00Z',
    })
    await render(<PaymentScreen orderId="o1" queue={['o1', 'o2']} />)
    expect(await screen.findByText('448.000đ')).toBeTruthy()
    expect(screen.getByText('Thanh toán qua VNPay')).toBeTruthy()
    await fireEvent.press(screen.getByText('Kiểm tra thanh toán'))
    expect(await screen.findByText('Chờ xác nhận')).toBeTruthy()
    await fireEvent.press(screen.getByText('Thanh toán đơn 2'))
    expect(router.replace).toHaveBeenCalledWith({
      pathname: '/payment/[orderId]',
      params: { orderId: 'o2', queue: 'o1,o2' },
    })
    expect(confirmVnpayReturn).not.toHaveBeenCalled()
  })

  it('explains an expired payment', async () => {
    createPayment.mockResolvedValue({
      orderId: 'o1',
      amount: 1,
      method: 'VNPAY',
      status: 'EXPIRED',
    })
    await render(<PaymentScreen orderId="o1" queue={['o1']} />)
    expect(await screen.findByText('Thanh toán đã hết hạn')).toBeTruthy()
  })
})

describe('order detail', () => {
  const order = {
    id: '01JORDERDN8921',
    status: 'PENDING',
    paymentMethod: 'COD',
    totalAmount: 448000,
    recipientName: 'Nguyễn Minh Tuấn',
    phone: '0905123456',
    streetAddress: '120 Hoàng Diệu',
    ward: 'Phường Hải Châu',
    province: 'Thành phố Đà Nẵng',
    items: [{ productId: 'p1', variantId: 'v1', quantity: 1, unitPrice: 249000 }],
  }

  it('shows product names, free shipping and cancels after confirmation', async () => {
    getOrder.mockResolvedValue(order)
    getPaymentByOrder.mockResolvedValue(null)
    cancelOrder.mockResolvedValue({ ...order, status: 'CANCELLED' })
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {})
    await render(<OrderDetailScreen orderId={order.id} />)
    expect(
      await screen.findByText('Áo Polo Nam Cotton Pique / Navy · L · 1 × 249.000đ'),
    ).toBeTruthy()
    expect(screen.getByText(/phí giao miễn phí/)).toBeTruthy()
    await fireEvent.press(screen.getByText('Huỷ đơn hàng'))
    const buttons = alert.mock.calls[0][2]
    await act(async () => {
      await buttons.find((button) => button.text === 'Huỷ đơn').onPress()
    })
    expect(cancelOrder).toHaveBeenCalledWith(order.id)
    expect(await screen.findByText(/Đã huỷ đơn/)).toBeTruthy()
    alert.mockRestore()
  })

  it('explains that a confirmed order can no longer be cancelled', async () => {
    getOrder.mockResolvedValue(order)
    getPaymentByOrder.mockResolvedValue(null)
    cancelOrder.mockRejectedValue({
      response: { status: 409, data: { error: { code: 'INVALID_TRANSITION', message: 'x' } } },
    })
    const alert = jest.spyOn(Alert, 'alert').mockImplementation(() => {})
    await render(<OrderDetailScreen orderId={order.id} />)
    await fireEvent.press(await screen.findByText('Huỷ đơn hàng'))
    await act(async () => {
      await alert.mock.calls[0][2].find((button) => button.text === 'Huỷ đơn').onPress()
    })
    expect(await screen.findByText('Đơn đã được xác nhận')).toBeTruthy()
    alert.mockRestore()
  })

  it('opens delivery tracking for a shipped order', async () => {
    getOrder.mockResolvedValue({ ...order, status: 'SHIPPED' })
    getPaymentByOrder.mockResolvedValue(null)
    await render(<OrderDetailScreen orderId={order.id} />)
    await fireEvent.press(await screen.findByText('Theo dõi giao hàng'))
    expect(router.push).toHaveBeenCalledWith({
      pathname: '/delivery/[orderId]',
      params: { orderId: order.id },
    })
    expect(screen.queryByText('Huỷ đơn hàng')).toBeNull()
  })
})

describe('notifications and theme', () => {
  it('maps order links to the order detail screen', () => {
    expect(notificationTarget('/orders/abc123', 'buyer')).toBe('/orders/abc123')
    expect(notificationTarget('/seller/orders', 'buyer')).toBe('')
    expect(notificationTarget('/orders/abc', 'shipper')).toBe('')
  })

  it('draws cards with the dark palette when the device uses dark mode', async () => {
    await render(
      <ThemeProvider scheme="dark">
        <Card title="Đang giao" />
      </ThemeProvider>,
    )
    const title = screen.getByText('Đang giao')
    expect(title.props.style).toEqual(expect.objectContaining({ color: darkColors.text }))
    expect(darkColors.text).not.toBe(lightColors.text)
  })
})
