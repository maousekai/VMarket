// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import CheckoutPage from './CheckoutPage'
import OrderResultPage from './OrderResultPage'
import VnpayReturnPage from './VnpayReturnPage'
import { CartContext } from '../cart/useCart'
import { resetCatalogLookup } from '../hooks/useCatalogLookup'
import {
  findNextUnpaid,
  prepareVnpayPayment,
  readVnpayCheckout,
  startVnpayQueue,
  waitForReservation,
} from '../order/vnpay'
import { validateCart } from '../services/api/cartApi'
import { checkoutOrders, getOrder } from '../services/api/orderApi'
import { confirmVnpayReturn, createPayment } from '../services/api/paymentApi'
import { getProduct } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'
import { listMyAddresses } from '../services/api/userApi'

vi.mock('../services/api/cartApi', () => ({ validateCart: vi.fn() }))
vi.mock('../services/api/orderApi', async (importOriginal) => ({
  ...(await importOriginal()),
  checkoutOrders: vi.fn(),
  getOrder: vi.fn(),
}))
vi.mock('../services/api/paymentApi', () => ({
  createPayment: vi.fn(),
  confirmVnpayReturn: vi.fn(),
}))
vi.mock('../services/api/productApi', () => ({ getProduct: vi.fn() }))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))
vi.mock('../services/api/userApi', async (importOriginal) => ({
  ...(await importOriginal()),
  listMyAddresses: vi.fn(),
}))

const item = (productId, variantId, shopId, quantity, unitPrice) => ({
  productId,
  variantId,
  shopId,
  quantity,
  unitPrice,
  lineTotal: quantity * unitPrice,
})

const cart = {
  status: 'ready',
  totalQuantity: 3,
  cart: {
    totalAmount: 618000,
    groups: [
      { shopId: 's1', subtotal: 498000, items: [item('p1', 'v1', 's1', 2, 249000)] },
      { shopId: 's2', subtotal: 120000, items: [item('p2', 'v2', 's2', 1, 120000)] },
    ],
  },
  flush: vi.fn(async () => {}),
  refresh: vi.fn(async () => null),
}

const order = {
  id: '01JC4X9Q2W5R8T6V8N0P4S6A2B',
  status: 'PENDING',
  paymentMethod: 'COD',
  totalAmount: 498000,
  recipientName: 'An',
  phone: '0905123456',
  streetAddress: '1 Trần Phú',
  province: 'Đà Nẵng',
  items: [],
}

const selectLines = (...keys) =>
  sessionStorage.setItem('vmarket.checkoutLines', JSON.stringify(keys))

const renderCheckout = (value = cart) =>
  render(
    <CartContext.Provider value={value}>
      <MemoryRouter initialEntries={['/checkout']}>
        <Routes>
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/cart" element={<p>Trang giỏ hàng</p>} />
          <Route path="/checkout/success" element={<p>Đặt thành công</p>} />
          <Route path="/profile/orders/:orderId" element={<p>Chi tiết đơn</p>} />
          <Route path="/payment/:orderId" element={<p>Màn thanh toán VNPay</p>} />
        </Routes>
      </MemoryRouter>
    </CartContext.Provider>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  resetCatalogLookup()
  sessionStorage.clear()
  selectLines('p1::v1')
  listMyAddresses.mockResolvedValue([
    {
      id: 'a1',
      recipientName: 'An',
      phone: '0905123456',
      province: 'Đà Nẵng',
      streetAddress: '1 Trần Phú',
    },
    {
      id: 'a2',
      recipientName: 'Bình',
      phone: '0905000000',
      province: 'Đà Nẵng',
      streetAddress: '2 Lê Lợi',
      isDefault: true,
    },
  ])
  getProduct.mockImplementation(async (id) => ({
    product: { id, name: id === 'p1' ? 'Áo polo' : 'Ly gốm', imageUrls: [], variants: [] },
  }))
  getPublicShop.mockImplementation(async (id) => ({
    id,
    name: id === 's1' ? 'Danang Apparel' : 'Gốm Mộc',
  }))
  validateCart.mockResolvedValue({ ok: true, issues: [] })
  checkoutOrders.mockResolvedValue([order])
})

