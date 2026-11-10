// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import PaymentPage from './PaymentPage'
import PaymentHistoryPage from './PaymentHistoryPage'
import PaymentDetailPage from './PaymentDetailPage'
import AdminPaymentsPage from './AdminPaymentsPage'
import { getOrder, listOrderReturns } from '../services/api/orderApi'
import {
  confirmRefund,
  createPayment,
  getPayment,
  listMyPayments,
  listPaymentRefunds,
  listPendingRefunds,
  listReconciliations,
  syncPayment,
} from '../services/api/paymentApi'
import { formatCountdown } from '../utils/payment'

vi.mock('qrcode', () => ({
  default: { toDataURL: vi.fn(async () => 'data:image/png;base64,QR') },
}))
vi.mock('../services/api/orderApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getOrder: vi.fn(),
  listOrderReturns: vi.fn(),
}))
vi.mock('../services/api/paymentApi', () => ({
  confirmRefund: vi.fn(),
  createPayment: vi.fn(),
  getPayment: vi.fn(),
  listMyPayments: vi.fn(),
  listPaymentRefunds: vi.fn(),
  listPendingRefunds: vi.fn(),
  listReconciliations: vi.fn(),
  syncPayment: vi.fn(),
}))

const ORDER = '01JC4X9Q2W5R8T6V8N0P4S6A2B'
const minutes = (value) => new Date(Date.now() + value * 60_000).toISOString()
const pending = {
  id: 42,
  orderId: ORDER,
  amount: 249000,
  method: 'PAYOS',
  status: 'PENDING',
  checkoutUrl: 'https://pay.payos.vn/web/abc',
  qrCode: '00020101021238570010A000000727',
  qrAvailable: true,
  linkExpiresAt: minutes(14),
  expiresAt: minutes(15),
  createdAt: new Date().toISOString(),
  paidAt: null,
}
const paid = { ...pending, status: 'SUCCEEDED', paidAt: new Date().toISOString() }
const waitingOrder = { id: ORDER, status: 'WAITING_PAYMENT', stockReserved: true, items: [] }

const auth = {
  session: {
    userId: 'u1',
    username: 'an',
    email: 'an@test.vn',
    status: 'ACTIVE',
    roles: ['BUYER'],
  },
  bootstrapState: 'ready',
  userProfile: { fullName: 'An' },
}

const renderAt = (path) =>
  render(
    <AuthContext.Provider value={auth}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/payment/:orderId" element={<PaymentPage />} />
          <Route path="/profile/payments" element={<PaymentHistoryPage />} />
          <Route path="/profile/payments/:paymentId" element={<PaymentDetailPage />} />
          <Route path="/profile/orders/:orderId" element={<p>Chi tiết đơn</p>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  getOrder.mockResolvedValue(waitingOrder)
  listOrderReturns.mockResolvedValue([])
  createPayment.mockResolvedValue(pending)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('formatCountdown', () => {
  it('formats the remaining time and never goes negative', () => {
    expect(formatCountdown(14 * 60_000 + 5_000)).toBe('14:05')
    expect(formatCountdown(-1)).toBe('00:00')
  })
})

describe('PaymentPage', () => {
  it('shows the VietQR code, amount and a countdown', async () => {
    renderAt(`/payment/${ORDER}`)
    expect(await screen.findByRole('img', { name: /Mã VietQR/ })).toHaveAttribute(
      'src',
      'data:image/png;base64,QR',
    )
    expect(screen.getByText('GD42')).toBeInTheDocument()
    expect(screen.getByText(/249\.000/)).toBeInTheDocument()
    expect(screen.getByRole('timer')).toHaveTextContent(/1[34]:\d\d/)
    expect(screen.getByRole('link', { name: 'Mở trang thanh toán PayOS' })).toHaveAttribute(
      'href',
      pending.checkoutUrl,
    )
    expect(createPayment).toHaveBeenCalledWith(ORDER)
  })

  it('polls the payment and offers the next unpaid order once paid', async () => {
    sessionStorage.setItem(
      'vmarket.payosCheckout',
      JSON.stringify({ queue: [ORDER, 'o2'], payments: {} }),
    )
    getOrder.mockImplementation(async (id) =>
      id === ORDER ? waitingOrder : { id: 'o2', status: 'WAITING_PAYMENT', totalAmount: 120000 },
    )
    syncPayment.mockResolvedValue(paid)
    vi.useFakeTimers({ shouldAdvanceTime: true })
    renderAt(`/payment/${ORDER}`)
    await screen.findByRole('img', { name: /Mã VietQR/ })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4100)
    })
    expect(
      await screen.findByRole('heading', { name: 'Thanh toán thành công' }),
    ).toBeInTheDocument()
    expect(syncPayment).toHaveBeenCalledWith(42)
    expect(await screen.findByRole('link', { name: 'Thanh toán đơn tiếp theo' })).toHaveAttribute(
      'href',
      '/payment/o2',
    )
  })

  it('lists every order of the checkout once the last one is paid', async () => {
    sessionStorage.setItem(
      'vmarket.payosCheckout',
      JSON.stringify({ queue: ['o1', ORDER], payments: {} }),
    )
    getOrder.mockImplementation(async (id) =>
      id === ORDER
        ? { ...waitingOrder, totalAmount: 249000 }
        : { id: 'o1', status: 'PENDING', totalAmount: 120000 },
    )
    createPayment.mockResolvedValue(paid)
    renderAt(`/payment/${ORDER}`)
    const list = await screen.findByRole('list', { name: 'Các đơn vừa đặt' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(2)
    expect(within(list).getByText(/120\.000/)).toBeInTheDocument()
    expect(
      screen.getByText('Bạn đã thanh toán xong cả 2 đơn của lần đặt hàng này.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Thanh toán đơn tiếp theo' })).not.toBeInTheDocument()
    expect(sessionStorage.getItem('vmarket.payosCheckout')).toBeNull()
  })

  it('explains an expired code', async () => {
    createPayment.mockResolvedValue({ ...pending, linkExpiresAt: minutes(-1) })
    renderAt(`/payment/${ORDER}`)
    expect(
      await screen.findByRole('heading', { name: 'Mã thanh toán đã hết hạn' }),
    ).toBeInTheDocument()
  })

  it('shows why PayOS could not start and keeps the order reachable', async () => {
    createPayment.mockRejectedValue({
      response: {
        status: 503,
        data: { error: { code: 'PAYOS_DISABLED', message: 'PayOS đang tắt.' } },
      },
    })
    renderAt(`/payment/${ORDER}`)
    expect(await screen.findByRole('alert')).toHaveTextContent('PayOS đang tắt.')
    expect(screen.getByRole('link', { name: 'Xem đơn hàng' })).toHaveAttribute(
      'href',
      `/profile/orders/${ORDER}`,
    )
  })
})

