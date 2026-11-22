// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SellerReviewsPage from './SellerReviewsPage'
import AdminReviewsPage from './AdminReviewsPage'
import { resetCatalogLookup } from '../hooks/useCatalogLookup'
import { getProduct } from '../services/api/productApi'
import { getMyShop, getPublicShop } from '../services/api/shopApi'
import {
  deleteReviewReply,
  getShopRatingSummary,
  hideReview,
  listSellerReviews,
  replyToReview,
  restoreReview,
  searchReviews,
} from '../services/api/reviewApi'

vi.mock('../services/api/reviewApi', async (importOriginal) => ({
  ...(await importOriginal()),
  deleteReviewReply: vi.fn(),
  getShopRatingSummary: vi.fn(),
  hideReview: vi.fn(),
  listSellerReviews: vi.fn(),
  replyToReview: vi.fn(),
  restoreReview: vi.fn(),
  searchReviews: vi.fn(),
}))
vi.mock('../services/api/productApi', () => ({ getProduct: vi.fn() }))
vi.mock('../services/api/shopApi', () => ({ getMyShop: vi.fn(), getPublicShop: vi.fn() }))

const page = (items, totalElements = items.length) => ({
  items,
  page: 0,
  size: 10,
  totalElements,
  totalPages: 1,
})
const review = (overrides) => ({
  id: 'r1',
  orderId: '01JC4X9Q2W5R8T6V8N0P4S6A2B',
  productId: 'p1',
  shopId: 's1',
  rating: 3,
  comment: 'Vải hơi mỏng',
  imageUrls: [],
  status: 'VISIBLE',
  createdAt: '2026-11-10T03:00:00Z',
  ...overrides,
})

const renderSeller = (path = '/seller/reviews') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/seller/reviews" element={<SellerReviewsPage />} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  resetCatalogLookup()
  getProduct.mockImplementation(async (id) => ({
    product: { id, name: 'Quần short kaki', imageUrls: [], variants: [] },
  }))
  getPublicShop.mockImplementation(async (id) => ({ id, name: 'Danang Apparel' }))
  getMyShop.mockResolvedValue({ id: 's1', status: 'ACTIVE' })
  getShopRatingSummary.mockResolvedValue({
    targetId: 's1',
    average: 4.25,
    count: 4,
    distribution: { 1: 0, 2: 0, 3: 1, 4: 1, 5: 2 },
  })
})

