import { useMemo, useState } from 'react'
import ImageSearchDialog from './ImageSearchDialog'
import { CameraIcon } from './HeaderSearch'
import { ImageSearchContext } from './imageSearchContext'

/**
 * Bao khu mua sam: cung cap `open()` cho nut camera trong o tim kiem va hien nut noi
 * "Tìm bằng ảnh" canh tro ly (Figma trang chu). Hop thoai chi mount khi dang mo.
 */
export default function ImageSearchProvider({ children }) {
  const [open, setOpen] = useState(false)
  const value = useMemo(() => ({ open: () => setOpen(true) }), [])
  return (
    <ImageSearchContext.Provider value={value}>
      {children}
      <button type="button" className="image-search-launcher" onClick={() => setOpen(true)}>
        <CameraIcon />
        Tìm bằng ảnh
      </button>
      {open && <ImageSearchDialog onClose={() => setOpen(false)} />}
    </ImageSearchContext.Provider>
  )
}