describe('PaymentHistoryPage', () => {
  it('lists payments with status and amount', async () => {
    listMyPayments.mockResolvedValue([
      paid,
      { ...pending, id: 43, method: 'COD', status: 'COD_PENDING' },
    ])
    renderAt('/profile/payments')
    const row = (await screen.findByText(/GD42/)).closest('a')
    expect(row).toHaveAttribute('href', '/profile/payments/42')
    expect(within(row).getByText('Đã thanh toán')).toBeInTheDocument()
    expect(screen.getByText('Thu tiền khi giao')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Xem thêm' })).not.toBeInTheDocument()
  })

  it('shows the empty state', async () => {
    listMyPayments.mockResolvedValue([])
    renderAt('/profile/payments')
    expect(await screen.findByText('Bạn chưa có giao dịch nào.')).toBeInTheDocument()
  })
})

describe('PaymentDetailPage', () => {
  it('shows refunds and links to a return request when the order allows it', async () => {
    getPayment.mockResolvedValue(paid)
    listPaymentRefunds.mockResolvedValue([
      {
        id: 'r1',
        paymentId: 42,
        returnId: 'ret1',
        amount: 249000,
        status: 'COMPLETED',
        requestedAt: '2026-11-09T08:00:00Z',
        completedAt: '2026-11-09T10:00:00Z',
        transferReference: 'FT123',
      },
    ])
    getOrder.mockResolvedValue({ id: ORDER, status: 'CANCELLED', items: [] })
    renderAt('/profile/payments/42')
    expect(await screen.findByRole('heading', { name: 'Giao dịch GD42' })).toBeInTheDocument()
    expect(screen.getByText('Yêu cầu trả hàng được chấp nhận')).toBeInTheDocument()
    expect(screen.getByText(/Mã chuyển khoản FT123/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Yêu cầu trả hàng/ })).not.toBeInTheDocument()
  })

  it('offers a return for a delivered order and resuming an unpaid one', async () => {
    getPayment.mockResolvedValue(paid)
    listPaymentRefunds.mockResolvedValue([])
    getOrder.mockResolvedValue({
      id: ORDER,
      status: 'DELIVERED',
      deliveredAt: new Date().toISOString(),
      items: [{ id: 'i1', productId: 'p1', quantity: 1 }],
    })
    renderAt('/profile/payments/42')
    expect(
      await screen.findByRole('link', { name: 'Yêu cầu trả hàng / hoàn tiền' }),
    ).toHaveAttribute('href', `/profile/orders/${ORDER}`)
  })

  it('lets the buyer resume a pending PayOS payment', async () => {
    getPayment.mockResolvedValue(pending)
    listPaymentRefunds.mockResolvedValue([])
    renderAt('/profile/payments/42')
    expect(await screen.findByRole('link', { name: 'Tiếp tục thanh toán' })).toHaveAttribute(
      'href',
      `/payment/${ORDER}`,
    )
  })
})

describe('AdminPaymentsPage', () => {
  it('confirms a pending refund with the bank reference', async () => {
    listPendingRefunds.mockResolvedValue([
      {
        id: 'r1',
        paymentId: 42,
        returnId: 'CANCELLATION_42',
        amount: 249000,
        requestedAt: '2026-11-09T08:00:00Z',
        status: 'PENDING',
      },
    ])
    confirmRefund.mockResolvedValue({ id: 'r1', status: 'COMPLETED' })
    render(<AdminPaymentsPage />)
    expect(await screen.findByText(/Huỷ đơn đã thanh toán/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Xác nhận đã hoàn' }))
    const dialog = screen.getByRole('dialog', { name: 'Xác nhận đã hoàn tiền' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xác nhận đã hoàn' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Nhập mã giao dịch')
    fireEvent.change(within(dialog).getByLabelText(/Mã giao dịch ngân hàng/), {
      target: { value: 'FT999' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xác nhận đã hoàn' }))
    await waitFor(() => expect(confirmRefund).toHaveBeenCalledWith('r1', 'FT999'))
    expect(await screen.findByText(/Đã ghi nhận hoàn/)).toBeInTheDocument()
  })

  it('lists payments that need reconciliation', async () => {
    listPendingRefunds.mockResolvedValue([])
    listReconciliations.mockResolvedValue([{ ...pending, status: 'RECONCILING' }])
    render(<AdminPaymentsPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Cần đối soát' }))
    expect(await screen.findByText(/GD42/)).toBeInTheDocument()
    expect(screen.getByText(/Đang đối soát/)).toBeInTheDocument()
  })
})
