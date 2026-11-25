import { describe, expect, it } from 'vitest'
import {
  emptyProductForm,
  formToRequest,
  productToForm,
  productToRequest,
  validateProductForm,
} from './productForm'

const product = {
  id: 'p1',
  shopId: 's1',
  name: 'Áo polo',
  description: 'Cotton',
  categoryId: 'c1',
  brandId: 'b1',
  imageUrls: ['https://cdn/1.jpg'],
  status: 'ACTIVE',
  variants: [
    {
      id: 'v1',
      sku: 'POLO-M',
      attributes: { Size: 'M' },
      price: 249000,
      stock: 10,
      reservedStock: 2,
    },
    {
      id: 'v2',
      sku: 'POLO-L',
      attributes: { Size: 'L' },
      price: 259000,
      stock: 4,
      reservedStock: 0,
    },
  ],
}

describe('product form helpers', () => {
  it('requires every variant to use the same attribute groups', () => {
    const form = productToForm({
      ...product,
      variants: [
        { id: 'v1', sku: 'A', attributes: { Màu: 'Đỏ' }, price: 1000, stock: 1 },
        { id: 'v2', sku: 'B', attributes: { Size: 'M' }, price: 1000, stock: 1 },
      ],
    })
    expect(validateProductForm(form)['variants.1.attributes']).toMatch(/cùng nhóm thuộc tính/)
    expect(validateProductForm(productToForm(product))['variants.1.attributes']).toBeUndefined()
  })

  it('round-trips a product into a request, keeping variant ids and the brand', () => {
    expect(productToRequest(product, 'HIDDEN')).toEqual({
      shopId: 's1',
      name: 'Áo polo',
      description: 'Cotton',
      imageUrls: ['https://cdn/1.jpg'],
      categoryId: 'c1',
      brandId: 'b1',
      status: 'HIDDEN',
      currency: 'VND',
      variants: [
        { id: 'v1', sku: 'POLO-M', attributes: { Size: 'M' }, price: 249000, stock: 10 },
        { id: 'v2', sku: 'POLO-L', attributes: { Size: 'L' }, price: 259000, stock: 4 },
      ],
    })
  })

  it('requires name, description, category, an image and valid variants', () => {
    const errors = validateProductForm({ ...emptyProductForm(), description: '   ' })
    expect(errors).toMatchObject({
      name: expect.any(String),
      description: 'Vui lòng nhập mô tả sản phẩm.',
      categoryId: expect.any(String),
      imageUrls: expect.any(String),
      'variants.0.sku': expect.any(String),
      'variants.0.price': expect.any(String),
      'variants.0.stock': expect.any(String),
    })
  })

  it('accepts a valid product', () => {
    expect(validateProductForm(productToForm(product))).toEqual({})
  })

  it('flags duplicate SKUs, stock below reservations and duplicate attribute sets', () => {
    const form = productToForm(product)
    form.variants[1].sku = 'polo-m'
    form.variants[1].attributes = [{ name: 'Size', value: 'M' }]
    form.variants[0].stock = '1'
    const errors = validateProductForm(form)
    expect(errors['variants.1.sku']).toBe('SKU bị trùng.')
    expect(errors['variants.0.stock']).toMatch(/đang được giữ/)
    expect(errors['variants.1.attributes']).toMatch(/Trùng thuộc tính/)
  })

  it('rejects more than 9 images and non-integer prices', () => {
    const form = productToForm(product)
    form.imageUrls = Array.from({ length: 10 }, (_, index) => `https://cdn/${index}.jpg`)
    form.variants[0].price = '12.5'
    const errors = validateProductForm(form)
    expect(errors.imageUrls).toBe('Tối đa 9 ảnh.')
    expect(errors['variants.0.price']).toBeTruthy()
  })

  it('drops empty attribute rows and trims values in the request', () => {
    const form = productToForm(product)
    form.variants[0].attributes.push({ name: ' ', value: '' })
    form.name = '  Áo mới  '
    const request = formToRequest(form, { shopId: 's1', status: 'DRAFT' })
    expect(request.name).toBe('Áo mới')
    expect(request.status).toBe('DRAFT')
    expect(request.variants[0].attributes).toEqual({ Size: 'M' })
  })
})
