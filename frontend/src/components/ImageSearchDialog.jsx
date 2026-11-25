import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import Modal from './Modal'
import { useTopCategories } from '../hooks/useCatalog'
import { getApiError } from '../services/api/apiClient'
import { SEARCH_LIMITS, searchByImage } from '../services/api/searchApi'
import { formatMoney } from '../utils/formatMoney'
import { formatMegabytes, IMAGE_ACCEPT, isAcceptedImage } from '../utils/imageFile'
import { PRODUCT_SORT } from '../services/api/productApi'
import './search.css'

const MAX_LABEL = formatMegabytes(SEARCH_LIMITS.maxImageBytes)

/** Kiem tra phia client truoc khi upload (UC-21: anh khong hop le hoac vuot 10 MB). */
function checkImageFile(file) {
  if (!file) return { kind: 'invalid' }
  if (!isAcceptedImage(file)) return { kind: 'invalid' }
  if (file.size > SEARCH_LIMITS.maxImageBytes) return { kind: 'tooLarge', size: file.size }
  return null
}

function priceLabel(product) {
  const { minPrice, maxPrice, currency } = product
  if (minPrice == null) return ''
  return minPrice === maxPrice || maxPrice == null
    ? formatMoney(minPrice, currency)
    : `${formatMoney(minPrice, currency)} – ${formatMoney(maxPrice, currency)}`
}

function filterSummary(filters, categories) {
  const from = Number.isFinite(filters.minPrice) ? formatMoney(filters.minPrice) : null
  const to = Number.isFinite(filters.maxPrice) ? formatMoney(filters.maxPrice) : null
  const price = from && to ? `${from} – ${to}` : from ? `từ ${from}` : to ? `đến ${to}` : 'tất cả'
  const category = categories.find((item) => item.id === filters.categoryId)?.name || 'tất cả'
  return `Giá: ${price} · Danh mục: ${category}`
}

const toAmount = (value) => {
  if (value === '' || value == null) return undefined
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? Math.floor(number) : undefined
}

function useObjectUrl(file) {
  const [url, setUrl] = useState('')
  useEffect(() => {
    if (!file || typeof URL.createObjectURL !== 'function') {
      setUrl('')
      return undefined
    }
    const value = URL.createObjectURL(file)
    setUrl(value)
    return () => URL.revokeObjectURL(value)
  }, [file])
  return url
}

function KeywordFallback({ onSearch, label = 'Tìm bằng từ khóa' }) {
  const [keyword, setKeyword] = useState('')
  return (
    <form
      className="image-search__keyword"
      onSubmit={(event) => {
        event.preventDefault()
        if (keyword.trim()) onSearch(keyword.trim())
      }}
    >
      <label className="visually-hidden" htmlFor="image-search-keyword">
        Tên sản phẩm
      </label>
      <input
        id="image-search-keyword"
        type="search"
        placeholder="Nhập tên sản phẩm..."
        maxLength={100}
        value={keyword}
        onChange={(event) => setKeyword(event.target.value)}
      />
      <button type="submit" className="button button--secondary" disabled={!keyword.trim()}>
        {label}
      </button>
    </form>
  )
}

function FilterForm({ filters, categories, onApply, onClear }) {
  const [min, setMin] = useState(filters.minPrice ?? '')
  const [max, setMax] = useState(filters.maxPrice ?? '')
  const [categoryId, setCategoryId] = useState(filters.categoryId || '')
  const invalid = min !== '' && max !== '' && Number(min) > Number(max)
  return (
    <form
      className="image-search__filter"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        if (!invalid) {
          onApply({
            minPrice: toAmount(min),
            maxPrice: toAmount(max),
            categoryId: categoryId || '',
          })
        }
      }}
    >
      <fieldset>
        <legend>Khoảng giá</legend>
        <label>
          <span className="visually-hidden">Giá từ</span>
          <input
            type="number"
            min="0"
            step="1000"
            inputMode="numeric"
            placeholder="Từ 0₫"
            value={min}
            onChange={(event) => setMin(event.target.value)}
          />
        </label>
        <label>
          <span className="visually-hidden">Giá đến</span>
          <input
            type="number"
            min="0"
            step="1000"
            inputMode="numeric"
            placeholder="Đến (không giới hạn)"
            value={max}
            onChange={(event) => setMax(event.target.value)}
          />
        </label>
      </fieldset>
      {invalid && (
        <p className="field-error" role="alert">
          Giá tối thiểu phải nhỏ hơn giá tối đa.
        </p>
      )}
      <label className="image-search__category">
        <span>Danh mục</span>
        <select value={categoryId} onChange={(event) => setCategoryId(event.target.value)}>
          <option value="">Tất cả danh mục</option>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>
              {category.name}
            </option>
          ))}
        </select>
      </label>
      <div className="image-search__actions">
        <button type="submit" className="button button--primary">
          Áp dụng bộ lọc
        </button>
        <button type="button" className="button button--secondary" onClick={onClear}>
          Xóa bộ lọc
        </button>
      </div>
    </form>
  )
}

