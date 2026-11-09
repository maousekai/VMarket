// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import CheckoutPage from './CheckoutPage'
import OrderResultPage from './OrderResultPage'
import { CartContext } from '../cart/useCart'
import { resetCatalogLookup } from '../hooks/useCatalogLookup'
import { startPayosPayment, waitForReservation } from '../order/payos'
import { validateCart } from '../services/api/cartApi'
import { getOrder, placeOrder } from '../services/api/orderApi'
import { createPayment } from '../services/api/paymentApi'
import { getProduct } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'
import { listMyAddresses } from '../services/api/userApi'

vi.mock('../services/api/cartApi', () => ({ validateCart: vi.fn() }))
vi.mock('../services/api/orderApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getOrder: vi.fn(),
  placeOrder: vi.fn(),
}))
vi.mock('../services/api/paymentApi', () => ({ createPayment: vi.fn() }))
vi.mock('../services/api/productApi', () => ({ getProduct: vi.fn() }))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))
vi.mock('../services/api/userApi', async (importOriginal) => ({
  ...(await importOriginal()),
  listMyAddresses: vi.fn(),
}))

const cart = {
  status: 'ready',
  totalQuantity: 2,
  cart: {
    totalAmount: 498000,
    groups: [
      {
        shopId: 's1',
        subtotal: 498000,
        items: [
          {
            productId: 'p1',
            variantId: 'v1',
            shopId: 's1',
            quantity: 2,
            unitPrice: 249000,
            lineTotal: 498000,
          },
        ],
      },
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

const renderCheckout = (value = cart) =>
  render(
    <CartContext.Provider value={value}>
      <MemoryRouter initialEntries={['/checkout']}>
        <Routes>
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/cart" element={<p>Trang giỏ hàng</p>} />
          <Route path="/checkout/success/:orderId" element={<p>Đặt thành công</p>} />
          <Route path="/profile/orders/:orderId" element={<p>Chi tiết đơn</p>} />
        </Routes>
      </MemoryRouter>
    </CartContext.Provider>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  resetCatalogLookup()
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
  getProduct.mockResolvedValue({
    product: { id: 'p1', name: 'Áo polo', imageUrls: [], variants: [] },
  })
  getPublicShop.mockResolvedValue({ id: 's1', name: 'Danang Apparel' })
  validateCart.mockResolvedValue({ ok: true, issues: [] })
  placeOrder.mockResolvedValue(order)
})

describe('CheckoutPage', () => {
  it('preselects the default address and lists the items by shop', async () => {
    renderCheckout()
    expect(await screen.findByRole('radio', { name: /Bình/ })).toBeChecked()
    expect(await screen.findByText('Danang Apparel')).toBeInTheDocument()
    expect(await screen.findByText('Áo polo')).toBeInTheDocument()
  })

  it('requires accepting the terms before placing the order', async () => {
    renderCheckout()
    await screen.findByRole('radio', { name: /Bình/ })
    fireEvent.click(screen.getByRole('button', { name: 'Đặt hàng' }))
    expect(screen.getByRole('alert')).toHaveTextContent('đồng ý với điều khoản')
    expect(placeOrder).not.toHaveBeenCalled()
  })

  it('places a COD order with the chosen address and note', async () => {
    renderCheckout()
    fireEvent.click(await screen.findByRole('radio', { name: /An ·/ }))
    fireEvent.change(screen.getByLabelText(/Ghi chú/), { target: { value: 'Gọi trước' } })
    fireEvent.click(screen.getByRole('checkbox', { name: /Tôi đồng ý/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Đặt hàng' }))
    expect(await screen.findByText('Đặt thành công')).toBeInTheDocument()
    expect(placeOrder).toHaveBeenCalledWith(
      { addressId: 'a1', note: 'Gọi trước', paymentMethod: 'COD' },
      expect.any(String),
    )
    expect(cart.refresh).toHaveBeenCalled()
  })

  it('sends the buyer back to the cart when prices changed', async () => {
    validateCart.mockResolvedValue({ ok: false, issues: [{ type: 'PRICE_CHANGED' }] })
    renderCheckout()
    await screen.findByRole('radio', { name: /Bình/ })
    fireEvent.click(screen.getByRole('checkbox', { name: /Tôi đồng ý/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Đặt hàng' }))
    expect(await screen.findByText('Trang giỏ hàng')).toBeInTheDocument()
    expect(placeOrder).not.toHaveBeenCalled()
  })

  it('opens the order detail when PayOS cannot start', async () => {
    placeOrder.mockResolvedValue({ ...order, status: 'WAITING_PAYMENT', paymentMethod: 'PAYOS' })
    getOrder.mockResolvedValue({ ...order, status: 'WAITING_PAYMENT', stockReserved: true })
    createPayment.mockRejectedValue({
      response: {
        status: 503,
        data: { error: { code: 'PAYOS_DISABLED', message: 'PayOS đang tắt.' } },
      },
    })
    renderCheckout()
    await screen.findByRole('radio', { name: /Bình/ })
    fireEvent.click(screen.getByRole('radio', { name: /PayOS/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /Tôi đồng ý/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Đặt hàng' }))
    expect(await screen.findByText('Chi tiết đơn')).toBeInTheDocument()
    expect(placeOrder).toHaveBeenCalledWith(
      expect.objectContaining({ paymentMethod: 'PAYOS' }),
      expect.any(String),
    )
  })

  it('links to the address book when there is no address', async () => {
    listMyAddresses.mockResolvedValue([])
    renderCheckout()
    expect(await screen.findByRole('link', { name: 'Thêm địa chỉ' })).toHaveAttribute(
      'href',
      '/profile/addresses',
    )
    expect(screen.getByRole('button', { name: 'Đặt hàng' })).toBeDisabled()
  })
})

describe('PayOS helpers', () => {
  it('waits for the stock reservation before creating the payment link', async () => {
    getOrder
      .mockResolvedValueOnce({ id: 'o1', status: 'WAITING_PAYMENT', stockReserved: false })
      .mockResolvedValueOnce({ id: 'o1', status: 'WAITING_PAYMENT', stockReserved: true })
    const wait = vi.fn(async () => {})
    const result = await waitForReservation('o1', { wait })
    expect(result.stockReserved).toBe(true)
    expect(wait).toHaveBeenCalledTimes(1)
  })

  it('redirects to the PayOS checkout URL and remembers the order', async () => {
    getOrder.mockResolvedValue({ id: 'o1', status: 'WAITING_PAYMENT', stockReserved: true })
    createPayment.mockResolvedValue({ checkoutUrl: 'https://pay.payos.vn/web/abc' })
    const redirect = vi.fn()
    await startPayosPayment('o1', redirect)
    expect(redirect).toHaveBeenCalledWith('https://pay.payos.vn/web/abc')
    expect(sessionStorage.getItem('vmarket.pendingPaymentOrder')).toBe('o1')
  })

  it('refuses to pay for an order cancelled because stock ran out', async () => {
    getOrder.mockResolvedValue({ id: 'o1', status: 'CANCELLED', stockReserved: false })
    await expect(startPayosPayment('o1', vi.fn())).rejects.toThrow('hết hàng')
    expect(createPayment).not.toHaveBeenCalled()
  })
})

describe('OrderResultPage', () => {
  const renderResult = (path, element) =>
    render(
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/checkout/success/:orderId" element={element} />
          <Route path="/payment/:result" element={element} />
        </Routes>
      </MemoryRouter>,
    )

  it('confirms a COD order', async () => {
    getOrder.mockResolvedValue(order)
    renderResult(`/checkout/success/${order.id}`, <OrderResultPage mode="success" />)
    expect(await screen.findByRole('heading', { name: 'Đặt hàng thành công' })).toBeInTheDocument()
    expect(screen.getByText('#0P4S6A2B')).toBeInTheDocument()
  })

  it('reports a paid PayOS order on return', async () => {
    sessionStorage.setItem('vmarket.pendingPaymentOrder', order.id)
    getOrder.mockResolvedValue({ ...order, paymentMethod: 'PAYOS' })
    renderResult('/payment/return', <OrderResultPage mode="return" />)
    expect(
      await screen.findByRole('heading', { name: 'Thanh toán thành công' }),
    ).toBeInTheDocument()
    await waitFor(() => expect(sessionStorage.getItem('vmarket.pendingPaymentOrder')).toBeNull())
  })

  it('explains a cancelled payment', async () => {
    sessionStorage.setItem('vmarket.pendingPaymentOrder', order.id)
    getOrder.mockResolvedValue({ ...order, status: 'WAITING_PAYMENT', paymentMethod: 'PAYOS' })
    renderResult('/payment/cancel', <OrderResultPage mode="cancel" />)
    expect(
      await screen.findByRole('heading', { name: 'Bạn đã huỷ thanh toán' }),
    ).toBeInTheDocument()
  })
})
