import { PRODUCT_LIMITS, PRODUCT_STATUS } from '../services/api/productApi'

let localKey = 0
const nextKey = () => `v${(localKey += 1)}`

export const emptyVariant = () => ({
  key: nextKey(),
  id: null,
  sku: '',
  attributes: [{ name: '', value: '' }],
  price: '',
  stock: '',
  reservedStock: 0,
})

export const emptyProductForm = () => ({
  name: '',
  description: '',
  categoryId: '',
  brandId: null,
  imageUrls: [],
  variants: [emptyVariant()],
})

/** ProductResponse -> trang thai form (gia/ton kho la chuoi de input dieu khien duoc). */
export function productToForm(product) {
  return {
    name: product.name || '',
    description: product.description || '',
    categoryId: product.categoryId || '',
    // Form chua co o chon thuong hieu, nhung PUT ghi de brandId: phai giu nguyen gia tri cu.
    brandId: product.brandId || null,
    imageUrls: [...(product.imageUrls || [])],
    variants: (product.variants?.length ? product.variants : [null]).map((variant) =>
      variant
        ? {
            key: nextKey(),
            id: variant.id,
            sku: variant.sku || '',
            attributes: Object.entries(variant.attributes || {}).map(([name, value]) => ({
              name,
              value,
            })),
            price: String(variant.price ?? ''),
            stock: String(variant.stock ?? ''),
            reservedStock: variant.reservedStock ?? 0,
          }
        : emptyVariant(),
    ),
  }
}

const isWholeNumber = (value) => /^\d+$/.test(String(value).trim())

/** Loi theo tung truong, khop rang buoc cua ProductRequest phia backend. */
export function validateProductForm(form) {
  const errors = {}
  const name = form.name.trim()
  if (!name) errors.name = 'Vui lòng nhập tên sản phẩm.'
  else if (name.length > PRODUCT_LIMITS.name)
    errors.name = `Tên tối đa ${PRODUCT_LIMITS.name} ký tự.`
  if (!form.description.trim()) errors.description = 'Vui lòng nhập mô tả sản phẩm.'
  else if (form.description.length > PRODUCT_LIMITS.description)
    errors.description = `Mô tả tối đa ${PRODUCT_LIMITS.description} ký tự.`
  if (!form.categoryId) errors.categoryId = 'Vui lòng chọn danh mục.'
  if (!form.imageUrls.length) errors.imageUrls = 'Cần ít nhất 1 ảnh sản phẩm.'
  else if (form.imageUrls.length > PRODUCT_LIMITS.images)
    errors.imageUrls = `Tối đa ${PRODUCT_LIMITS.images} ảnh.`

  const skus = new Map()
  const combos = new Map()
  // Moi phan loai phai cung bo nhom thuoc tinh, nguoi mua moi chon duoc tung phan loai.
  const groupsOf = (variant) =>
    variant.attributes
      .filter((item) => item.name.trim())
      .map((item) => item.name.trim().toLowerCase())
      .sort()
      .join('|')
  const firstGroups = groupsOf(form.variants[0])
  form.variants.forEach((variant, index) => {
    const prefix = `variants.${index}`
    const sku = variant.sku.trim()
    if (!sku) errors[`${prefix}.sku`] = 'Nhập mã SKU.'
    else if (sku.length > PRODUCT_LIMITS.sku)
      errors[`${prefix}.sku`] = `SKU tối đa ${PRODUCT_LIMITS.sku} ký tự.`
    else if (skus.has(sku.toLowerCase())) errors[`${prefix}.sku`] = 'SKU bị trùng.'
    else skus.set(sku.toLowerCase(), index)

    if (!isWholeNumber(variant.price) || Number(variant.price) <= 0)
      errors[`${prefix}.price`] = 'Giá phải là số nguyên lớn hơn 0.'
    if (!isWholeNumber(variant.stock)) errors[`${prefix}.stock`] = 'Tồn kho là số nguyên ≥ 0.'
    else if (Number(variant.stock) < (variant.reservedStock || 0))
      errors[`${prefix}.stock`] = `Không thấp hơn ${variant.reservedStock} đang được giữ cho đơn.`

    const filled = variant.attributes.filter((item) => item.name.trim() || item.value.trim())
    if (filled.some((item) => !item.name.trim() || !item.value.trim()))
      errors[`${prefix}.attributes`] = 'Mỗi thuộc tính cần đủ tên và giá trị.'
    const names = filled.map((item) => item.name.trim().toLowerCase())
    if (new Set(names).size !== names.length)
      errors[`${prefix}.attributes`] = 'Tên thuộc tính bị trùng.'
    const combo = filled
      .map((item) => `${item.name.trim().toLowerCase()}=${item.value.trim().toLowerCase()}`)
      .sort()
      .join('|')
    if (form.variants.length > 1 && !combo)
      errors[`${prefix}.attributes`] = 'Sản phẩm nhiều phân loại cần thuộc tính để phân biệt.'
    else if (index > 0 && groupsOf(variant) !== firstGroups)
      errors[`${prefix}.attributes`] =
        'Mọi phân loại cần cùng nhóm thuộc tính với phân loại đầu tiên (vd đều có Màu và Size).'
    else if (combo && combos.has(combo))
      errors[`${prefix}.attributes`] = 'Trùng thuộc tính với một phân loại khác.'
    else combos.set(combo, index)
  })
  return errors
}

/** Trang thai form -> ProductRequest. */
export function formToRequest(form, { shopId, status = PRODUCT_STATUS.active }) {
  return {
    shopId,
    name: form.name.trim(),
    description: form.description.trim(),
    imageUrls: form.imageUrls,
    categoryId: form.categoryId,
    brandId: form.brandId || null,
    status,
    currency: 'VND',
    variants: form.variants.map((variant) => ({
      ...(variant.id ? { id: variant.id } : {}),
      sku: variant.sku.trim(),
      attributes: Object.fromEntries(
        variant.attributes
          .filter((item) => item.name.trim() && item.value.trim())
          .map((item) => [item.name.trim(), item.value.trim()]),
      ),
      price: Number(variant.price),
      stock: Number(variant.stock),
    })),
  }
}

/** ProductResponse -> ProductRequest giu nguyen noi dung, chi doi trang thai (an / hien). */
export function productToRequest(product, status = product.status) {
  return formToRequest(productToForm(product), { shopId: product.shopId, status })
}
