import * as Crypto from 'expo-crypto'

/** RFC4122 v4 UUID. Works on native and web. */
export function uuid(): string {
  return Crypto.randomUUID()
}
