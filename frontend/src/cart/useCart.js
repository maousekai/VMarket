import { createContext, useContext } from 'react'

const noop = async () => null

// Gia tri mac dinh cho component render ngoai CartProvider (test, trang auth).
export const CartContext = createContext({
  status: 'idle',
  cart: null,
  error: null,
  enabled: false,
  totalQuantity: 0,
  refresh: noop,
  addItem: noop,
  removeItem: noop,
  setQuantity: () => {},
  flush: noop,
})

export const useCart = () => useContext(CartContext)
