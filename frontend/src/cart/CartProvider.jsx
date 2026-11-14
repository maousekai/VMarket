import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { getApiError } from '../services/api/apiClient'
import {
  addCartItem,
  getCart,
  removeCartItem,
  updateCartItemQuantity,
} from '../services/api/cartApi'
import { lineKey, withQuantity } from './cartMath'
import { CartContext } from './useCart'

const QUANTITY_DEBOUNCE_MS = 400

/**
 * Gio hang dung chung cho header, trang chi tiet va trang gio. Gio nam tren Cart Service nen
 * web va mobile cung mot du lieu: tai lai khi doi tai khoan va moi khi tab duoc mo lai.
 */
export function CartProvider({ children }) {
  const { session } = useAuth()
  const userId = session?.status === 'ACTIVE' ? session.userId : null
  const [state, setState] = useState({ status: 'idle', cart: null, error: null })
  const confirmed = useRef(null)
  const timers = useRef(new Map())
  const generation = useRef(0)

  const apply = useCallback((cart) => {
    confirmed.current = cart
    setState({ status: 'ready', cart, error: null })
    return cart
  }, [])

  const refresh = useCallback(async () => {
    if (!userId) return null
    const ticket = generation.current
    try {
      const cart = await getCart()
      if (ticket === generation.current && timers.current.size === 0) apply(cart)
      return cart
    } catch (error) {
      if (ticket === generation.current)
        setState((current) => ({ ...current, status: current.cart ? 'ready' : 'error', error }))
      throw error
    }
  }, [userId, apply])

  useEffect(() => {
    generation.current += 1
    const pending = timers.current
    pending.forEach((timer) => {
      if (!timer.promise) clearTimeout(timer.id)
    })
    pending.clear()
    confirmed.current = null
    if (!userId) {
      setState({ status: 'idle', cart: null, error: null })
      return undefined
    }
    setState({ status: 'loading', cart: null, error: null })
    refresh().catch(() => {})
    const onVisible = () => {
      if (document.visibilityState === 'visible') refresh().catch(() => {})
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', onVisible)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', onVisible)
    }
  }, [userId, refresh])

  const addItem = useCallback(async (item) => apply(await addCartItem(item)), [apply])

  /** Huy PATCH so luong chua gui cua dong `key` (dong sap bi xoa). */
  const dropPending = useCallback((key) => {
    const pending = timers.current.get(key)
    if (!pending) return
    if (!pending.promise) clearTimeout(pending.id)
    timers.current.delete(key)
  }, [])

  const removeItem = useCallback(
    async (item) => {
      const key = lineKey(item)
      dropPending(key)
      setState((current) => ({ ...current, cart: withQuantity(current.cart, key, 0) }))
      try {
        return apply(await removeCartItem(item.productId, item.variantId))
      } catch (error) {
        setState((current) => ({ ...current, cart: confirmed.current }))
        throw error
      }
    },
    [apply, dropPending],
  )

  /**
   * Xoa nhieu dong: an ngay tren giao dien, gui DELETE lan luot va chi ap dung gio cua response
   * cuoi cung, de response cu ve sau khong lam hien lai dong vua bi xoa. Loi thi tai lai gio
   * tu server roi bao loi dau tien.
   */
  const removeItems = useCallback(
    async (items) => {
      const keys = items.map(lineKey)
      keys.forEach(dropPending)
      setState((current) => ({
        ...current,
        cart: keys.reduce((cart, key) => withQuantity(cart, key, 0), current.cart),
      }))
      let latest = null
      let failure = null
      for (const item of items) {
        try {
          latest = await removeCartItem(item.productId, item.variantId)
        } catch (error) {
          failure ??= error
        }
      }
      if (failure) {
        await refresh().catch(() =>
          setState((current) => ({ ...current, cart: confirmed.current })),
        )
        throw failure
      }
      if (latest) apply(latest)
      return latest
    },
    [apply, dropPending, refresh],
  )

  /**
   * Doi so luong ngay tren giao dien, gom cac lan bam lien tiep thanh mot PATCH. Loi thi tra
   * lai trang thai server da xac nhan va bao qua `onError`.
   */
  const setQuantity = useCallback((item, quantity, { onError } = {}) => {
    const key = lineKey(item)
    setState((current) => ({ ...current, cart: withQuantity(current.cart, key, quantity) }))
    const previous = timers.current.get(key)
    if (previous && !previous.promise) clearTimeout(previous.id)
    const ticket = generation.current
    const entry = {}
    entry.run = async () => {
      // PATCH cua cung dong chay lan luot: PATCH cu toi server sau khong de len so luong moi.
      await previous?.promise?.catch(() => {})
      try {
        const cart = await updateCartItemQuantity(item.productId, item.variantId, quantity)
        if (timers.current.get(key) !== entry || ticket !== generation.current) return
        timers.current.delete(key)
        confirmed.current = cart
        if (timers.current.size === 0) setState({ status: 'ready', cart, error: null })
      } catch (error) {
        if (timers.current.get(key) !== entry) return
        timers.current.delete(key)
        setState((current) => ({ ...current, cart: confirmed.current }))
        onError?.(getApiError(error))
        throw error
      }
    }
    entry.start = () => {
      if (!entry.promise) {
        entry.promise = entry.run()
        entry.promise.catch(() => {})
      }
      return entry.promise
    }
    entry.id = setTimeout(entry.start, QUANTITY_DEBOUNCE_MS)
    timers.current.set(key, entry)
  }, [])

  /**
   * Gui ngay cac PATCH dang hen gio va cho tat ca xong (truoc khi kiem tra gio de thanh toan).
   * Neu mot PATCH loi hoac het thoi gian cho thi nem loi de chan buoc thanh toan.
   */
  const flush = useCallback(async () => {
    const pending = [...timers.current.values()]
    pending.forEach((entry) => {
      if (!entry.promise) clearTimeout(entry.id)
    })
    const results = await Promise.allSettled(pending.map((entry) => entry.start()))
    const failed = results.find((result) => result.status === 'rejected')
    if (failed) throw failed.reason
  }, [])

  useEffect(() => {
    const pending = timers.current
    return () =>
      pending.forEach((timer) => {
        if (!timer.promise) clearTimeout(timer.id)
      })
  }, [])

  const value = useMemo(
    () => ({
      ...state,
      enabled: Boolean(userId),
      totalQuantity: state.cart?.totalQuantity ?? 0,
      refresh,
      addItem,
      removeItem,
      removeItems,
      setQuantity,
      flush,
    }),
    [state, userId, refresh, addItem, removeItem, removeItems, setQuantity, flush],
  )
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}
