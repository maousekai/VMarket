// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SellerOrdersPage from './SellerOrdersPage'
import { resetCatalogLookup } from '../hooks/useCatalogLookup'
import { getDelivery, handoffOrder } from '../services/api/deliveryApi'
import {
  acceptReturn,
  confirmReturnReceipt,
  confirmSellerOrder,
  listSellerOrders,
  listSellerReturns,
  rejectReturn,
} from '../services/api/orderApi'
import { getProduct } from '../services/api/productApi'

vi.mock('../services/api/orderApi', async (importOriginal) => ({
  ...(await importOriginal()),
  acceptReturn: vi.fn(),
  confirmReturnReceipt: vi.fn(),
  confirmSellerOrder: vi.fn(),
  listSellerOrders: vi.fn(),
  listSellerReturns: vi.fn(),
  rejectReturn: vi.fn(),
}))
vi.mock('../services/api/deliveryApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getDelivery: vi.fn(),
  handoffOrder: vi.fn(),
}))
vi.mock('../services/api/productApi', () => ({ getProduct: vi.fn() }))

const page = (content) => ({
  content,
  page: 0,
  size: 10,
  totalElements: content.length,
  totalPages: 1,
})
const order = (overrides) => ({
  id: '01JC4X9Q2W5R8T6V8N0P4S6A2B',
  status: 'PENDING',
  shopConfirmed: false,
  recipientName: 'Trần Thị Mai',
  phone: '0914987654',
  streetAddress: '45 Lê Duẩn',
  province: 'Đà Nẵng',
  paymentMethod: 'COD',
  shopTotalAmount: 398000,
  createdAt: '2026-11-07T06:45:00Z',
  items: [
    {
      id: 'i1',
      productId: 'p1',
      variantId: 'v1',
      quantity: 2,
      unitPrice: 199000,
      lineTotal: 398000,
    },
  ],
  ...overrides,
})

const renderAt = (path = '/seller/orders') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/seller/orders" element={<SellerOrdersPage />} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  resetCatalogLookup()
  getProduct.mockResolvedValue({
    product: { id: 'p1', name: 'Quần short kaki', imageUrls: [], variants: [] },
  })
  getDelivery.mockRejectedValue({ response: { status: 404, data: {} } })
})

describe('SellerOrdersPage', () => {
  it('lists pending orders by default and confirms one', async () => {
    listSellerOrders.mockResolvedValue(page([order()]))
    confirmSellerOrder.mockResolvedValue(order({ shopConfirmed: true }))
    renderAt()
    const card = (await screen.findByRole('heading', { name: 'Đơn #0P4S6A2B' })).closest('article')
    expect(within(card).getByText(/Trần Thị Mai/)).toBeInTheDocument()
    expect(await within(card).findByText('Quần short kaki')).toBeInTheDocument()
    expect(listSellerOrders).toHaveBeenCalledWith({ status: 'PENDING', page: 0, size: 10 })
    fireEvent.click(within(card).getByRole('button', { name: 'Xác nhận đơn' }))
    expect(await screen.findByText('Đã xác nhận đơn #0P4S6A2B.')).toBeInTheDocument()
    expect(confirmSellerOrder).toHaveBeenCalledWith(order().id)
  })

  it('hands a confirmed order over to delivery', async () => {
    listSellerOrders.mockResolvedValue(page([order({ status: 'PROCESSING', shopConfirmed: true })]))
    handoffOrder.mockResolvedValue({ status: 'WAITING_ASSIGNMENT' })
    renderAt('/seller/orders?status=PROCESSING')
    fireEvent.click(await screen.findByRole('button', { name: 'Bàn giao vận chuyển' }))
    await waitFor(() => expect(handoffOrder).toHaveBeenCalledWith(order().id))
    expect(await screen.findByText(/Đã bàn giao đơn/)).toBeInTheDocument()
  })

  it('shows delivery progress instead of the handoff button once handed off', async () => {
    listSellerOrders.mockResolvedValue(page([order({ status: 'PROCESSING', shopConfirmed: true })]))
    getDelivery.mockResolvedValue({
      assignment: { status: 'WAITING_ASSIGNMENT' },
      pickups: [{ shop_id: 's1' }],
    })
    renderAt('/seller/orders?status=PROCESSING')
    expect(await screen.findByText('Chờ phân công shipper')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Bàn giao vận chuyển' })).not.toBeInTheDocument()
  })

  it('switches status tabs through the URL', async () => {
    listSellerOrders.mockResolvedValue(page([]))
    renderAt()
    await screen.findByText('Không có đơn hàng ở trạng thái này.')
    fireEvent.click(screen.getByRole('button', { name: 'Đang giao' }))
    await waitFor(() =>
      expect(listSellerOrders).toHaveBeenLastCalledWith({ status: 'SHIPPED', page: 0, size: 10 }),
    )
  })

  it('handles return requests: accept, reject with reason, confirm receipt', async () => {
    const pendingReturn = {
      id: 'r1',
      orderId: order().id,
      status: 'PENDING',
      reason: 'Sai kích cỡ',
      evidenceUrls: ['https://cdn/returns/e.jpg'],
      createdAt: '2026-11-07T06:45:00Z',
      items: [
        {
          id: 'ri1',
          orderItemId: 'i1',
          productId: 'p1',
          quantity: 1,
          unitPrice: 199000,
          lineTotal: 199000,
        },
      ],
    }
    listSellerReturns.mockResolvedValue(
      page([pendingReturn, { ...pendingReturn, id: 'r2', status: 'APPROVED' }]),
    )
    acceptReturn.mockResolvedValue({})
    rejectReturn.mockResolvedValue({})
    confirmReturnReceipt.mockResolvedValue({})
    renderAt('/seller/orders?view=returns&status=')
    const [first, second] = await screen.findAllByRole('article')
    expect(within(first).getByRole('img', { name: 'Ảnh minh chứng' })).toBeInTheDocument()

    fireEvent.click(within(first).getByRole('button', { name: 'Từ chối' }))
    const dialog = screen.getByRole('dialog', { name: 'Từ chối yêu cầu trả hàng' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Từ chối' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do')
    fireEvent.change(within(dialog).getByLabelText('Lý do từ chối *'), {
      target: { value: 'Hàng đã qua sử dụng' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Từ chối' }))
    await waitFor(() => expect(rejectReturn).toHaveBeenCalledWith('r1', 'Hàng đã qua sử dụng'))

    fireEvent.click(within(second).getByRole('button', { name: 'Đã nhận hàng trả' }))
    await waitFor(() => expect(confirmReturnReceipt).toHaveBeenCalledWith('r2'))
    expect(listSellerReturns).toHaveBeenCalledWith({ status: '', page: 0, size: 10 })
  })
})
