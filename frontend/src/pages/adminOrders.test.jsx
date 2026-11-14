// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import AdminOrdersPage from './AdminOrdersPage'
import AdminDisputesPage from './AdminDisputesPage'
import { resetCatalogLookup } from '../hooks/useCatalogLookup'
import {
  adminCancelOrder,
  getAdminOrder,
  listAdminOrders,
  listAdminReturns,
  resolveReturn,
} from '../services/api/orderApi'
import { getProduct } from '../services/api/productApi'

vi.mock('../services/api/orderApi', async (importOriginal) => ({
  ...(await importOriginal()),
  adminCancelOrder: vi.fn(),
  getAdminOrder: vi.fn(),
  listAdminOrders: vi.fn(),
  listAdminReturns: vi.fn(),
  resolveReturn: vi.fn(),
}))
vi.mock('../services/api/productApi', () => ({ getProduct: vi.fn() }))

const page = (content) => ({
  content,
  page: 0,
  size: 10,
  totalElements: content.length,
  totalPages: 1,
})
const order = {
  id: '01JC4X9Q2W5R8T6V8N0P4S6A2B',
  status: 'PROCESSING',
  recipientName: 'Lê Hoàng Long',
  phone: '0978654321',
  streetAddress: '88 Nguyễn Thị Minh Khai',
  province: 'Đà Nẵng',
  paymentMethod: 'COD',
  totalAmount: 320000,
  createdAt: '2026-11-07T05:15:00Z',
  items: [
    {
      productId: 'p1',
      variantId: 'v1',
      shopId: 's1',
      quantity: 1,
      unitPrice: 320000,
      lineTotal: 320000,
    },
  ],
}

const renderPage = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>)

beforeEach(() => {
  vi.clearAllMocks()
  resetCatalogLookup()
  getProduct.mockResolvedValue({
    product: { id: 'p1', name: 'Áo sơ mi Oxford', imageUrls: [], variants: [] },
  })
})

describe('AdminOrdersPage', () => {
  it('lists platform orders and filters by status and buyer', async () => {
    listAdminOrders.mockResolvedValue(page([order]))
    renderPage(<AdminOrdersPage />)
    expect(await screen.findByRole('button', { name: '#0P4S6A2B' })).toBeInTheDocument()
    expect(listAdminOrders).toHaveBeenCalledWith({ status: '', userId: '', page: 0, size: 10 })
    fireEvent.click(screen.getByRole('button', { name: 'Đang giao' }))
    await waitFor(() =>
      expect(listAdminOrders).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: 'SHIPPED' }),
      ),
    )
    fireEvent.change(screen.getByRole('searchbox', { name: 'Lọc theo mã người mua' }), {
      target: { value: 'user-1' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Lọc' }))
    await waitFor(() =>
      expect(listAdminOrders).toHaveBeenLastCalledWith(
        expect.objectContaining({ userId: 'user-1' }),
      ),
    )
  })

  it('opens the order detail and cancels it with a reason', async () => {
    listAdminOrders.mockResolvedValue(page([order]))
    getAdminOrder.mockResolvedValue(order)
    adminCancelOrder.mockResolvedValue({ ...order, status: 'CANCELLED' })
    renderPage(<AdminOrdersPage />)
    fireEvent.click(await screen.findByRole('button', { name: '#0P4S6A2B' }))
    const drawer = await screen.findByRole('dialog', { name: 'Chi tiết đơn #0P4S6A2B' })
    expect(await within(drawer).findByText('Áo sơ mi Oxford')).toBeInTheDocument()
    fireEvent.click(within(drawer).getByRole('button', { name: 'Huỷ đơn hàng' }))
    fireEvent.click(within(drawer).getByRole('button', { name: 'Xác nhận huỷ đơn' }))
    expect(within(drawer).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do')
    fireEvent.change(within(drawer).getByLabelText('Lý do huỷ *'), {
      target: { value: 'Shop báo hết hàng' },
    })
    fireEvent.click(within(drawer).getByRole('button', { name: 'Xác nhận huỷ đơn' }))
    expect(await screen.findByText('Đã huỷ đơn #0P4S6A2B.')).toBeInTheDocument()
    expect(adminCancelOrder).toHaveBeenCalledWith(order.id, 'Shop báo hết hàng')
  })
})

describe('AdminDisputesPage', () => {
  const dispute = {
    id: 'r1',
    orderId: order.id,
    status: 'DISPUTED',
    reason: 'Sai màu',
    sellerNote: 'Đúng mẫu',
    evidenceUrls: [],
    createdAt: '2026-11-01T00:00:00Z',
    items: [{ id: 'ri1', productId: 'p1', quantity: 1, unitPrice: 320000, lineTotal: 320000 }],
  }

  it('shows disputed returns and resolves one with a refund', async () => {
    listAdminReturns.mockResolvedValue(page([dispute]))
    resolveReturn.mockResolvedValue({ ...dispute, status: 'RESOLVED' })
    renderPage(<AdminDisputesPage />)
    expect(await screen.findByText('Phản hồi gian hàng: Đúng mẫu')).toBeInTheDocument()
    expect(listAdminReturns).toHaveBeenCalledWith({ status: 'DISPUTED', page: 0, size: 10 })
    fireEvent.click(screen.getByRole('button', { name: 'Phân xử' }))
    const dialog = screen.getByRole('dialog', { name: 'Phân xử yêu cầu trả hàng' })
    fireEvent.change(within(dialog).getByLabelText('Kết luận *'), {
      target: { value: 'Ảnh cho thấy sai màu' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lưu quyết định' }))
    expect(await screen.findByText('Đã chấp nhận hoàn tiền cho người mua.')).toBeInTheDocument()
    expect(resolveReturn).toHaveBeenCalledWith('r1', {
      approved: true,
      restock: true,
      note: 'Ảnh cho thấy sai màu',
    })
  })

  it('rejects without restocking and only offers resolution when allowed', async () => {
    listAdminReturns.mockResolvedValue(
      page([
        dispute,
        { ...dispute, id: 'r2', status: 'PENDING', createdAt: new Date().toISOString() },
      ]),
    )
    resolveReturn.mockResolvedValue({ ...dispute, status: 'ADMIN_REJECTED' })
    renderPage(<AdminDisputesPage />)
    const cards = await screen.findAllByRole('article')
    expect(within(cards[1]).queryByRole('button', { name: 'Phân xử' })).not.toBeInTheDocument()
    fireEvent.click(within(cards[0]).getByRole('button', { name: 'Phân xử' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('radio', { name: /Từ chối/ }))
    expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Kết luận *'), {
      target: { value: 'Không đủ bằng chứng' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lưu quyết định' }))
    await waitFor(() =>
      expect(resolveReturn).toHaveBeenCalledWith('r1', {
        approved: false,
        restock: false,
        note: 'Không đủ bằng chứng',
      }),
    )
  })
})
