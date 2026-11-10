import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'

import type { ImageSearchFilter } from '@/api/imageSearch'

/** A photo already normalised to JPEG and ready to upload. */
export interface SelectedImage {
  /** Changes for every new photo; part of the search query key. */
  id: number
  uri: string
  width: number
  height: number
  /** Bytes of the prepared file. */
  size: number
}

interface ImageSearchState {
  image: SelectedImage | null
  setImage: (image: SelectedImage | null) => void
  filter: ImageSearchFilter
  setFilter: (filter: ImageSearchFilter) => void
  reset: () => void
}

const Ctx = createContext<ImageSearchState | null>(null)

/** Keeps the chosen photo and filter alive across the image-search screens. */
export function ImageSearchProvider({ children }: { children: ReactNode }) {
  const [image, setImage] = useState<SelectedImage | null>(null)
  const [filter, setFilter] = useState<ImageSearchFilter>({})
  // Stable identity: screens call it from focus effects.
  const reset = useCallback(() => {
    setImage(null)
    setFilter({})
  }, [])
  const value = useMemo<ImageSearchState>(
    () => ({ image, setImage, filter, setFilter, reset }),
    [image, filter, reset],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useImageSearch(): ImageSearchState {
  const value = useContext(Ctx)
  if (!value) throw new Error('useImageSearch must be used inside ImageSearchProvider')
  return value
}
