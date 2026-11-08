import { ImageManipulator, SaveFormat } from 'expo-image-manipulator'
import * as ImagePicker from 'expo-image-picker'

export interface PickedImage {
  uri: string
  width: number
}

const MAX_WIDTH = 800
/** Encoded size cap per image (base64 chars, about 300 KB of JPEG). */
const MAX_ENCODED_CHARS = 400_000

export class ImageTooLargeError extends Error {
  constructor() {
    super('IMAGE_TOO_LARGE')
  }
}

/** Opens the gallery (images only) and returns the picked files; [] when cancelled. */
export async function pickImages(limit: number): Promise<PickedImage[]> {
  const res = await ImagePicker.launchImageLibraryAsync({
    mediaTypes: ['images'],
    allowsMultipleSelection: true,
    selectionLimit: limit,
    quality: 1,
  })
  return res.canceled ? [] : res.assets.map((a) => ({ uri: a.uri, width: a.width }))
}

/**
 * The backend has no upload API (gap G4) and stores `evidenceUrls` verbatim, so images are
 * downscaled/compressed and sent as data URIs. Swap this for a real upload when one exists.
 */
export async function encodeImage(image: PickedImage): Promise<string> {
  let context = ImageManipulator.manipulate(image.uri)
  if (image.width > MAX_WIDTH) context = context.resize({ width: MAX_WIDTH })
  const rendered = await context.renderAsync()
  const out = await rendered.saveAsync({ compress: 0.5, format: SaveFormat.JPEG, base64: true })
  if (!out.base64 || out.base64.length > MAX_ENCODED_CHARS) throw new ImageTooLargeError()
  return `data:image/jpeg;base64,${out.base64}`
}

export const encodeImages = (images: PickedImage[]) => Promise.all(images.map(encodeImage))
