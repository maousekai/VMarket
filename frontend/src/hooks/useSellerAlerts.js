import { useEffect, useState } from 'react'
import { listSellerOrders, listSellerReturns } from '../services/api/orderApi'

const POLL_MS = 60_000
export const SELLER_ALERTS_EVENT = 'vmarket:seller-alerts'

/** Bao sidebar tai lai cham do ngay sau khi seller xu ly don / yeu cau tra hang. */
export const refreshSellerAlerts = () => window.dispatchEvent(new Event(SELLER_ALERTS_EVENT))

const total = (query, load) =>
  load({ ...query, page: 0, size: 1 })
    .then((page) => page.totalElements)
    .catch(() => 0)

/**
 * So viec seller can lam: don cho xac nhan / cho ban giao va yeu cau tra hang cho phan hoi.
 * Tai lai moi phut, khi tab duoc mo lai va khi trang don hang bao vua xu ly xong.
 */
export function useSellerAlerts(enabled = true) {
  const [alerts, setAlerts] = useState({ orders: 0, returns: 0 })

  useEffect(() => {
    if (!enabled) return undefined
    let current = true
    const load = async () => {
      const [pending, processing, returns] = await Promise.all([
        total({ status: 'PENDING' }, listSellerOrders),
        total({ status: 'PROCESSING' }, listSellerOrders),
        total({ status: 'PENDING' }, listSellerReturns),
      ])
      if (current) setAlerts({ orders: pending + processing, returns })
    }
    const onVisible = () => document.visibilityState === 'visible' && load()
    load()
    const timer = setInterval(load, POLL_MS)
    window.addEventListener(SELLER_ALERTS_EVENT, load)
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      current = false
      clearInterval(timer)
      window.removeEventListener(SELLER_ALERTS_EVENT, load)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [enabled])

  return alerts
}
