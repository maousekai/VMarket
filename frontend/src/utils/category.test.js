import { describe, expect, it } from 'vitest'
import { descendantIds, findNode, findPath, flattenTree, sortTree } from './category'

const tree = [
  {
    id: 'b',
    name: 'Điện máy',
    sortOrder: 2,
    children: [{ id: 'b1', name: 'Tivi', sortOrder: 0, children: [] }],
  },
  {
    id: 'a',
    name: 'Thời trang',
    sortOrder: 1,
    children: [
      {
        id: 'a2',
        name: 'Nữ',
        sortOrder: 2,
        children: [{ id: 'a2x', name: 'Váy', sortOrder: 0, children: [] }],
      },
      { id: 'a1', name: 'Nam', sortOrder: 1, children: [] },
    ],
  },
]

describe('category tree helpers', () => {
  it('sorts every level by sortOrder', () => {
    const sorted = sortTree(tree)
    expect(sorted.map((node) => node.id)).toEqual(['a', 'b'])
    expect(sorted[0].children.map((node) => node.id)).toEqual(['a1', 'a2'])
  })

  it('flattens in display order with depth', () => {
    expect(flattenTree(tree).map((node) => `${node.depth}:${node.id}`)).toEqual([
      '0:a',
      '1:a1',
      '1:a2',
      '2:a2x',
      '0:b',
      '1:b1',
    ])
  })

  it('finds the path from the root to a nested category', () => {
    expect(findPath(tree, 'a2x').map((node) => node.id)).toEqual(['a', 'a2', 'a2x'])
    expect(findPath(tree, 'missing')).toEqual([])
    expect(findNode(tree, 'b1').name).toBe('Tivi')
  })

  it('collects a category and all of its descendants', () => {
    expect([...descendantIds(findNode(tree, 'a'))].sort()).toEqual(['a', 'a1', 'a2', 'a2x'])
  })
})