const EMPTY_FILTERS = { minPrice: undefined, maxPrice: undefined, categoryId: '' }

/**
 * Tim san pham bang hinh anh (UC-21, Figma "Web end-user / UC-21 / *").
 * Anh chi gui len AI Search khi nguoi dung bam tim; server tu xoa anh truy van (NFR-SEC-07).
 */
export default function ImageSearchDialog({ onClose }) {
  const navigate = useNavigate()
  const categoriesState = useTopCategories()
  const categories = categoriesState.status === 'ready' ? categoriesState.data : []
  const fileInput = useRef(null)
  const video = useRef(null)
  const stream = useRef(null)
  const request = useRef(null)
  const [step, setStep] = useState('pick')
  const [file, setFile] = useState(null)
  const [problem, setProblem] = useState(null)
  const [results, setResults] = useState([])
  const [filters, setFilters] = useState(EMPTY_FILTERS)
  const [dragging, setDragging] = useState(false)
  const preview = useObjectUrl(file)

  // Ve cua lan xin camera gan nhat. Doi ve = huy moi lan xin dang cho nguoi dung cap quyen.
  const cameraRequest = useRef(0)

  const stopCamera = useCallback(() => {
    cameraRequest.current += 1
    stream.current?.getTracks().forEach((track) => track.stop())
    stream.current = null
  }, [])

  // Dong hop thoai giua chung: tat camera va huy request dang chay.
  useEffect(
    () => () => {
      stopCamera()
      request.current?.abort()
    },
    [stopCamera],
  )

  function close() {
    stopCamera()
    request.current?.abort()
    onClose()
  }

  function pickFile() {
    stopCamera()
    fileInput.current?.click()
  }

  function accept(candidate) {
    stopCamera()
    const issue = checkImageFile(candidate)
    if (issue) {
      setProblem(issue)
      setStep(issue.kind)
      return
    }
    setFile(candidate)
    setFilters(EMPTY_FILTERS)
    setStep('preview')
  }

  async function openCamera() {
    if (!navigator.mediaDevices?.getUserMedia) {
      setStep('denied')
      return
    }
    stopCamera()
    const ticket = cameraRequest.current
    try {
      const granted = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment' },
        audio: false,
      })
      // Hop thoai da dong, da chon anh khac hoac da xin lai trong luc cho cap quyen: tat ngay
      // camera vua nhan, khong de no chay ngam sau khi component da unmount.
      if (ticket !== cameraRequest.current) {
        granted.getTracks().forEach((track) => track.stop())
        return
      }
      stream.current = granted
      setStep('camera')
    } catch {
      if (ticket !== cameraRequest.current) return
      stopCamera()
      setStep('denied')
    }
  }

  // Gan stream vao the video sau khi buoc "camera" da render.
  useEffect(() => {
    if (step === 'camera' && video.current && stream.current) {
      video.current.srcObject = stream.current
      // play() co the khong tra Promise (trinh duyet cu) hoac bi tu choi khi chua tuong tac.
      Promise.resolve(video.current.play?.()).catch(() => {})
    }
  }, [step])

  function capture() {
    const element = video.current
    if (!element?.videoWidth) return
    const canvas = document.createElement('canvas')
    canvas.width = element.videoWidth
    canvas.height = element.videoHeight
    canvas.getContext('2d').drawImage(element, 0, 0)
    canvas.toBlob(
      (blob) => {
        if (blob) accept(new File([blob], 'anh-camera.jpg', { type: 'image/jpeg' }))
      },
      'image/jpeg',
      0.9,
    )
  }

  async function runSearch(nextFilters = filters) {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setFilters(nextFilters)
    setStep('loading')
    try {
      const items = await searchByImage(file, nextFilters, { signal: controller.signal })
      if (controller.signal.aborted) return
      setResults(items)
      setStep(items.length ? 'results' : 'noMatch')
    } catch (error) {
      if (controller.signal.aborted || error?.code === 'ERR_CANCELED') return
      const { code, status } = getApiError(error)
      if (code === 'IMAGE_TOO_LARGE' || status === 413) {
        setProblem({ kind: 'tooLarge', size: file.size })
        setStep('tooLarge')
      } else if (code === 'INVALID_IMAGE') {
        setProblem({ kind: 'invalid', server: true })
        setStep('invalid')
      } else if (status === 429) {
        setStep('busy')
      } else if (status >= 500 || code === 'IMAGE_SEARCH_UNAVAILABLE') {
        // 503 tu Search, hoac 500 tu gateway khi khong toi duoc container Search.
        setStep('unavailable')
      } else {
        setStep('error')
      }
    }
  }

  function searchKeyword(keyword) {
    close()
    navigate(`/search?q=${encodeURIComponent(keyword)}`)
  }

  function openProduct(id) {
    close()
    navigate(`/products/${encodeURIComponent(id)}`)
  }

  const filePicker = (
    <input
      ref={fileInput}
      type="file"
      accept={IMAGE_ACCEPT}
      hidden
      data-testid="image-search-file"
      onChange={(event) => {
        const [picked] = event.target.files || []
        event.target.value = ''
        if (picked) accept(picked)
      }}
    />
  )
  const closeButton = (
    <button type="button" className="button button--secondary" onClick={close}>
      Đóng
    </button>
  )
  const thumbnail = preview && (
    <img className="image-search__preview" src={preview} alt="Ảnh đã chọn để tìm kiếm" />
  )

  const screens = {
    pick: {
      title: 'Tìm bằng ảnh',
      description: `Chọn ảnh rõ sản phẩm, dung lượng tối đa ${MAX_LABEL}.`,
      body: (
        <>
          <div
            className={`image-search__drop${dragging ? ' is-dragging' : ''}`}
            onDragOver={(event) => {
              event.preventDefault()
              setDragging(true)
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(event) => {
              event.preventDefault()
              setDragging(false)
              const [dropped] = event.dataTransfer?.files || []
              if (dropped) accept(dropped)
            }}
          >
            <strong>Tải ảnh lên</strong>
            <span>Kéo ảnh vào đây hoặc chọn từ thiết bị (JPEG, PNG, WebP).</span>
          </div>
          <div className="image-search__actions">
            <button
              type="button"
              className="button button--primary"
              onClick={pickFile}
              data-autofocus
            >
              Chọn ảnh
            </button>
            <button type="button" className="button button--secondary" onClick={openCamera}>
              Dùng camera
            </button>
            {closeButton}
          </div>
        </>
      ),
    },
    camera: {
      title: 'Chụp ảnh sản phẩm',
      description: 'Đặt sản phẩm vào giữa khung hình, đủ sáng và rõ nét.',
      body: (
        <>
          <video
            ref={video}
            className="image-search__video"
            playsInline
            muted
            aria-label="Camera"
          />
          <div className="image-search__actions">
            <button type="button" className="button button--primary" onClick={capture}>
              Chụp ảnh
            </button>
            <button type="button" className="button button--secondary" onClick={pickFile}>
              Chọn ảnh từ thiết bị
            </button>
            {closeButton}
          </div>
        </>
      ),
    },
    denied: {
      title: 'Chưa được phép dùng camera',
      description:
        'Mở quyền camera trong cài đặt trình duyệt (biểu tượng ổ khóa cạnh địa chỉ trang) hoặc chọn ảnh từ thiết bị.',
      body: (
        <div className="image-search__actions">
          <button
            type="button"
            className="button button--primary"
            onClick={pickFile}
            data-autofocus
          >
            Chọn ảnh từ thiết bị
          </button>
          {closeButton}
        </div>
      ),
    },
    tooLarge: {
      title: `Ảnh vượt quá ${MAX_LABEL}`,
      description: `Ảnh bạn chọn có dung lượng ${formatMegabytes(problem?.size || 0)}. Chọn ảnh nhỏ hơn để tiếp tục.`,
      body: (
        <div className="image-search__actions">
          <button
            type="button"
            className="button button--primary"
            onClick={pickFile}
            data-autofocus
          >
            Chọn ảnh dưới {MAX_LABEL}
          </button>
          {closeButton}
        </div>
      ),
    },
    invalid: {
      title: 'Ảnh không hợp lệ',
      description: problem?.server
        ? 'Không đọc được ảnh này (ảnh động, hỏng hoặc quá nhiều điểm ảnh). Hãy chọn ảnh khác.'
        : 'Chỉ nhận ảnh JPEG, PNG hoặc WebP.',
      body: (
        <div className="image-search__actions">
          <button
            type="button"
            className="button button--primary"
            onClick={pickFile}
            data-autofocus
          >
            Chọn ảnh khác
          </button>
          {closeButton}
        </div>
      ),
    },
    preview: {
      title: 'Kiểm tra ảnh',
      description: file ? `Ảnh đã chọn · ${formatMegabytes(file.size)}` : '',
      body: (
        <>
          {thumbnail}
          <div className="image-search__actions">
            <button
              type="button"
              className="button button--primary"
              onClick={() => runSearch(EMPTY_FILTERS)}
              data-autofocus
            >
              Tìm sản phẩm tương đồng
            </button>
            <button type="button" className="button button--secondary" onClick={pickFile}>
              Chọn ảnh khác
            </button>
            {closeButton}
          </div>
        </>
      ),
    },
    loading: {
      title: 'Đang tìm sản phẩm tương đồng',
      description: 'Hệ thống đang so sánh ảnh với sản phẩm trên VMarket.',
      body: (
        <>
          <div className="image-search__loading" role="status">
            <span className="image-search__spinner" aria-hidden="true" />
            Đang phân tích ảnh…
          </div>
          <div className="image-search__actions">
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                request.current?.abort()
                setStep('preview')
              }}
            >
              Hủy
            </button>
          </div>
        </>
      ),
    },
    results: {
      title: 'Sản phẩm tương đồng',
      description: 'Kết quả dựa trên hình ảnh; kiểm tra mô tả trước khi mua.',
      body: (
        <>
          {thumbnail}
          <p className="image-search__summary">{filterSummary(filters, categories)}</p>
          <ul className="image-search__results" aria-label="Kết quả tìm bằng ảnh">
            {results.map((product) => {
              const image = product.matchedImageUrl || product.imageUrls?.[0]
              return (
                <li key={product.id}>
                  <button type="button" onClick={() => openProduct(product.id)}>
                    {image ? (
                      <img src={image} alt="" loading="lazy" />
                    ) : (
                      <span aria-hidden="true" />
                    )}
                    <span className="image-search__result-text">
                      <strong>{product.name}</strong>
                      <span>{priceLabel(product)}</span>
                      {Number.isFinite(product.similarity) && (
                        <small>Giống {Math.round(Math.max(0, product.similarity) * 100)}%</small>
                      )}
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
          <div className="image-search__actions">
            <button
              type="button"
              className="button button--secondary"
              onClick={() => setStep('filter')}
            >
              Lọc giá và danh mục
            </button>
            <button type="button" className="button button--secondary" onClick={pickFile}>
              Tìm bằng ảnh khác
            </button>
            {closeButton}
          </div>
        </>
      ),
    },
    filter: {
      title: 'Lọc kết quả ảnh',
      description: 'Áp dụng cùng ảnh đã chọn.',
      body: (
        <FilterForm
          filters={filters}
          categories={categories}
          onApply={(next) => runSearch(next)}
          onClear={() => runSearch(EMPTY_FILTERS)}
        />
      ),
    },
    noMatch: {
      title: 'Chưa tìm thấy sản phẩm phù hợp',
      description: 'Thử ảnh rõ hơn hoặc tìm bằng tên sản phẩm.',
      body: (
        <>
          <div className="image-search__actions">
            <button
              type="button"
              className="button button--primary"
              onClick={pickFile}
              data-autofocus
            >
              Chọn ảnh khác
            </button>
          </div>
          <KeywordFallback onSearch={searchKeyword} />
          <div className="image-search__actions">
            <Link
              className="button button--secondary"
              to={`/search?sort=${PRODUCT_SORT.bestSelling}`}
              onClick={close}
            >
              Xem sản phẩm bán chạy
            </Link>
            {closeButton}
          </div>
        </>
      ),
    },
    busy: {
      title: 'Hệ thống đang bận',
      description: 'Có nhiều yêu cầu tìm bằng ảnh cùng lúc. Vui lòng thử lại sau giây lát.',
      body: (
        <div className="image-search__actions">
          <button
            type="button"
            className="button button--primary"
            onClick={() => runSearch()}
            data-autofocus
          >
            Thử lại
          </button>
          {closeButton}
        </div>
      ),
    },
    unavailable: {
      title: 'Tìm bằng ảnh tạm gián đoạn',
      description: 'Bạn vẫn có thể tìm theo tên sản phẩm.',
      body: (
        <>
          <KeywordFallback onSearch={searchKeyword} label="Tìm sản phẩm" />
          <div className="image-search__actions">{closeButton}</div>
        </>
      ),
    },
    error: {
      title: 'Chưa tìm được',
      description: 'Đã có lỗi khi tìm bằng ảnh. Kiểm tra kết nối và thử lại.',
      body: (
        <div className="image-search__actions">
          <button
            type="button"
            className="button button--primary"
            onClick={() => runSearch()}
            data-autofocus
          >
            Thử lại
          </button>
          {closeButton}
        </div>
      ),
    },
  }
  const screen = screens[step]

  return (
    <Modal title={screen.title} description={screen.description} onClose={close}>
      <div className="image-search" data-step={step}>
        {screen.body}
        {filePicker}
      </div>
    </Modal>
  )
}
