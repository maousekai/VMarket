// Phep tinh tren CartResponse{groups[{shopId, items[], subtotal}], totalQuantity, totalAmount}.

export const lineKey = (item) => `${item.productId}::${item.variantId || ''}`

/** Tinh lai tong sau khi doi so luong tai cho (optimistic), cung cong thuc voi Cart Service. */
export function withQuantity(cart, key, quantity) {
  if (!cart) return cart
  const groups = cart.groups
    .map((group) => {
      const items = group.items
        .map((item) =>
          lineKey(item) === key
            ? { ...item, quantity, lineTotal: Number(item.unitPrice) * quantity }
            : item,
        )
        .filter((item) => item.quantity > 0)
      return {
        ...group,
        items,
        subtotal: items.reduce((sum, item) => sum + Number(item.lineTotal), 0),
      }
    })
    .filter((group) => group.items.length > 0)
  return {
    ...cart,
    groups,
    totalQuantity: groups.reduce(
      (sum, group) => sum + group.items.reduce((count, item) => count + item.quantity, 0),
      0,
    ),
    totalAmount: groups.reduce((sum, group) => sum + group.subtotal, 0),
  }
}
