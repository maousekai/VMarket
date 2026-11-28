import { useSyncExternalStore } from 'react'

// Lua chon khi dat hang (dia chi nhan, phuong thuc thanh toan) dung chung giua Gio hang, So dia chi
// va man xac nhan dat hang - khong truyen qua tham so route.
let state = { addressId: null, paymentMethod: 'VNPAY' }
const listeners = new Set()

export function setCheckout(changes) {
  state = { ...state, ...changes }
  listeners.forEach((listener) => listener())
}

export function getCheckout() {
  return state
}

export function resetCheckoutForTests() {
  state = { addressId: null, paymentMethod: 'VNPAY' }
}

const subscribe = (listener) => {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function useCheckout() {
  return useSyncExternalStore(subscribe, getCheckout, getCheckout)
}
