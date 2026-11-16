// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import AdminCategoriesPage from './AdminCategoriesPage'
import AdminProductsPage from './AdminProductsPage'
import {
  createCategory,
  deleteCategory,
  listAdminCategories,
  listAdminProducts,
  moderateProduct,
  setCategoryVisibility,
  updateCategory,
} from '../services/api/productApi'

vi.mock('../services/api/productApi', () => ({
  createCategory: vi.fn(),
  deleteCategory: vi.fn(),
  listAdminCategories: vi.fn(),
  listAdminProducts: vi.fn(),
  moderateProduct: vi.fn(),
  setCategoryVisibility: vi.fn(),
  updateCategory: vi.fn(),
}))

const tree = [
  {
    id: 'c1',
    name: 'Thời trang',
    sortOrder: 0,
    active: true,
    children: [{ id: 'c1a', name: 'Áo polo', parentId: 'c1', sortOrder: 0, active: false }],
  },
  { id: 'c2', name: 'Điện máy', sortOrder: 1, active: true, children: [] },
]

const renderWithRouter = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>)

beforeEach(() => {
  vi.clearAllMocks()
  listAdminCategories.mockResolvedValue(tree)
  createCategory.mockImplementation(async (body) => ({ id: 'new', ...body }))
  updateCategory.mockImplementation(async (id, body) => ({ id, ...body }))
  setCategoryVisibility.mockResolvedValue({})
  deleteCategory.mockResolvedValue()
})

describe('AdminCategoriesPage', () => {
  it('renders the tree with nesting and visibility', async () => {
    renderWithRouter(<AdminCategoriesPage />)
    const child = (await screen.findByText('Áo polo')).closest('tr')
    expect(within(child).getByText('Đang ẩn')).toBeInTheDocument()
    expect(screen.getByText(/1 danh mục con/)).toBeInTheDocument()
  })

  it('adds a subcategory under the chosen parent', async () => {
    renderWithRouter(<AdminCategoriesPage />)
    const row = (await screen.findByText('Điện máy')).closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: 'Thêm con' }))
    const dialog = screen.getByRole('dialog', { name: 'Thêm danh mục' })
    fireEvent.change(within(dialog).getByLabelText('Tên danh mục *'), {
      target: { value: 'Tivi' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Lưu danh mục' }))
    await screen.findByText('Đã thêm “Tivi”.')
    expect(createCategory).toHaveBeenCalledWith({
      name: 'Tivi',
      parentId: 'c2',
      sortOrder: 0,
      active: true,
    })
  })

  it('does not offer a category or its children as its own parent', async () => {
    renderWithRouter(<AdminCategoriesPage />)
    const row = (await screen.findByText('Thời trang')).closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: 'Sửa' }))
    const select = within(screen.getByRole('dialog')).getByLabelText('Danh mục cha')
    const options = within(select)
      .getAllByRole('option')
      .map((option) => option.textContent)
    expect(options).toEqual(['— Danh mục cấp 1 —', 'Điện máy'])
  })

  it('toggles visibility and explains a blocked delete', async () => {
    deleteCategory.mockRejectedValue({
      response: {
        status: 409,
        data: { error: { code: 'CATEGORY_IN_USE', message: 'Danh mục còn sản phẩm.' } },
      },
    })
    renderWithRouter(<AdminCategoriesPage />)
    const row = (await screen.findByText('Điện máy')).closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: 'Ẩn' }))
    await waitFor(() => expect(setCategoryVisibility).toHaveBeenCalledWith('c2', false))
    fireEvent.click(
      within(screen.getByText('Điện máy').closest('tr')).getByRole('button', { name: 'Xoá' }),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Xoá danh mục' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Danh mục còn sản phẩm.')
  })
})

describe('AdminProductsPage', () => {
  const products = [
    {
      id: 'p1',
      shopId: 's1',
      name: 'Áo polo',
      status: 'ACTIVE',
      currency: 'VND',
      minPrice: 249000,
      imageUrls: [],
    },
    {
      id: 'p2',
      shopId: 's2',
      name: 'Nón fake',
      status: 'ACTIVE',
      currency: 'VND',
      minPrice: 10000,
      imageUrls: [],
      moderationRemoved: true,
      moderationReason: 'Hàng giả',
    },
  ]

  beforeEach(() => {
    listAdminProducts.mockResolvedValue(products)
    moderateProduct.mockImplementation(async (id, body) => ({
      ...products.find((product) => product.id === id),
      moderationRemoved: body.removed,
    }))
  })

  it('requires a reason before removing a product', async () => {
    renderWithRouter(<AdminProductsPage />)
    const row = (await screen.findByText('Áo polo')).closest('tr')
    fireEvent.click(within(row).getByRole('button', { name: 'Gỡ' }))
    const dialog = screen.getByRole('dialog', { name: 'Gỡ sản phẩm vi phạm' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gỡ sản phẩm' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Vui lòng nhập lý do')
    fireEvent.change(within(dialog).getByLabelText('Lý do gỡ *'), {
      target: { value: 'Sai danh mục' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Gỡ sản phẩm' }))
    await screen.findByText('Đã gỡ “Áo polo”.')
    expect(moderateProduct).toHaveBeenCalledWith('p1', { removed: true, reason: 'Sai danh mục' })
  })

  it('restores a removed product from the removed tab', async () => {
    renderWithRouter(<AdminProductsPage />)
    await screen.findByText('Áo polo')
    fireEvent.click(screen.getByRole('button', { name: /Đã gỡ/ }))
    const row = screen.getByText('Nón fake').closest('tr')
    expect(within(row).getByText('Hàng giả')).toBeInTheDocument()
    fireEvent.click(within(row).getByRole('button', { name: 'Khôi phục' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Khôi phục' }))
    await waitFor(() =>
      expect(moderateProduct).toHaveBeenCalledWith('p2', { removed: false, reason: '' }),
    )
  })
})
