import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import RULES from './delivery-rules.json'

const here = dirname(fileURLToPath(import.meta.url))
const shared = resolve(here, '../../../shared/delivery/delivery-rules.json')

// Ban sao cua frontend phai trung nguon chung (chay `node scripts/sync-delivery-rules.mjs`).
describe('delivery-rules.json', () => {
  it('matches shared/delivery/delivery-rules.json', () => {
    expect(RULES).toEqual(JSON.parse(readFileSync(shared, 'utf8')))
  })
})
