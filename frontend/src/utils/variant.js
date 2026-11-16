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

const chosen = (selection) => Object.entries(selection).filter(([, value]) => value)

/**
 * Bien the khong mau thuan voi lua chon: moi thuoc tinh bien the CO thi phai bang lua chon.
 * San pham cu co the co bien the dung bo thuoc tinh khac nhau ({Mau: Do} va {Size: M}), nen
 * thuoc tinh bien the khong co thi bo qua.
 */
const compatible = (variant, selection) =>
  chosen(selection).every(
    ([name, value]) => !(name in (variant.attributes || {})) || variant.attributes[name] === value,
  )

/** Lua chon xac dinh dung bien the: chon du va dung moi thuoc tinh cua bien the, khong thua. */
const exactly = (variant, selection) => {
  const attributes = Object.entries(variant.attributes || {})
  const picked = chosen(selection)
  return (
    attributes.length === picked.length &&
    attributes.every(([name, value]) => selection[name] === value)
  )
}

/** Bien the khop day du lua chon; null khi chua chon du hoac khong co bien the khop. */
export function findVariant(variants = [], selection = {}) {
  if (variants.length === 1) return variants[0]
  return variants.find((variant) => exactly(variant, selection)) || null
}

/**
 * Gia tri `value` cua nhom `name` co the chon cung cac lua chon khac hay khong
 * (con it nhat mot bien the co gia tri nay, khong mau thuan lua chon va con hang).
 */
export function isOptionAvailable(variants = [], selection = {}, name, value) {
  const next = { ...selection, [name]: value }
  return variants.some(
    (variant) =>
      variant.attributes?.[name] === value &&
      compatible(variant, next) &&
      (variant.availableStock ?? 0) > 0,
  )
}

/**
 * Lua chon moi khi bam gia tri `value` cua nhom `name`. Neu to hop moi khong ung voi bien the
 * nao, chuyen sang bien the co gia tri nay (uu tien con hang, khop nhieu lua chon cu nhat).
 */
export function selectOption(variants = [], selection = {}, name, value) {
  const next = { ...selection, [name]: value }
  if (variants.some((variant) => exactly(variant, next))) return next
  const candidates = variants.filter((variant) => variant.attributes?.[name] === value)
  if (!candidates.length) return next
  const score = (variant) =>
    ((variant.availableStock ?? 0) > 0 ? 100 : 0) +
    chosen(selection).filter(([key, picked]) => variant.attributes?.[key] === picked).length
  const best = candidates.reduce((top, variant) => (score(variant) > score(top) ? variant : top))
  return { ...best.attributes }
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
