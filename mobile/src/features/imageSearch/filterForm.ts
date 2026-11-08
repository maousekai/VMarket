import type { Category } from '@/api/imageSearch'

export interface CategoryOption {
  id: string
  name: string
  depth: number
}

/** Depth-first flatten of the category tree, skipping inactive branches. */
export function flattenCategories(tree: Category[], depth = 0): CategoryOption[] {
  return tree
    .filter((c) => c.active !== false)
    .flatMap((c) => [
      { id: c.id, name: c.name, depth },
      ...flattenCategories(c.children ?? [], depth + 1),
    ])
}

/** '1250000' -> '1.250.000'; strips everything that is not a digit. */
export function formatPriceInput(text: string): string {
  const digits = text
    .replace(/\D/g, '')
    .replace(/^0+(?=\d)/, '')
    .slice(0, 12)
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
}

/** '1.250.000' -> 1250000, '' -> undefined. */
export function parsePriceInput(text: string): number | undefined {
  const digits = text.replace(/\D/g, '')
  return digits ? Number(digits) : undefined
}
