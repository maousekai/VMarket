// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import ImageSearchDialog from './ImageSearchDialog'
import { listCategories } from '../services/api/productApi'
import { searchByImage } from '../services/api/searchApi'

vi.mock('../services/api/productApi', () => ({
  PRODUCT_SORT: { bestSelling: 'BEST_SELLING' },
  listCategories: vi.fn(),
}))
vi.mock('../services/api/searchApi', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, searchByImage: vi.fn() }
})

function Where() {
  const location = useLocation()
  return <p data-testid="where">{location.pathname + location.search}</p>
}

const onClose = vi.fn()

function renderDialog() {
  return render(
    <MemoryRouter>
      <ImageSearchDialog onClose={onClose} />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
}

function image(name = 'polo-navy.jpg', size = 2_400_000, type = 'image/jpeg') {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

const choose = (file) =>
  fireEvent.change(screen.getByTestId('image-search-file'), { target: { files: [file] } })

const apiError = (status, code) =>
  Object.assign(new Error(code), { response: { status, data: { error: { code } } } })

const result = {
  id: 'p1',
  name: 'Áo Polo Nam Cotton Pique',
  minPrice: 249000,
  maxPrice: 249000,
  currency: 'VND',
  imageUrls: ['https://img/p1.jpg'],
  matchedImageUrl: 'https://img/p1-match.jpg',
  similarity: 0.87,
}

beforeEach(() => {
  vi.clearAllMocks()
  listCategories.mockResolvedValue([{ id: 'c1', name: 'Thời trang', active: true, parentId: null }])
  searchByImage.mockResolvedValue([result])
})

describe('ImageSearchDialog', () => {
  it('starts on the upload screen with the 10 MB limit', () => {
    renderDialog()
    expect(screen.getByRole('dialog', { name: 'Tìm bằng ảnh' })).toBeInTheDocument()
    expect(screen.getByText(/dung lượng tối đa 10 MB/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Đóng' }))
    expect(onClose).toHaveBeenCalled()
  })

  it('rejects an image over 10 MB before uploading', () => {
    renderDialog()
    choose(image('big.jpg', 12_000_000))
    expect(screen.getByRole('dialog', { name: 'Ảnh vượt quá 10 MB' })).toBeInTheDocument()
    expect(screen.getByText(/dung lượng 12 MB/)).toBeInTheDocument()
    expect(searchByImage).not.toHaveBeenCalled()
  })

  it('rejects files that are not JPEG, PNG or WebP', () => {
    renderDialog()
    choose(image('anim.gif', 1000, 'image/gif'))
    expect(screen.getByRole('dialog', { name: 'Ảnh không hợp lệ' })).toBeInTheDocument()
  })

  it('previews, searches and opens a similar product', async () => {
    renderDialog()
    choose(image())
    expect(screen.getByRole('dialog', { name: 'Kiểm tra ảnh' })).toHaveTextContent(
      'polo-navy.jpg · 2,4 MB',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Tìm sản phẩm tương đồng' }))
    expect(await screen.findByRole('dialog', { name: 'Sản phẩm tương đồng' })).toBeInTheDocument()
    expect(searchByImage).toHaveBeenCalledWith(
      expect.any(File),
      expect.objectContaining({ categoryId: '' }),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(screen.getByText('Giống 87%')).toBeInTheDocument()
    expect(screen.getByText('Giá: tất cả · Danh mục: tất cả')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Áo Polo Nam Cotton Pique/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/products/p1')
    expect(onClose).toHaveBeenCalled()
  })

  it('filters by price and category with the same image', async () => {
    renderDialog()
    choose(image())
    fireEvent.click(screen.getByRole('button', { name: 'Tìm sản phẩm tương đồng' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Lọc giá và danh mục' }))
    fireEvent.change(screen.getByLabelText('Giá đến'), { target: { value: '500000' } })
    fireEvent.change(await screen.findByRole('combobox'), { target: { value: 'c1' } })
    await screen.findByRole('option', { name: 'Thời trang' })
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'c1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Áp dụng bộ lọc' }))
    expect(await screen.findByText(/Danh mục: Thời trang/)).toBeInTheDocument()
    expect(searchByImage).toHaveBeenLastCalledWith(
      expect.any(File),
      { minPrice: undefined, maxPrice: 500000, categoryId: 'c1' },
      expect.anything(),
    )
  })

  it('offers another image or a keyword search when nothing matches', async () => {
    searchByImage.mockResolvedValue([])
    renderDialog()
    choose(image())
    fireEvent.click(screen.getByRole('button', { name: 'Tìm sản phẩm tương đồng' }))
    expect(
      await screen.findByRole('dialog', { name: 'Chưa tìm thấy sản phẩm phù hợp' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Xem sản phẩm bán chạy' })).toHaveAttribute(
      'href',
      '/search?sort=BEST_SELLING',
    )
    fireEvent.change(screen.getByPlaceholderText('Nhập tên sản phẩm...'), {
      target: { value: 'áo polo' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Tìm bằng từ khóa' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/search?q=%C3%A1o%20polo')
  })

  it.each([
    [apiError(503, 'IMAGE_SEARCH_UNAVAILABLE'), 'Tìm bằng ảnh tạm gián đoạn'],
    [apiError(429, 'SEARCH_BUSY'), 'Hệ thống đang bận'],
    [apiError(413, 'IMAGE_TOO_LARGE'), 'Ảnh vượt quá 10 MB'],
    [apiError(400, 'INVALID_IMAGE'), 'Ảnh không hợp lệ'],
    [new Error('Network Error'), 'Chưa tìm được'],
  ])('explains a failed search (%#)', async (error, title) => {
    searchByImage.mockRejectedValue(error)
    renderDialog()
    choose(image())
    fireEvent.click(screen.getByRole('button', { name: 'Tìm sản phẩm tương đồng' }))
    expect(await screen.findByRole('dialog', { name: title })).toBeInTheDocument()
  })

  it('falls back to choosing a file when the camera is not allowed', async () => {
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn().mockRejectedValue(new Error('NotAllowedError')) },
    })
    renderDialog()
    fireEvent.click(screen.getByRole('button', { name: 'Dùng camera' }))
    expect(
      await screen.findByRole('dialog', { name: 'Chưa được phép dùng camera' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Chọn ảnh từ thiết bị' })).toBeInTheDocument()
  })

  it('stops the camera when the dialog closes', async () => {
    const stop = vi.fn()
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [{ stop }] }) },
    })
    renderDialog()
    fireEvent.click(screen.getByRole('button', { name: 'Dùng camera' }))
    expect(await screen.findByRole('dialog', { name: 'Chụp ảnh sản phẩm' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Đóng' }))
    expect(stop).toHaveBeenCalled()
  })
})
