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
    pending.forEach((timer) => clearTimeout(timer.id))
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

  const removeItem = useCallback(
    async (item) => {
      const key = lineKey(item)
      const timer = timers.current.get(key)
      if (timer) {
        clearTimeout(timer.id)
        timers.current.delete(key)
      }
      setState((current) => ({ ...current, cart: withQuantity(current.cart, key, 0) }))
      try {
        return apply(await removeCartItem(item.productId, item.variantId))
      } catch (error) {
        setState((current) => ({ ...current, cart: confirmed.current }))
        throw error
      }
    },
    [apply],
  )

  /**
   * Doi so luong ngay tren giao dien, gom cac lan bam lien tiep thanh mot PATCH. Loi thi tra
   * lai trang thai server da xac nhan va bao qua `onError`.
   */
  const setQuantity = useCallback((item, quantity, { onError } = {}) => {
    const key = lineKey(item)
    setState((current) => ({ ...current, cart: withQuantity(current.cart, key, quantity) }))
    const previous = timers.current.get(key)
    if (previous) clearTimeout(previous.id)
    const ticket = generation.current
    const id = setTimeout(async () => {
      try {
        const cart = await updateCartItemQuantity(item.productId, item.variantId, quantity)
        if (timers.current.get(key)?.id !== id || ticket !== generation.current) return
        timers.current.delete(key)
        confirmed.current = cart
        if (timers.current.size === 0) setState({ status: 'ready', cart, error: null })
      } catch (error) {
        if (timers.current.get(key)?.id !== id) return
        timers.current.delete(key)
        setState((current) => ({ ...current, cart: confirmed.current }))
        onError?.(getApiError(error))
      }
    }, QUANTITY_DEBOUNCE_MS)
    timers.current.set(key, { id })
  }, [])

  /** Cho cac PATCH dang hen gio chay xong (vd truoc khi kiem tra gio de thanh toan). */
  const flush = useCallback(async () => {
    for (let attempt = 0; attempt < 20 && timers.current.size > 0; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, QUANTITY_DEBOUNCE_MS / 2))
    }
  }, [])

  useEffect(() => {
    const pending = timers.current
    return () => pending.forEach((timer) => clearTimeout(timer.id))
  }, [])

  const value = useMemo(
    () => ({
      ...state,
      enabled: Boolean(userId),
      totalQuantity: state.cart?.totalQuantity ?? 0,
      refresh,
      addItem,
      removeItem,
      setQuantity,
      flush,
    }),
    [state, userId, refresh, addItem, removeItem, setQuantity, flush],
  )
  return <CartContext.Provider value={value}>{children}</CartContext.Provider>
}
