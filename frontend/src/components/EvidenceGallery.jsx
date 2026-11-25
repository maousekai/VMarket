import { imageUrlOrEmpty, isVideoUrl } from '../utils/shop'

/** Ảnh / video minh chứng trả hàng; bấm để mở bản gốc ở tab mới. */
export default function EvidenceGallery({ urls = [], className = '' }) {
  const items = urls.filter((url) => imageUrlOrEmpty(url))
  if (!items.length) return null
  return (
    <div className={`order-return__evidence ${className}`.trim()}>
      {items.map((url) =>
        isVideoUrl(url) ? (
          <video key={url} src={url} controls preload="metadata" aria-label="Video minh chứng" />
        ) : (
          <a key={url} href={url} target="_blank" rel="noreferrer">
            <img src={url} alt="Ảnh minh chứng" />
          </a>
        ),
      )}
    </div>
  )
}
