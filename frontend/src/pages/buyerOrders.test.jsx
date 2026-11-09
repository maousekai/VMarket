// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import OrdersPage from './OrdersPage'
import OrderDetailPage from './OrderDetailPage'
import { resetCatalogLookup } from '../hooks/useCatalogLookup'
import { getDelivery } from '../services/api/deliveryApi'
import {
  cancelOrder,
  completeOrder,
  createReturn,
  disputeReturn,
  getOrder,
  listMyOrders,
  listOrderReturns,
  uploadReturnEvidence,
} from '../services/api/orderApi'
import { getProduct } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'

vi.mock('../services/api/orderApi', async (importOriginal) => ({
  ...(await importOriginal()),
  cancelOrder: vi.fn(),
  completeOrder: vi.fn(),
  createReturn: vi.fn(),
  deleteReturnEvidence: vi.fn(async () => {}),
  disputeReturn: vi.fn(),
  getOrder: vi.fn(),
  listMyOrders: vi.fn(),
  listOrderReturns: vi.fn(),
  uploadReturnEvidence: vi.fn(),
}))
vi.mock('../services/api/deliveryApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getDelivery: vi.fn(),
}))
vi.mock('../services/api/productApi', () => ({ getProduct: vi.fn() }))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))

const base = {
  recipientName: 'An',
  phone: '0905123456',
  streetAddress: '1 Trần Phú',
  province: 'Đà Nẵng',
  paymentMethod: 'COD',
  totalAmount: 369000,
  createdAt: '2026-11-01T08:00:00Z',
}
const items = [
  {
    id: 'i1',
    productId: 'p1',
    variantId: 'v1',
    shopId: 's1',
    quantity: 1,
    unitPrice: 249000,
    lineTotal: 249000,
  },
  {
    id: 'i2',
    productId: 'p2',
    variantId: 'v2',
    shopId: 's2',
    quantity: 1,
    unitPrice: 120000,
    lineTotal: 120000,
  },
]
const pending = { ...base, id: '01JC4X9Q2W5R8T6V8N0PENDING', status: 'PENDING', items }
const delivered = {
  ...base,
  id: '01JC4X9Q2W5R8T6V8DELIVERED',
  status: 'DELIVERED',
  deliveredAt: new Date(Date.now() - 24 * 3600 * 1000).toISOString(),
  items,
}

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
          <Route path="/profile/orders" element={<OrdersPage />} />
          <Route path="/profile/orders/:orderId" element={<OrderDetailPage />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  resetCatalogLookup()
  getProduct.mockImplementation(async (id) => ({
    product: { id, name: id === 'p1' ? 'Áo polo' : 'Ly gốm', imageUrls: [], variants: [] },
  }))
  getPublicShop.mockImplementation(async (id) => ({
    id,
    name: id === 's1' ? 'Danang Apparel' : 'Gốm Mộc',
  }))
  listMyOrders.mockResolvedValue([pending, delivered])
  listOrderReturns.mockResolvedValue([])
  getDelivery.mockRejectedValue({ response: { status: 404, data: {} } })
})

