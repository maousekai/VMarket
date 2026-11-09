// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AuthContext } from '../auth/useAuth'
import { CartProvider } from '../cart/CartProvider'
import { withQuantity } from '../cart/cartMath'
import CartPage from './CartPage'
import AddToCartActions from '../components/AddToCartActions'
import { resetCatalogLookup } from '../hooks/useCatalogLookup'
import {
  addCartItem,
  getCart,
  removeCartItem,
  updateCartItemQuantity,
  validateCart,
} from '../services/api/cartApi'
import { getProduct } from '../services/api/productApi'
import { getPublicShop } from '../services/api/shopApi'

vi.mock('../services/api/cartApi', async (importOriginal) => ({
  ...(await importOriginal()),
  addCartItem: vi.fn(),
  getCart: vi.fn(),
  removeCartItem: vi.fn(),
  updateCartItemQuantity: vi.fn(),
  validateCart: vi.fn(),
}))
vi.mock('../services/api/productApi', () => ({ getProduct: vi.fn() }))
vi.mock('../services/api/shopApi', () => ({ getPublicShop: vi.fn() }))

const line = (productId, variantId, shopId, quantity, unitPrice) => ({
  productId,
  variantId,
  shopId,
  quantity,
  unitPrice,
  lineTotal: quantity * unitPrice,
})

const cartOf = (...items) => {
  const byShop = new Map()
  items.forEach((item) => byShop.set(item.shopId, [...(byShop.get(item.shopId) || []), item]))
  return withQuantity(
    {
      userId: 'u1',
      groups: [...byShop].map(([shopId, groupItems]) => ({
        shopId,
        items: groupItems,
        subtotal: 0,
      })),
    },
    'none',
    0,
  )
}

const polo = line('p1', 'v1', 's1', 2, 249000)
const mug = line('p2', 'v2', 's2', 1, 120000)

const session = { userId: 'u1', status: 'ACTIVE', roles: ['BUYER'], email: 'a@b.c' }
const auth = (value = session) => ({ session: value, bootstrapState: 'ready' })

