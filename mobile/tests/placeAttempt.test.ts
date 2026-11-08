import assert from 'node:assert/strict'
import { test } from 'node:test'

import {
  ATTEMPT_WINDOW_MS,
  attemptSignature,
  resolveAttempt,
  type PlaceAttempt,
} from '../src/features/checkout/placeAttempt.ts'

const sig = attemptSignature('user-1', 'addr-1', 'PAYOS')
let n = 0
const nextKey = () => `key-${++n}`

test('first press starts a new attempt and is not a retry', () => {
  const { attempt, retry } = resolveAttempt(null, sig, 1_000, nextKey)
  assert.equal(retry, false)
  assert.equal(attempt.signature, sig)
  assert.equal(attempt.at, 1_000)
})

test('lost response: the retry reuses the SAME key and skips cart validation (the cart is already empty)', () => {
  const first = resolveAttempt(null, sig, 1_000, nextKey)
  // Later the buyer taps again; nothing about the (now empty) cart takes part in the decision.
  const second = resolveAttempt(first.attempt, sig, 1_000 + 30_000, nextKey)
  assert.equal(second.retry, true)
  assert.equal(second.attempt.key, first.attempt.key)
  assert.equal(second.attempt.at, 1_000, 'the retry window stays anchored to the first press')
})

test('a different address / payment method / user is a new action with a new key', () => {
  const prev: PlaceAttempt = { key: 'old', signature: sig, at: 0 }
  for (const other of [
    attemptSignature('user-1', 'addr-2', 'PAYOS'),
    attemptSignature('user-1', 'addr-1', 'COD'),
    attemptSignature('user-2', 'addr-1', 'PAYOS'),
  ]) {
    const { attempt, retry } = resolveAttempt(prev, other, 10, nextKey)
    assert.equal(retry, false)
    assert.notEqual(attempt.key, 'old')
  }
})

test('an attempt older than the window is dropped', () => {
  const prev: PlaceAttempt = { key: 'old', signature: sig, at: 0 }
  const { attempt, retry } = resolveAttempt(prev, sig, ATTEMPT_WINDOW_MS, nextKey)
  assert.equal(retry, false)
  assert.notEqual(attempt.key, 'old')
  const inside = resolveAttempt(prev, sig, ATTEMPT_WINDOW_MS - 1, nextKey)
  assert.equal(inside.retry, true)
})
