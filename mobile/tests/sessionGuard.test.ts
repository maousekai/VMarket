import assert from 'node:assert/strict'
import { test } from 'node:test'

import { decideReplay } from '../src/api/sessionGuard.ts'

test('replays after a refresh in the same session', () => {
  assert.equal(
    decideReplay({ issuedGeneration: 3, currentGeneration: 3, authenticated: true }),
    'replay',
  )
})

test("A signs out and B signs in while the refresh is pending: A's request is NOT replayed", () => {
  // B is authenticated, but the request was issued in A's generation.
  assert.equal(
    decideReplay({ issuedGeneration: 3, currentGeneration: 4, authenticated: true }),
    'reject',
  )
})

test('rejects when the session ended (refresh failed or signed out)', () => {
  assert.equal(
    decideReplay({ issuedGeneration: 3, currentGeneration: 3, authenticated: false }),
    'reject',
  )
  assert.equal(
    decideReplay({ issuedGeneration: 3, currentGeneration: 4, authenticated: false }),
    'reject',
  )
})

test('rejects a request that carries no generation', () => {
  assert.equal(
    decideReplay({ issuedGeneration: undefined, currentGeneration: 0, authenticated: true }),
    'reject',
  )
})
