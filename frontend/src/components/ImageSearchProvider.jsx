import { useMemo, useState } from 'react'
import ImageSearchDialog from './ImageSearchDialog'
import { ImageSearchContext } from './imageSearchContext'

/**
 * Bao khu mua sam: cung cap `open()` cho nut camera trong o tim kiem (diem vao duy nhat cua
 * tim bang anh). Hop thoai chi mount khi dang mo.
 */
export default function ImageSearchProvider({ children }) {
  const [open, setOpen] = useState(false)
  const value = useMemo(() => ({ open: () => setOpen(true) }), [])
  return (
    <ImageSearchContext.Provider value={value}>
      {children}
      {open && <ImageSearchDialog onClose={() => setOpen(false)} />}
    </ImageSearchContext.Provider>
  )
}
