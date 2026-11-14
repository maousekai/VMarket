import { useEffect, useRef } from 'react'

// Su kien trong trang de chuong thong bao va cac trang dang mo noi chuyen voi nhau.
export const NOTIFICATION_ARRIVED_EVENT = 'vmarket:notification-arrived'
export const NOTIFICATIONS_CHANGED_EVENT = 'vmarket:notifications-changed'

/** Chuong phat hien co thong bao moi (so chua doc tang). */
export const announceNotificationArrived = () =>
  window.dispatchEvent(new Event(NOTIFICATION_ARRIVED_EVENT))

/** Trang thong bao vua danh dau da doc: chuong tai lai so chua doc. */
export const announceNotificationsChanged = () =>
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED_EVENT))

/** Goi `reload` moi khi co thong bao moi (vd trang chi tiet don tu cap nhat trang thai). */
export function useReloadOnNotification(reload) {
  const latest = useRef(reload)
  useEffect(() => {
    latest.current = reload
  })
  useEffect(() => {
    const handler = () => latest.current?.()
    window.addEventListener(NOTIFICATION_ARRIVED_EVENT, handler)
    return () => window.removeEventListener(NOTIFICATION_ARRIVED_EVENT, handler)
  }, [])
}
