import { createContext, useContext } from 'react'

// Mo hop thoai "Tìm bằng ảnh" tu bat ky dau trong khu mua sam (o tim kiem, nut noi).
// Ngoai ImageSearchProvider (vd test render rieng header) gia tri la null: an nut camera.
export const ImageSearchContext = createContext(null)

export const useImageSearch = () => useContext(ImageSearchContext)