describe('SellerReviewsPage', () => {
  it('shows shop stats and replies publicly to an unreplied review', async () => {
    listSellerReviews.mockImplementation(async ({ replied, size }) =>
      size === 1 && replied === false ? page([], 1) : page([review()]),
    )
    replyToReview.mockImplementation(async (id, content) =>
      review({ reply: { content, createdAt: '2026-11-12T03:00:00Z' } }),
    )
    renderSeller()

    expect(await screen.findByText('4,3 / 5')).toBeInTheDocument()
    const card = (await screen.findByText('Vải hơi mỏng')).closest('article.seller-review-card')
    expect(within(card).getByText('Quần short kaki')).toBeInTheDocument()
    expect(within(card).getByText('Chưa phản hồi')).toBeInTheDocument()
    expect(within(card).getByText(/Đơn #0P4S6A2B/)).toBeInTheDocument()

    fireEvent.click(within(card).getByRole('button', { name: 'Gửi phản hồi công khai' }))
    expect(
      await within(card).findByText('Nội dung phản hồi không được để trống.'),
    ).toBeInTheDocument()

    fireEvent.change(within(card).getByRole('textbox'), {
      target: { value: 'Shop sẽ đổi size cho bạn' },
    })
    fireEvent.click(within(card).getByRole('button', { name: 'Gửi phản hồi công khai' }))
    await waitFor(() =>
      expect(replyToReview).toHaveBeenCalledWith('r1', 'Shop sẽ đổi size cho bạn'),
    )
    expect(await screen.findByText('Đã gửi phản hồi công khai.')).toBeInTheDocument()
    expect(within(card).getByText('Shop sẽ đổi size cho bạn')).toBeInTheDocument()
    expect(within(card).getByText('Đã phản hồi')).toBeInTheDocument()
  })

  it('filters by the unreplied tab and deletes a reply', async () => {
    listSellerReviews.mockResolvedValue(
      page([review({ reply: { content: 'Cảm ơn bạn', createdAt: '2026-11-12T03:00:00Z' } })]),
    )
    deleteReviewReply.mockResolvedValue()
    renderSeller()

    fireEvent.click(await screen.findByRole('button', { name: 'Chưa phản hồi' }))
    await waitFor(() =>
      expect(listSellerReviews).toHaveBeenLastCalledWith({
        rating: undefined,
        replied: false,
        page: 0,
        size: 10,
      }),
    )

    fireEvent.click(await screen.findByRole('button', { name: 'Xoá phản hồi' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xoá phản hồi?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xoá phản hồi' }))
    await waitFor(() => expect(deleteReviewReply).toHaveBeenCalledWith('r1'))
    expect(await screen.findByText('Đã xoá phản hồi.')).toBeInTheDocument()
  })

  it('goes back to the last page that still has reviews', async () => {
    // Trang 2 cua "Chua phan hoi" vua het muc (11 -> 10): con dung 1 trang.
    listSellerReviews.mockImplementation(async ({ page: current, size }) =>
      size === 1
        ? page([], 10)
        : current === 1
          ? { items: [], page: 1, size: 10, totalElements: 10, totalPages: 1 }
          : page([review({ comment: 'Còn chờ phản hồi' })], 10),
    )
    renderSeller('/seller/reviews?tab=unreplied&page=1')

    expect(await screen.findByText('Còn chờ phản hồi')).toBeInTheDocument()
    expect(listSellerReviews).toHaveBeenLastCalledWith({
      rating: undefined,
      replied: false,
      page: 0,
      size: 10,
    })
    expect(screen.queryByText('Tất cả đánh giá đã được phản hồi.')).not.toBeInTheDocument()
  })
})

describe('Reviews of returned orders', () => {
  it('seller sees the returned badge and cannot reply', async () => {
    listSellerReviews.mockResolvedValue(
      page([
        review({
          status: 'RETURNED',
          moderationReason: 'Đơn hàng đã được trả hàng / hoàn tiền',
        }),
      ]),
    )
    renderSeller()
    const card = (await screen.findByText('Vải hơi mỏng')).closest('article.seller-review-card')
    expect(within(card).getByText('Đơn đã trả hàng')).toBeInTheDocument()
    expect(within(card).getByText(/Đơn hàng đã được trả hàng/)).toBeInTheDocument()
    expect(within(card).queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('admin can filter returned reviews but not hide or restore them', async () => {
    searchReviews.mockResolvedValue(
      page([
        review({ status: 'RETURNED', moderationReason: 'Đơn hàng đã được trả hàng / hoàn tiền' }),
      ]),
    )
    render(
      <MemoryRouter>
        <AdminReviewsPage />
      </MemoryRouter>,
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Đơn đã trả hàng' }))
    await waitFor(() =>
      expect(searchReviews).toHaveBeenLastCalledWith({
        status: 'RETURNED',
        productId: '',
        page: 0,
        size: 20,
      }),
    )
    expect(await screen.findByText(/Đơn hàng đã được trả hàng/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ẩn' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Khôi phục' })).not.toBeInTheDocument()
  })
})

describe('AdminReviewsPage', () => {
  const renderAdmin = () =>
    render(
      <MemoryRouter>
        <AdminReviewsPage />
      </MemoryRouter>,
    )

  it('hides a review with a required reason', async () => {
    searchReviews.mockResolvedValue(page([review({ comment: 'Lừa đảo, đừng mua' })]))
    hideReview.mockResolvedValue(review({ status: 'HIDDEN' }))
    renderAdmin()

    expect(await screen.findByText('Lừa đảo, đừng mua')).toBeInTheDocument()
    expect(searchReviews).toHaveBeenCalledWith({
      status: 'VISIBLE',
      productId: '',
      page: 0,
      size: 20,
    })
    fireEvent.click(screen.getByRole('button', { name: 'Ẩn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Ẩn đánh giá vi phạm' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Ẩn đánh giá' }))
    expect(await within(dialog).findByText('Vui lòng nêu lý do ẩn đánh giá.')).toBeInTheDocument()
    expect(hideReview).not.toHaveBeenCalled()

    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'Ngôn từ xúc phạm' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Ẩn đánh giá' }))
    await waitFor(() => expect(hideReview).toHaveBeenCalledWith('r1', 'Ngôn từ xúc phạm'))
    expect(await screen.findByText('Đã ẩn đánh giá.')).toBeInTheDocument()
  })

  it('restores a hidden review from the hidden tab', async () => {
    searchReviews.mockResolvedValue(
      page([review({ status: 'HIDDEN', moderationReason: 'Spam link ngoài' })]),
    )
    restoreReview.mockResolvedValue(review())
    renderAdmin()

    fireEvent.click(await screen.findByRole('button', { name: 'Đã ẩn' }))
    await waitFor(() =>
      expect(searchReviews).toHaveBeenLastCalledWith({
        status: 'HIDDEN',
        productId: '',
        page: 0,
        size: 20,
      }),
    )
    expect(await screen.findByText('Spam link ngoài')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Khôi phục' }))
    const dialog = await screen.findByRole('dialog', { name: 'Khôi phục đánh giá' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Khôi phục' }))
    await waitFor(() => expect(restoreReview).toHaveBeenCalledWith('r1'))
  })

  it('moves back a page after hiding the last review of the last page', async () => {
    let hidden = false
    searchReviews.mockImplementation(async ({ page: current }) => {
      if (current === 0)
        return { ...page([review({ id: 'r0', comment: 'Trang đầu' })]), totalPages: hidden ? 1 : 2 }
      return hidden
        ? { items: [], page: 1, size: 20, totalElements: 20, totalPages: 1 }
        : {
            items: [review({ comment: 'Mục cuối' })],
            page: 1,
            size: 20,
            totalElements: 21,
            totalPages: 2,
          }
    })
    hideReview.mockImplementation(async () => {
      hidden = true
      return review({ status: 'HIDDEN' })
    })
    renderAdmin()

    fireEvent.click(await screen.findByRole('button', { name: 'Trang 2' }))
    expect(await screen.findByText('Mục cuối')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Ẩn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Ẩn đánh giá vi phạm' })
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'Spam' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Ẩn đánh giá' }))

    expect(await screen.findByText('Trang đầu')).toBeInTheDocument()
    expect(searchReviews).toHaveBeenLastCalledWith({
      status: 'VISIBLE',
      productId: '',
      page: 0,
      size: 20,
    })
  })
})
