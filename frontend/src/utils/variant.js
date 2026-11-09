// Chon bien the san pham theo thuoc tinh (ProductResponse.variants[].attributes: Map<String,String>).

/** Cac nhom thuoc tinh va gia tri, giu thu tu xuat hien dau tien. */
export function attributeGroups(variants = []) {
  const groups = new Map()
  for (const variant of variants) {
    for (const [name, value] of Object.entries(variant.attributes || {})) {
      if (!groups.has(name)) groups.set(name, [])
      if (!groups.get(name).includes(value)) groups.get(name).push(value)
    }
  }
  return [...groups].map(([name, values]) => ({ name, values }))
}

const matches = (variant, selection) =>
  Object.entries(selection).every(([name, value]) => variant.attributes?.[name] === value)

/** Bien the khop day du lua chon; null khi chua chon du hoac khong co bien the khop. */
export function findVariant(variants = [], selection = {}) {
  if (variants.length === 1) return variants[0]
  const groups = attributeGroups(variants)
  if (groups.some((group) => !selection[group.name])) return null
  return variants.find((variant) => matches(variant, selection)) || null
}

/**
 * Gia tri `value` cua nhom `name` co the chon cung cac lua chon khac hay khong
 * (con it nhat mot bien the khop va con hang).
 */
export function isOptionAvailable(variants = [], selection = {}, name, value) {
  const next = { ...selection, [name]: value }
  return variants.some((variant) => matches(variant, next) && (variant.availableStock ?? 0) > 0)
}

/** Lua chon ban dau: bien the dau tien con hang (hoac bien the dau tien). */
export function initialSelection(variants = []) {
  const first = variants.find((variant) => (variant.availableStock ?? 0) > 0) || variants[0]
  return first ? { ...(first.attributes || {}) } : {}
}

/** Mo ta ngan cua bien the: "Màu: Đen · Size: L". */
export function describeVariant(variant) {
  const entries = Object.entries(variant?.attributes || {})
  return entries.map(([name, value]) => `${name}: ${value}`).join(' · ')
}
