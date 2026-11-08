import type { Category, ProductQuery, ProductSummary, SortKey, Variant } from '@/api/catalog'
import { API_BASE_URL } from '@/lib/config'

export const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: 'RELEVANCE', label: 'liên quan' },
  { key: 'BEST_SELLING', label: 'bán chạy' },
  { key: 'PRICE_ASC', label: 'giá tăng dần' },
  { key: 'PRICE_DESC', label: 'giá giảm dần' },
  { key: 'NEWEST', label: 'mới nhất' },
]

export const sortLabel = (key: SortKey) => SORT_OPTIONS.find((o) => o.key === key)?.label ?? ''

const API_HOST = /^https?:\/\/([^:/]+)/.exec(API_BASE_URL)?.[1]

/**
 * Seeded image URLs point at the dev MinIO on `localhost`, which a phone or emulator cannot
 * reach. Swap loopback hosts for the gateway host (MinIO runs next to it).
 */
export function resolveImageUrl(url?: string | null): string | undefined {
  if (!url) return undefined
  if (!API_HOST) return url
  return url.replace(/^(https?:\/\/)(localhost|127\.0\.0\.1)(?=[:/]|$)/, `$1${API_HOST}`)
}

export const coverImage = (p: Pick<ProductSummary, 'imageUrls'>) => resolveImageUrl(p.imageUrls[0])

export const productPrice = (p: Pick<ProductSummary, 'minPrice'>) => p.minPrice ?? 0

export function flattenCategories(
  tree: Category[] | undefined,
  depth = 0,
): { category: Category; depth: number }[] {
  return (tree ?? []).flatMap((c) => [
    { category: c, depth },
    ...flattenCategories(c.children, depth + 1),
  ])
}

export function findCategoryName(tree: Category[] | undefined, id?: string): string | undefined {
  return flattenCategories(tree).find((c) => c.category.id === id)?.category.name
}

// ---- route params <-> query ------------------------------------------------------------------

type RawParams = Record<string, string | string[] | undefined>

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) || undefined

const toInt = (v: string | string[] | undefined) => {
  const s = first(v)
  return s && /^\d{1,15}$/.test(s) ? Number(s) : undefined
}

const SORT_KEYS = SORT_OPTIONS.map((o) => o.key)

export function parseQuery(params: RawParams): ProductQuery {
  const sort = first(params.sort)
  return {
    keyword: first(params.keyword)?.trim() || undefined,
    categoryId: first(params.categoryId),
    minPrice: toInt(params.minPrice),
    maxPrice: toInt(params.maxPrice),
    sort: SORT_KEYS.includes(sort as SortKey) ? (sort as SortKey) : 'RELEVANCE',
  }
}

export function toParams(query: ProductQuery): Record<string, string> {
  const out: Record<string, string> = {}
  if (query.keyword) out.keyword = query.keyword
  if (query.categoryId) out.categoryId = query.categoryId
  if (query.minPrice !== undefined) out.minPrice = String(query.minPrice)
  if (query.maxPrice !== undefined) out.maxPrice = String(query.maxPrice)
  if (query.sort && query.sort !== 'RELEVANCE') out.sort = query.sort
  return out
}

export function activeFilterCount(query: ProductQuery): number {
  return [query.categoryId, query.minPrice, query.maxPrice].filter((v) => v !== undefined).length
}

// ---- variants --------------------------------------------------------------------------------

export const variantLabel = (v: Variant) => Object.values(v.attributes).join(' · ')

export const hasVariantOptions = (variants: Variant[]) =>
  variants.length > 1 || variants.some((v) => Object.keys(v.attributes).length > 0)

export const defaultVariant = (variants: Variant[]): Variant | undefined =>
  variants.find((v) => v.availableStock > 0) ?? variants[0]

/** attribute key -> ordered unique values across all variants. */
export function attributeAxes(variants: Variant[]): Record<string, string[]> {
  const axes: Record<string, string[]> = {}
  for (const v of variants) {
    for (const [key, value] of Object.entries(v.attributes)) {
      const list = (axes[key] ??= [])
      if (!list.includes(value)) list.push(value)
    }
  }
  return axes
}

const matchesOtherAxes = (v: Variant, current: Variant | undefined, key: string) =>
  Object.entries(current?.attributes ?? {}).every(
    ([k, val]) => k === key || v.attributes[k] === val,
  )

/** Variant after the user picks `value` for attribute `key`, keeping the other axes if possible. */
export function pickVariant(
  variants: Variant[],
  current: Variant | undefined,
  key: string,
  value: string,
): Variant | undefined {
  const withValue = variants.filter((v) => v.attributes[key] === value)
  const keepOthers = withValue.filter((v) => matchesOtherAxes(v, current, key))
  return keepOthers.find((v) => v.availableStock > 0) ?? keepOthers[0] ?? withValue[0]
}

/** Whether the combination "current with key=value" has stock. */
export function axisValueInStock(
  variants: Variant[],
  current: Variant | undefined,
  key: string,
  value: string,
): boolean {
  return variants.some(
    (v) => v.attributes[key] === value && v.availableStock > 0 && matchesOtherAxes(v, current, key),
  )
}
