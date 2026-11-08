import { AuthStore } from '@/auth/session'
import { secureStorage } from '@/lib/storage'

/** A status write whose result is unknown (connection lost); resumed after restart. */
export interface PendingWrite {
  /** What was being saved. */
  kind: 'cod' | 'failed'
  assignmentId: string
  expectedVersion: number
  /** FAILED only: the exact `reason` string that was sent. */
  reason?: string
  /** DELIVERED only: the confirmed COD amount. */
  amount?: number
  /** Attempt the write was made on (decides what follows a successful FAILED). */
  attempt: number
}

/** A stored write is still relevant while the server is at (or one step past) its version. */
export function isLivePending(
  pending: PendingWrite | null | undefined,
  a: { id: string; version: number } | undefined,
): pending is PendingWrite {
  return (
    !!pending &&
    !!a &&
    pending.assignmentId === a.id &&
    (a.version === pending.expectedVersion || a.version === pending.expectedVersion + 1)
  )
}

/** Scoped by account so a shared phone never resumes another shipper's write. */
const key = (kind: PendingWrite['kind'], orderId: string) =>
  `shipper.pending.${AuthStore.getSnapshot().user?.userId ?? 'anon'}.${kind}.${orderId}`
/** SecureStore cannot enumerate keys: remember ours so logout can wipe them. */
const INDEX_KEY = 'shipper.pending.index'

async function readIndex(): Promise<string[]> {
  try {
    const raw = await secureStorage.getItem(INDEX_KEY)
    const list = raw ? (JSON.parse(raw) as unknown) : []
    return Array.isArray(list) ? list.filter((k): k is string => typeof k === 'string') : []
  } catch {
    return []
  }
}

async function updateIndex(change: (list: string[]) => string[]): Promise<void> {
  try {
    const next = change(await readIndex())
    if (next.length) await secureStorage.setItem(INDEX_KEY, JSON.stringify(next))
    else await secureStorage.removeItem(INDEX_KEY)
  } catch {
    // best effort
  }
}

export async function loadPending(
  kind: PendingWrite['kind'],
  orderId: string,
): Promise<PendingWrite | null> {
  try {
    const raw = await secureStorage.getItem(key(kind, orderId))
    return raw ? (JSON.parse(raw) as PendingWrite) : null
  } catch {
    return null
  }
}

export async function savePending(orderId: string, write: PendingWrite): Promise<void> {
  try {
    const k = key(write.kind, orderId)
    await secureStorage.setItem(k, JSON.stringify(write))
    await updateIndex((list) => (list.includes(k) ? list : [...list, k]))
  } catch {
    // Storage is best effort: the in-memory state still protects the current session.
  }
}

export async function clearPending(kind: PendingWrite['kind'], orderId: string): Promise<void> {
  try {
    const k = key(kind, orderId)
    await secureStorage.removeItem(k)
    await updateIndex((list) => list.filter((x) => x !== k))
  } catch {
    // ignore
  }
}

/** Wipe every stored pending write (sign-out). */
export async function clearAllPending(): Promise<void> {
  const keys = await readIndex()
  await Promise.all(keys.map((k) => secureStorage.removeItem(k).catch(() => {})))
  await secureStorage.removeItem(INDEX_KEY).catch(() => {})
}

// A deliberate sign-out / suspension drops them; an expired session keeps them (they are
// account-scoped, and the same shipper signing back in must still resume an unconfirmed write).
AuthStore.addBeforeClear((_token, reason) => {
  if (reason !== 'expired') void clearAllPending()
})