function renderWithCart(ui, { path = '/cart', user = session } = {}) {
  return render(
    <AuthContext.Provider value={auth(user)}>
      <CartProvider>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/cart" element={ui} />
            <Route path="/checkout" element={<p>Trang thanh toán</p>} />
            <Route path="/login" element={<p>Trang đăng nhập</p>} />
            <Route path="/products/:id" element={ui} />
          </Routes>
        </MemoryRouter>
      </CartProvider>
    </AuthContext.Provider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  resetCatalogLookup()
  getCart.mockResolvedValue(cartOf(polo, mug))
  getProduct.mockImplementation(async (id) => ({
    product: {
      id,
      name: id === 'p1' ? 'Áo polo' : 'Ly gốm',
      imageUrls: [],
      variants: [{ id: id === 'p1' ? 'v1' : 'v2', attributes: { Size: 'L' }, availableStock: 5 }],
    },
  }))
  getPublicShop.mockImplementation(async (id) => ({
    id,
    name: id === 's1' ? 'Danang Apparel' : 'Gốm Mộc',
  }))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('withQuantity', () => {
  it('recomputes line, group and cart totals and drops empty lines', () => {
    const cart = cartOf(polo, mug)
    expect(cart.totalAmount).toBe(618000)
    const next = withQuantity(cart, 'p1::v1', 3)
    expect(next.groups[0].subtotal).toBe(747000)
    expect(next.totalQuantity).toBe(4)
    const removed = withQuantity(cart, 'p2::v2', 0)
    expect(removed.groups).toHaveLength(1)
  })
})

describe('CartPage', () => {
  it('groups items by shop with product details and totals', async () => {
    renderWithCart(<CartPage />)
    const apparel = await screen.findByRole('region', { name: 'Danang Apparel' })
    expect(within(apparel).getByRole('link', { name: 'Áo polo' })).toBeInTheDocument()
    expect(within(apparel).getByText('Size: L')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Gốm Mộc' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Giỏ hàng/ })).toHaveTextContent('3 sản phẩm')
  })

  it('updates quantity optimistically and rolls back when the server refuses', async () => {
    updateCartItemQuantity.mockRejectedValue({
      response: { status: 400, data: { error: { code: 'X', message: 'Không đổi được số lượng' } } },
    })
    renderWithCart(<CartPage />)
    const apparel = await screen.findByRole('region', { name: 'Danang Apparel' })
    vi.useFakeTimers()
    fireEvent.click(within(apparel).getByRole('button', { name: 'Tăng' }))
    fireEvent.click(within(apparel).getByRole('button', { name: 'Tăng' }))
    expect(within(apparel).getByRole('textbox', { name: 'Số lượng' })).toHaveValue('4')
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500)
    })
    vi.useRealTimers()
    expect(updateCartItemQuantity).toHaveBeenCalledTimes(1)
    expect(updateCartItemQuantity).toHaveBeenCalledWith('p1', 'v1', 4)
    expect(await within(apparel).findByText('Không đổi được số lượng')).toBeInTheDocument()
    expect(within(apparel).getByRole('textbox', { name: 'Số lượng' })).toHaveValue('2')
  })

  it('goes to checkout when validation passes', async () => {
    validateCart.mockResolvedValue({ ok: true, issues: [] })
    renderWithCart(<CartPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Tiến hành đặt hàng' }))
    expect(await screen.findByText('Trang thanh toán')).toBeInTheDocument()
  })

  it('shows price and stock warnings and fixes a changed price', async () => {
    validateCart.mockResolvedValue({
      ok: false,
      issues: [
        {
          productId: 'p1',
          variantId: 'v1',
          type: 'PRICE_CHANGED',
          cartPrice: 249000,
          currentPrice: 259000,
        },
        { productId: 'p2', variantId: 'v2', type: 'OUT_OF_STOCK', availableStock: 0 },
      ],
    })
    removeCartItem.mockResolvedValue(cartOf(mug))
    addCartItem.mockResolvedValue(cartOf({ ...polo, unitPrice: 259000, lineTotal: 518000 }, mug))
    renderWithCart(<CartPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Tiến hành đặt hàng' }))
    const apparel = screen.getByRole('region', { name: 'Danang Apparel' })
    expect(await within(apparel).findByText(/Giá đã đổi từ/)).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'Gốm Mộc' })).getByText('Sản phẩm đã hết hàng.'),
    ).toBeInTheDocument()
    fireEvent.click(within(apparel).getByRole('button', { name: 'Cập nhật giá mới' }))
    await waitFor(() =>
      expect(addCartItem).toHaveBeenCalledWith(
        expect.objectContaining({
          productId: 'p1',
          variantId: 'v1',
          quantity: 2,
          unitPrice: 259000,
        }),
      ),
    )
    expect(within(apparel).queryByText(/Giá đã đổi từ/)).not.toBeInTheDocument()
  })

  it('removes the selected items after confirmation', async () => {
    removeCartItem.mockResolvedValue(cartOf(mug))
    renderWithCart(<CartPage />)
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Chọn Áo polo' }))
    fireEvent.click(screen.getByRole('button', { name: 'Xoá đã chọn (1)' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Xoá' }))
    await waitFor(() => expect(removeCartItem).toHaveBeenCalledWith('p1', 'v1'))
    await waitFor(() => expect(screen.queryByText('Áo polo')).not.toBeInTheDocument())
  })

  it('shows the empty state', async () => {
    getCart.mockResolvedValue({ groups: [], totalQuantity: 0, totalAmount: 0 })
    renderWithCart(<CartPage />)
    expect(await screen.findByRole('heading', { name: 'Giỏ hàng đang trống' })).toBeInTheDocument()
  })
})

describe('AddToCartActions', () => {
  const product = { id: 'p1', shopId: 's1' }
  const variant = { id: 'v1', price: 249000 }

  it('adds the selected variant with its current price', async () => {
    addCartItem.mockResolvedValue(cartOf(polo))
    renderWithCart(<AddToCartActions product={product} variant={variant} quantity={2} />, {
      path: '/products/p1',
    })
    fireEvent.click(screen.getByRole('button', { name: 'Thêm vào giỏ hàng' }))
    expect(await screen.findByText(/Đã thêm 2 sản phẩm vào giỏ/)).toBeInTheDocument()
    expect(addCartItem).toHaveBeenCalledWith({
      productId: 'p1',
      variantId: 'v1',
      shopId: 's1',
      quantity: 2,
      unitPrice: 249000,
    })
  })

  it('sends guests to login', async () => {
    renderWithCart(<AddToCartActions product={product} variant={variant} quantity={1} />, {
      path: '/products/p1',
      user: null,
    })
    fireEvent.click(screen.getByRole('button', { name: 'Mua ngay' }))
    expect(await screen.findByText('Trang đăng nhập')).toBeInTheDocument()
    expect(addCartItem).not.toHaveBeenCalled()
  })

  it('is disabled for a sold-out variant', () => {
    renderWithCart(<AddToCartActions product={product} variant={variant} quantity={1} soldOut />, {
      path: '/products/p1',
    })
    expect(screen.getByRole('button', { name: 'Thêm vào giỏ hàng' })).toBeDisabled()
  })
})
