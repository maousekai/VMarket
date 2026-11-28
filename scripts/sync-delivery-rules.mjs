#!/usr/bin/env node
// Sao chep shared/delivery/delivery-rules.json toi moi noi dung no (review PR 68/69: mot nguon).
// Chay lai sau moi lan sua file goc; test o tung noi se fail neu ban sao lech ban goc.
import { copyFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const source = join(root, 'shared/delivery/delivery-rules.json')
const targets = [
  'frontend/src/generated/delivery-rules.json',
  'mobile/src/generated/delivery-rules.json',
  'services/delivery-service/src/main/resources/delivery-rules.json',
]
for (const target of targets) {
  const path = join(root, target)
  mkdirSync(dirname(path), { recursive: true })
  copyFileSync(source, path)
  console.log(`synced ${target}`)
}
