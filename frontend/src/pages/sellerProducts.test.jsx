// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import SellerProductsPage from './SellerProductsPage'
import SellerProductFormPage from './SellerProductFormPage'
import ProductImagesField from '../components/ProductImagesField'
import {
  createProduct,
  deleteProduct,
  listCategories,
  listMySellerProducts,
  resubmitProduct,
  updateProduct,
} from '../services/api/productApi'
import { deleteProductMedia, uploadProductImage } from '../services/api/mediaApi'
import { getMyShop } from '../services/api/shopApi'

vi.mock('../services/api/productApi', async (importOriginal) => ({
  ...(await importOriginal()),
  createProduct: vi.fn(),
  deleteProduct: vi.fn(),
  listCategories: vi.fn(),
  listMySellerProducts: vi.fn(),
  resubmitProduct: vi.fn(),
  updateProduct: vi.fn(),
}))
vi.mock('../services/api/mediaApi', async (importOriginal) => ({
  ...(await importOriginal()),
  uploadProductImage: vi.fn(),
  deleteProductMedia: vi.fn(),
}))
vi.mock('../services/api/shopApi', () => ({ getMyShop: vi.fn() }))

const products = [
  {
    id: 'p1',
    shopId: 's1',
    name: 'Áo polo',
    description: 'Áo polo cotton',
    categoryId: 'c1',
    imageUrls: ['https://cdn/p1.jpg'],
    status: 'ACTIVE',
    currency: 'VND',
    minPrice: 249000,
    maxPrice: 259000,
    availableStock: 8,
    soldCount: 3,
    variants: [
      { id: 'v1', sku: 'POLO-M', attributes: { Size: 'M' }, price: 249000, stock: 5 },
      { id: 'v2', sku: 'POLO-L', attributes: { Size: 'L' }, price: 259000, stock: 3 },
    ],
  },
  {
    id: 'p2',
    shopId: 's1',
    name: 'Nón bucket',
    categoryId: 'c1',
    imageUrls: [],
    status: 'ACTIVE',
    currency: 'VND',
    minPrice: 120000,
    maxPrice: 120000,
    availableStock: 0,
    moderationRemoved: true,
    moderationReason: 'Ảnh vi phạm bản quyền',
    variants: [{ id: 'v3', sku: 'HAT', attributes: {}, price: 120000, stock: 0 }],
  },
]

const renderAt = (path) =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/seller/products" element={<SellerProductsPage />} />
        <Route path="/seller/products/new" element={<SellerProductFormPage />} />
        <Route path="/seller/products/:productId/edit" element={<SellerProductFormPage />} />
      </Routes>
    </MemoryRouter>,
  )

beforeEach(() => {
  vi.clearAllMocks()
  listMySellerProducts.mockResolvedValue(products)
  listCategories.mockResolvedValue([{ id: 'c1', name: 'Thời trang', children: [] }])
  getMyShop.mockResolvedValue({ id: 's1' })
  updateProduct.mockResolvedValue({})
  deleteProduct.mockResolvedValue()
  resubmitProduct.mockResolvedValue({})
  deleteProductMedia.mockResolvedValue()
})

describe('SellerProductsPage', () => {
  it('lists products with price range, stock and moderation reason', async () => {
    renderAt('/seller/products')
    const row = (await screen.findByText('Áo polo')).closest('tr')
    expect(within(row).getByText(/249\.000/)).toBeInTheDocument()
    expect(within(row).getByText('2 phân loại')).toBeInTheDocument()
    const removed = screen.getByText('Nón bucket').closest('tr')
    expect(within(removed).getByText('Ảnh vi phạm bản quyền')).toBeInTheDocument()
  })

  it('filters by tab and searches by SKU', async () => {
    renderAt('/seller/products')
    await screen.findByText('Áo polo')
    fireEvent.click(screen.getByRole('button', { name: /Bị Admin gỡ/ }))
    expect(screen.queryByText('Áo polo')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Tất cả/ }))
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'polo-l' } })
    expect(screen.getByText('Áo polo')).toBeInTheDocument()
    expect(screen.queryByText('Nón bucket')).not.toBeInTheDocument()
  })

  it('hides a product by sending the full product with HIDDEN status', async () => {
    renderAt('/seller/products')
    const row = (await screen.findByText('Áo polo')).closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: 'Ẩn' }))
    await screen.findByText(/Đã ẩn “Áo polo”/)
    expect(updateProduct).toHaveBeenCalledWith(
      'p1',
      expect.objectContaining({
        status: 'HIDDEN',
        shopId: 's1',
        imageUrls: ['https://cdn/p1.jpg'],
      }),
    )
  })

  it('resubmits a removed product and confirms deletion', async () => {
    renderAt('/seller/products')
    const removed = (await screen.findByText('Nón bucket')).closest('tr')
    fireEvent.click(within(removed).getByRole('button', { name: 'Gửi lại' }))
    await waitFor(() => expect(resubmitProduct).toHaveBeenCalledWith('p2'))

    const row = screen.getByText('Áo polo').closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: 'Xoá' }))
    const dialog = screen.getByRole('dialog', { name: 'Xoá sản phẩm?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Xoá sản phẩm' }))
    await waitFor(() => expect(deleteProduct).toHaveBeenCalledWith('p1'))
  })

  it('explains that an unapproved shop cannot manage products', async () => {
    listMySellerProducts.mockRejectedValue({
      response: { status: 403, data: { error: { code: 'FORBIDDEN', message: 'x' } } },
    })
    renderAt('/seller/products')
    expect(await screen.findByRole('alert')).toHaveTextContent('cần được duyệt')
  })
})

