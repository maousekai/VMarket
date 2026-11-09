// Helpers cho cay danh muc nhieu cap tu GET /api/products/categories
// (CategoryResponse{id, name, parentId, active, sortOrder, children[]}).

const bySortOrder = (a, b) =>
  (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || String(a.name).localeCompare(String(b.name), 'vi')

/** Sap xep de quy theo sortOrder; tra ve cay moi, khong sua du lieu goc. */
export function sortTree(nodes = []) {
  return [...(Array.isArray(nodes) ? nodes : [])]
    .sort(bySortOrder)
    .map((node) => ({ ...node, children: sortTree(node.children) }))
}

/** Danh sach phang theo thu tu duyet cay, kem do sau (0 = cap 1). */
export function flattenTree(nodes = [], depth = 0, out = []) {
  for (const node of sortTree(nodes)) {
    out.push({ ...node, depth })
    flattenTree(node.children, depth + 1, out)
  }
  return out
}

/** Duong dan tu goc toi danh muc `id` (gom chinh no); rong khi khong tim thay. */
export function findPath(nodes = [], id) {
  if (!id) return []
  for (const node of nodes || []) {
    if (node.id === id) return [node]
    const inner = findPath(node.children, id)
    if (inner.length) return [node, ...inner]
  }
  return []
}

export function findNode(nodes = [], id) {
  const path = findPath(nodes, id)
  return path[path.length - 1] || null
}

/** Id cua danh muc va moi danh muc con chau (dung khi chon cha cho danh muc). */
export function descendantIds(node) {
  if (!node) return new Set()
  const ids = new Set([node.id])
  for (const child of node.children || []) {
    for (const id of descendantIds(child)) ids.add(id)
  }
  return ids
}