describe('OrdersPage', () => {
  it('lists orders with status and filters by tab', async () => {
    renderAt('/profile/orders')
    expect(await screen.findByRole('heading', { name: 'Đơn #0PENDING' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Đơn #ELIVERED' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Đã giao/ }))
    expect(screen.queryByRole('heading', { name: 'Đơn #0PENDING' })).not.toBeInTheDocument()
  })

  it('searches by order code', async () => {
    renderAt('/profile/orders')
    await screen.findByRole('heading', { name: 'Đơn #0PENDING' })
    fireEvent.change(screen.getByRole('searchbox', { name: 'Tìm theo mã đơn' }), {
      target: { value: '#elivered' },
    })
    expect(screen.queryByRole('heading', { name: 'Đơn #0PENDING' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Đơn #ELIVERED' })).toBeInTheDocument()
  })
})

describe('OrderDetailPage', () => {
  it('shows the timeline, items per shop and cancels a pending order', async () => {
    getOrder.mockResolvedValueOnce(pending).mockResolvedValue({ ...pending, status: 'CANCELLED' })
    cancelOrder.mockResolvedValue({ ...pending, status: 'CANCELLED' })
    renderAt(`/profile/orders/${pending.id}`)
    expect(await screen.findByRole('list', { name: 'Tiến trình đơn hàng' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Danang Apparel' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Huỷ đơn hàng' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Huỷ đơn' }))
    expect(await screen.findByText('Đã huỷ đơn hàng.')).toBeInTheDocument()
    expect(cancelOrder).toHaveBeenCalledWith(pending.id)
    expect(await screen.findByText('Đơn hàng đã bị huỷ.')).toBeInTheDocument()
  })

  it('confirms receipt of a delivered order', async () => {
    getOrder.mockResolvedValue(delivered)
    completeOrder.mockResolvedValue({ ...delivered, status: 'COMPLETED' })
    renderAt(`/profile/orders/${delivered.id}`)
    fireEvent.click(await screen.findByRole('button', { name: 'Đã nhận được hàng' }))
    await waitFor(() => expect(completeOrder).toHaveBeenCalledWith(delivered.id))
    expect(screen.queryByRole('button', { name: 'Huỷ đơn hàng' })).not.toBeInTheDocument()
  })

  it('requests a return for items of one shop with evidence', async () => {
    getOrder.mockResolvedValue(delivered)
    uploadReturnEvidence.mockResolvedValue({ url: 'https://cdn/returns/e1.jpg' })
    createReturn.mockResolvedValue({ id: 'r1', status: 'PENDING' })
    renderAt(`/profile/orders/${delivered.id}`)
    fireEvent.click(await screen.findByRole('button', { name: 'Yêu cầu trả hàng / hoàn tiền' }))
    const dialog = screen.getByRole('dialog', { name: 'Yêu cầu trả hàng / hoàn tiền' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Chọn ít nhất một sản phẩm')

    const [first, second] = within(dialog).getAllByRole('checkbox')
    fireEvent.click(first)
    fireEvent.click(second)
    // Picking an item of another shop replaces the previous selection.
    expect(first).not.toBeChecked()
    expect(second).toBeChecked()

    fireEvent.click(within(dialog).getByRole('radio', { name: 'Khác' }))
    fireEvent.change(within(dialog).getByLabelText('Mô tả lý do khác'), {
      target: { value: 'Ly bị nứt' },
    })
    fireEvent.change(within(dialog).getByLabelText(/Thêm ảnh/), {
      target: { files: [new File(['x'], 'e.jpg', { type: 'image/jpeg' })] },
    })
    expect(await within(dialog).findByRole('img', { name: 'Ảnh 1' })).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi yêu cầu' }))
    expect(await screen.findByText(/Đã gửi yêu cầu trả hàng/)).toBeInTheDocument()
    expect(createReturn).toHaveBeenCalledWith(delivered.id, {
      reason: 'Ly bị nứt',
      evidenceUrls: ['https://cdn/returns/e1.jpg'],
      items: [{ orderItemId: 'i2', productId: 'p2', variantId: 'v2', quantity: 1 }],
    })
  })

  it('hides the return button once the 7-day window has passed', async () => {
    getOrder.mockResolvedValue({
      ...delivered,
      deliveredAt: new Date(Date.now() - 8 * 24 * 3600 * 1000).toISOString(),
    })
    renderAt(`/profile/orders/${delivered.id}`)
    await screen.findByRole('button', { name: 'Đã nhận được hàng' })
    expect(screen.queryByRole('button', { name: /Yêu cầu trả hàng/ })).not.toBeInTheDocument()
  })

  it('disputes a rejected return', async () => {
    getOrder.mockResolvedValue(delivered)
    listOrderReturns.mockResolvedValue([
      {
        id: 'r1',
        status: 'REJECTED',
        reason: 'Lỗi',
        sellerNote: 'Hàng đã dùng',
        evidenceUrls: [],
        createdAt: '2026-11-02T08:00:00Z',
        items: [{ orderItemId: 'i1' }],
      },
    ])
    disputeReturn.mockResolvedValue({ id: 'r1', status: 'DISPUTED' })
    renderAt(`/profile/orders/${delivered.id}`)
    expect(await screen.findByText('Phản hồi của gian hàng: Hàng đã dùng')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Khiếu nại lên sàn' }))
    const dialog = screen.getByRole('dialog', { name: 'Khiếu nại lên VMarket' })
    fireEvent.change(within(dialog).getByLabelText('Lý do khiếu nại *'), {
      target: { value: 'Hàng còn tem' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gửi khiếu nại' }))
    await waitFor(() => expect(disputeReturn).toHaveBeenCalledWith('r1', 'Hàng còn tem'))
  })

  it('shows the delivery status when a shipment exists', async () => {
    getOrder.mockResolvedValue({ ...pending, status: 'SHIPPED' })
    getDelivery.mockResolvedValue({ assignment: { status: 'IN_TRANSIT' } })
    renderAt(`/profile/orders/${pending.id}`)
    expect(await screen.findByText('Đang giao hàng')).toBeInTheDocument()
  })
})
