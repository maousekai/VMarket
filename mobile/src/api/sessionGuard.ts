/**
 * Whether a request that got a 401 may be replayed after the session refresh.
 * `issued` is the auth generation the request was sent in, `current` the generation now (it
 * changes on sign-out and when another account signs in).
 */
export type ReplayDecision = 'replay' | 'reject'

export function decideReplay(input: {
  issuedGeneration: number | undefined
  currentGeneration: number
  authenticated: boolean
}): ReplayDecision {
  // A request must never be replayed with another user's token (A signs out, B signs in while
  // A's refresh is pending): that would run A's action against B's data.
  if (input.issuedGeneration !== input.currentGeneration) return 'reject'
  return input.authenticated ? 'replay' : 'reject'
}