describe('SellerProductFormPage', () => {
  it('blocks saving an incomplete product', async () => {
    renderAt('/seller/products/new')
    fireEvent.click(await screen.findByRole('button', { name: 'Đăng bán' }))
    expect(await screen.findByText('Vui lòng nhập tên sản phẩm.')).toBeInTheDocument()
    expect(screen.getByText('Cần ít nhất 1 ảnh sản phẩm.')).toBeInTheDocument()
    expect(createProduct).not.toHaveBeenCalled()
  })

  it('uploads an image and creates the product as a draft', async () => {
    uploadProductImage.mockResolvedValue({ url: 'https://cdn/new.jpg' })
    createProduct.mockResolvedValue({
      id: 'p9',
      name: 'Túi tote',
      imageUrls: ['https://cdn/new.jpg'],
    })
    renderAt('/seller/products/new')
    fireEvent.change(await screen.findByLabelText(/Tên sản phẩm/), {
      target: { value: 'Túi tote' },
    })
    fireEvent.change(screen.getByLabelText('Danh mục *'), { target: { value: 'c1' } })
    fireEvent.change(screen.getByLabelText(/^Mô tả \*/), { target: { value: 'Túi vải canvas' } })
    fireEvent.change(screen.getByLabelText('SKU *'), { target: { value: 'TOTE' } })
    fireEvent.change(screen.getByLabelText('Giá bán (₫) *'), { target: { value: '89000' } })
    fireEvent.change(screen.getByLabelText('Tồn kho *'), { target: { value: '20' } })
    const file = new File(['x'], 'tote.png', { type: 'image/png' })
    fireEvent.change(screen.getByLabelText(/Thêm ảnh/), { target: { files: [file] } })
    expect(await screen.findByRole('img', { name: 'Ảnh 1' })).toHaveAttribute(
      'src',
      'https://cdn/new.jpg',
    )
    fireEvent.click(screen.getByRole('button', { name: 'Lưu nháp' }))
    await waitFor(() => expect(createProduct).toHaveBeenCalled())
    const [body, key] = createProduct.mock.calls[0]
    expect(body).toMatchObject({
      shopId: 's1',
      name: 'Túi tote',
      description: 'Túi vải canvas',
      status: 'DRAFT',
      imageUrls: ['https://cdn/new.jpg'],
      variants: [{ sku: 'TOTE', price: 89000, stock: 20, attributes: {} }],
    })
    expect(key).toEqual(expect.any(String))
  })

  it('loads an existing product for editing and shows server errors', async () => {
    updateProduct.mockRejectedValue({
      response: { status: 409, data: { error: { code: 'STOCK_BELOW_RESERVED', message: 'x' } } },
    })
    renderAt('/seller/products/p1/edit')
    expect(await screen.findByDisplayValue('Áo polo')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Lưu & đăng bán' }))
    expect(await screen.findByText(/thấp hơn số lượng đang được giữ/)).toBeInTheDocument()
    expect(updateProduct).toHaveBeenCalledWith(
      'p1',
      expect.objectContaining({ status: 'ACTIVE' }),
      expect.any(String),
    )
  })
})

describe('ProductImagesField', () => {
  it('refuses more than 9 images and unsupported files', async () => {
    const upload = vi.fn().mockResolvedValue({ url: 'https://cdn/9.jpg' })
    const value = Array.from({ length: 8 }, (_, index) => `https://cdn/${index}.jpg`)
    render(<ProductImagesField value={value} onChange={vi.fn()} upload={upload} />)
    const files = [
      new File(['a'], 'a.png', { type: 'image/png' }),
      new File(['b'], 'b.png', { type: 'image/png' }),
    ]
    fireEvent.change(screen.getByLabelText(/Thêm ảnh/), { target: { files } })
    expect(await screen.findByRole('alert')).toHaveTextContent('Chỉ thêm được 1 ảnh nữa')
    await waitFor(() => expect(upload).toHaveBeenCalledTimes(1))
  })

  it('ignores an image that is already in the list', async () => {
    const upload = vi.fn().mockResolvedValue({ url: 'https://cdn/1.jpg' })
    const onChange = vi.fn()
    render(<ProductImagesField value={['https://cdn/1.jpg']} onChange={onChange} upload={upload} />)
    fireEvent.change(screen.getByLabelText(/Thêm ảnh/), {
      target: { files: [new File(['a'], 'a.png', { type: 'image/png' })] },
    })
    expect(await screen.findByText('Ảnh này đã có trong danh sách.')).toBeInTheDocument()
    const updater = onChange.mock.calls[0][0]
    expect(updater(['https://cdn/1.jpg'])).toEqual(['https://cdn/1.jpg'])
  })

  it('rejects a GIF before uploading', async () => {
    const upload = vi.fn()
    render(<ProductImagesField value={[]} onChange={vi.fn()} upload={upload} />)
    fireEvent.change(screen.getByLabelText(/Thêm ảnh/), {
      target: { files: [new File(['g'], 'a.gif', { type: 'image/gif' })] },
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('chỉ nhận JPEG, PNG hoặc WebP')
    expect(upload).not.toHaveBeenCalled()
  })
})