describe('CheckoutPage', () => {
  it('preselects the default address and lists only the selected items', async () => {
    renderCheckout()
    expect(await screen.findByRole('radio', { name: /Bình/ })).toBeChecked()
    expect(await screen.findByText('Danang Apparel')).toBeInTheDocument()
    expect(await screen.findByText('Áo polo')).toBeInTheDocument()
    expect(screen.queryByText('Ly gốm')).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  it('places a COD order for the selected lines with the chosen address and note', async () => {
    renderCheckout()
    fireEvent.click(await screen.findByRole('radio', { name: /An ·/ }))
    fireEvent.change(screen.getByLabelText(/Ghi chú/), { target: { value: 'Gọi trước' } })
    fireEvent.click(screen.getByRole('button', { name: 'Đặt hàng' }))
    expect(await screen.findByText('Đặt thành công')).toBeInTheDocument()
    expect(checkoutOrders).toHaveBeenCalledWith(
      {
        addressId: 'a1',
        note: 'Gọi trước',
        paymentMethod: 'COD',
        items: [{ productId: 'p1', variantId: 'v1' }],
      },
      expect.any(String),
    )
    expect(cart.refresh).toHaveBeenCalled()
    expect(sessionStorage.getItem('vmarket.checkoutLines')).toBeNull()
  })

  it('announces one order per shop when several shops are selected', async () => {
    selectLines('p1::v1', 'p2::v2')
    renderCheckout()
    await screen.findByRole('radio', { name: /Bình/ })
    expect(screen.getByText(/tách thành 2 đơn/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Đặt 2 đơn hàng' })).toBeEnabled()
  })

  it('ignores cart issues of lines that were not selected', async () => {
    validateCart.mockResolvedValue({
      ok: false,
      issues: [{ productId: 'p2', variantId: 'v2', type: 'OUT_OF_STOCK' }],
    })
    renderCheckout()
    await screen.findByRole('radio', { name: /Bình/ })
    fireEvent.click(screen.getByRole('button', { name: 'Đặt hàng' }))
    expect(await screen.findByText('Đặt thành công')).toBeInTheDocument()
  })

  it('sends the buyer back to the cart when a selected price changed', async () => {
    validateCart.mockResolvedValue({
      ok: false,
      issues: [{ productId: 'p1', variantId: 'v1', type: 'PRICE_CHANGED' }],
    })
    renderCheckout()
    await screen.findByRole('radio', { name: /Bình/ })
    fireEvent.click(screen.getByRole('button', { name: 'Đặt hàng' }))
    expect(await screen.findByText('Trang giỏ hàng')).toBeInTheDocument()
    expect(checkoutOrders).not.toHaveBeenCalled()
  })

  it('opens the VNPay payment screen of the first order for VNPay', async () => {
    selectLines('p1::v1', 'p2::v2')
    checkoutOrders.mockResolvedValue([
      { ...order, id: 'o1', status: 'WAITING_PAYMENT', paymentMethod: 'VNPAY' },
      { ...order, id: 'o2', status: 'WAITING_PAYMENT', paymentMethod: 'VNPAY' },
    ])
    renderCheckout()
    await screen.findByRole('radio', { name: /Bình/ })
    fireEvent.click(screen.getByRole('radio', { name: /VNPay/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Đặt 2 đơn hàng' }))
    expect(await screen.findByText('Màn thanh toán VNPay')).toBeInTheDocument()
    expect(readVnpayCheckout().queue).toEqual(['o1', 'o2'])
    expect(checkoutOrders).toHaveBeenCalledWith(
      expect.objectContaining({ paymentMethod: 'VNPAY' }),
      expect.any(String),
    )
  })

  it('adds an address in a popup when the address book is empty', async () => {
    listMyAddresses.mockResolvedValue([])
    renderCheckout()
    fireEvent.click(await screen.findByRole('button', { name: 'Thêm địa chỉ' }))
    expect(screen.getByRole('dialog', { name: 'Thêm địa chỉ nhận hàng' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Đặt hàng' })).toBeDisabled()
  })

  it('asks the buyer to pick items when nothing was selected', async () => {
    sessionStorage.removeItem('vmarket.checkoutLines')
    renderCheckout()
    expect(
      await screen.findByRole('heading', { name: 'Chưa chọn sản phẩm để thanh toán' }),
    ).toBeInTheDocument()
  })
})

describe('VNPay helpers', () => {
  it('waits for the stock reservation before creating the payment link', async () => {
    getOrder
      .mockResolvedValueOnce({ id: 'o1', status: 'WAITING_PAYMENT', stockReserved: false })
      .mockResolvedValueOnce({ id: 'o1', status: 'WAITING_PAYMENT', stockReserved: true })
    const wait = vi.fn(async () => {})
    const result = await waitForReservation('o1', { wait })
    expect(result.stockReserved).toBe(true)
    expect(wait).toHaveBeenCalledTimes(1)
  })

  it('creates the payment once stock is reserved and remembers it', async () => {
    getOrder.mockResolvedValue({ id: 'o1', status: 'WAITING_PAYMENT', stockReserved: true })
    createPayment.mockResolvedValue({ id: 7, checkoutUrl: 'https://pay' })
    sessionStorage.setItem(
      'vmarket.vnpayCheckout',
      JSON.stringify({ queue: ['o1', 'o2'], payments: {} }),
    )
    const payment = await prepareVnpayPayment('o1')
    expect(payment.id).toBe(7)
    expect(readVnpayCheckout()).toEqual({ queue: ['o1', 'o2'], payments: { o1: 7 } })
    expect(sessionStorage.getItem('vmarket.pendingPaymentOrder')).toBe('o1')
  })

  it('refuses to pay for an order cancelled because stock ran out', async () => {
    getOrder.mockResolvedValue({ id: 'o1', status: 'CANCELLED', stockReserved: false })
    await expect(prepareVnpayPayment('o1')).rejects.toThrow('hết hàng')
    expect(createPayment).not.toHaveBeenCalled()
  })

  it('starts a queue on the first order and finds the next unpaid one', async () => {
    const navigate = vi.fn()
    startVnpayQueue(['o1', 'o2', 'o3'], navigate)
    expect(navigate).toHaveBeenCalledWith('/payment/o1', {
      replace: true,
      state: { autoRedirect: true },
    })
    getOrder.mockImplementation(async (id) => ({
      id,
      status: id === 'o2' ? 'CANCELLED' : 'WAITING_PAYMENT',
    }))
    expect((await findNextUnpaid('o1')).id).toBe('o3')
  })
})

describe('OrderResultPage', () => {
  const renderVnpayReturn = (search) =>
    render(
      <MemoryRouter initialEntries={[`/payment/vnpay-return${search}`]}>
        <Routes>
          <Route path="/payment/vnpay-return" element={<VnpayReturnPage />} />
          <Route path="/payment/:orderId" element={<p>Màn thanh toán VNPay</p>} />
        </Routes>
      </MemoryRouter>,
    )

  const renderResult = (path, element) =>
    render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/checkout/success" element={element} />
          <Route path="/checkout/success/:orderId" element={element} />
        </Routes>
      </MemoryRouter>,
    )

  it('confirms a COD order', async () => {
    getOrder.mockResolvedValue(order)
    renderResult(`/checkout/success/${order.id}`, <OrderResultPage />)
    expect(await screen.findByRole('heading', { name: 'Đặt hàng thành công' })).toBeInTheDocument()
    expect(screen.getByText('Đơn #0P4S6A2B')).toBeInTheDocument()
  })

  it('lists every order created by a split checkout', async () => {
    getOrder.mockImplementation(async (id) => ({ ...order, id }))
    renderResult('/checkout/success?orders=o-AAAAAAAA,o-BBBBBBBB', <OrderResultPage />)
    expect(await screen.findByText(/Đã tạo 2 đơn theo từng gian hàng/)).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Xem đơn' })).toHaveLength(2)
  })

  it('confirms a VNPay return and reports a paid order', async () => {
    sessionStorage.setItem(
      'vmarket.vnpayCheckout',
      JSON.stringify({ queue: [order.id], payments: { [order.id]: 7 } }),
    )
    confirmVnpayReturn.mockResolvedValue({
      id: 7,
      orderId: order.id,
      amount: 249000,
      status: 'SUCCEEDED',
      paidAt: '2026-11-10T08:00:00Z',
    })
    renderVnpayReturn('?vnp_ResponseCode=00&vnp_SecureHash=abc')
    expect(
      await screen.findByRole('heading', { name: 'Thanh toán thành công' }),
    ).toBeInTheDocument()
    expect(confirmVnpayReturn).toHaveBeenCalledWith('?vnp_ResponseCode=00&vnp_SecureHash=abc')
    await waitFor(() => expect(sessionStorage.getItem('vmarket.vnpayCheckout')).toBeNull())
  })

  it('offers to pay the next unpaid order of the same checkout', async () => {
    sessionStorage.setItem(
      'vmarket.vnpayCheckout',
      JSON.stringify({ queue: ['o1', 'o2'], payments: { o1: 7 } }),
    )
    confirmVnpayReturn.mockResolvedValue({
      id: 7,
      orderId: 'o1',
      amount: 249000,
      status: 'SUCCEEDED',
      paidAt: '2026-11-10T08:00:00Z',
    })
    getOrder.mockImplementation(async (id) => ({
      ...order,
      id,
      paymentMethod: 'VNPAY',
      status: id === 'o1' ? 'PENDING' : 'WAITING_PAYMENT',
    }))
    renderVnpayReturn('?vnp_ResponseCode=00')
    expect(await screen.findByRole('link', { name: 'Thanh toán đơn tiếp theo' })).toHaveAttribute(
      'href',
      '/payment/o2',
    )
  })

  it('explains a payment the buyer cancelled on VNPay (code 24)', async () => {
    confirmVnpayReturn.mockResolvedValue({
      id: 7,
      orderId: order.id,
      amount: 249000,
      status: 'PENDING',
      paidAt: null,
    })
    getOrder.mockResolvedValue({ ...order, status: 'WAITING_PAYMENT', paymentMethod: 'VNPAY' })
    renderVnpayReturn('?vnp_ResponseCode=24')
    expect(
      await screen.findByRole('heading', { name: 'Bạn đã huỷ thanh toán' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem chi tiết đơn' })).toBeInTheDocument()
  })

  it('shows a failure for other VNPay response codes', async () => {
    confirmVnpayReturn.mockResolvedValue({
      id: 7,
      orderId: order.id,
      amount: 249000,
      status: 'PENDING',
      paidAt: null,
    })
    renderVnpayReturn('?vnp_ResponseCode=51')
    expect(
      await screen.findByRole('heading', { name: 'Thanh toán không thành công' }),
    ).toBeInTheDocument()
  })

  it('reports an invalid signature', async () => {
    confirmVnpayReturn.mockRejectedValue({
      response: {
        status: 400,
        data: { error: { code: 'INVALID_SIGNATURE', message: 'Chữ ký VNPay không hợp lệ' } },
      },
    })
    renderVnpayReturn('?vnp_ResponseCode=00&vnp_SecureHash=bad')
    expect(await screen.findByRole('alert')).toHaveTextContent('Chữ ký VNPay không hợp lệ')
  })
})
