/**
 * The Idempotency-Key of "the user pressed Đặt hàng". It identifies the ACTION, not the cart:
 * when the response of a successful POST is lost, the server has already created the orders and
 * emptied the cart, so a retry must go back to the server with the same key (the backend replays
 * the stored orders) instead of first judging the now-empty cart.
 */
export interface PlaceAttempt {
  key: string
  signature: string
  /** When the attempt started; the retry window is measured from here. */
  at: number
}

/** How long an attempt whose outcome is unknown may be retried with the same key. */
export const ATTEMPT_WINDOW_MS = 10 * 60_000

/** Same user + address + payment method = the same checkout action. */
export const attemptSignature = (userId: string, addressId: string, paymentMethod: string) =>
  [userId, addressId, paymentMethod].join('|')

/**
 * Reuses the stored attempt (`retry: true`) while it matches and is still inside the window;
 * otherwise starts a new one with a fresh key.
 */
export function resolveAttempt(
  prev: PlaceAttempt | null,
  signature: string,
  now: number,
  newKey: () => string,
): { attempt: PlaceAttempt; retry: boolean } {
  if (prev && prev.signature === signature && now - prev.at < ATTEMPT_WINDOW_MS) {
    return { attempt: prev, retry: true }
  }
  return { attempt: { key: newKey(), signature, at: now }, retry: false }
}
