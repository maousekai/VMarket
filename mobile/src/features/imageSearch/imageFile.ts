import { ImageManipulator, SaveFormat } from 'expo-image-manipulator'

import { MAX_IMAGE_BYTES } from '@/api/imageSearch'
import type { SelectedImage } from '@/features/imageSearch/ImageSearchContext'

/** The server downsizes to 256 px anyway; this keeps uploads small and fast. */
const MAX_LONG_EDGE = 1600
const JPEG_QUALITY = 0.8

let counter = 0

export const isTooLarge = (image: Pick<SelectedImage, 'size'>) => image.size > MAX_IMAGE_BYTES

/**
 * Re-encodes any picked/captured photo as an upright JPEG (also converts HEIC, PNG with alpha,
 * etc.) whose long edge is at most 1600 px. Works on native and web.
 */
export async function prepareImage(source: {
  uri: string
  width: number
  height: number
}): Promise<SelectedImage> {
  const context = ImageManipulator.manipulate(source.uri)
  try {
    if (Math.max(source.width, source.height) > MAX_LONG_EDGE) {
      context.resize(
        source.width >= source.height ? { width: MAX_LONG_EDGE } : { height: MAX_LONG_EDGE },
      )
    }
    const rendered = await context.renderAsync()
    try {
      const saved = await rendered.saveAsync({ format: SaveFormat.JPEG, compress: JPEG_QUALITY })
      const size = (await (await fetch(saved.uri)).blob()).size
      counter += 1
      return { id: counter, uri: saved.uri, width: saved.width, height: saved.height, size }
    } finally {
      rendered.release()
    }
  } finally {
    context.release()
  }
}

/** 2400000 -> '2,4 MB', 3000000 -> '3 MB', 20000 -> '0,1 MB'. */
export function formatMegabytes(bytes: number): string {
  const mb = Math.max(0.1, Math.round((bytes / 1_000_000) * 10) / 10)
  return `${String(mb).replace('.', ',').replace(/,0$/, '')} MB`
}
