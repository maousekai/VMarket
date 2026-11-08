import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { getApiError } from '../services/api/apiClient'
import { getMyShop, getRecentOrders, getShopStats } from '../services/api/shopApi'
import ShopStatusBadge from '../components/ShopStatusBadge'
import { formatDate, formatDateTime } from '../utils/formatDate'
import {
  formatCompactCurrency,
  formatCurrency,
  formatPercent,
  formatShortDate,
  imageUrlOrEmpty,
  orderStatusLabels,
  percentChange,
} from '../utils/shop'
import '../components/account.css'
import '../components/seller.css'

const ranges = [
  ['7d', '7 ngày'],
  ['30d', '30 ngày'],
  ['90d', '90 ngày'],
]

function StatCard({ label, value, hint, tone = 'default', icon }) {
  return (
    <article className={`seller-stat seller-stat--${tone}`}>
      <span className="seller-stat__icon" aria-hidden="true">
        {icon}
      </span>
      <div>
        <span className="seller-stat__label">{label}</span>
        <strong>{value}</strong>
        <small>{hint}</small>
      </div>
    </article>
  )
}

function RevenueChart({ daily }) {
  const width = 760
  const height = 240
  const pad = { top: 20, right: 20, bottom: 34, left: 58 }
  const points = daily?.length ? daily : []
  const max = Math.max(1, ...points.map((point) => Number(point.revenue) || 0))
  const innerWidth = width - pad.left - pad.right
  const innerHeight = height - pad.top - pad.bottom
  const step = points.length > 1 ? innerWidth / (points.length - 1) : 0
  const coordinates = points.map((point, index) => {
    const value = Number(point.revenue) || 0
    return {
      x: pad.left + (points.length > 1 ? index * step : innerWidth / 2),
      y: pad.top + innerHeight - (value / max) * innerHeight,
      ...point,
    }
  })
  const line = coordinates.map((point) => `${point.x},${point.y}`).join(' ')
  const area = coordinates.length
    ? `M ${coordinates[0].x} ${pad.top + innerHeight} L ${coordinates
        .map((point) => `${point.x} ${point.y}`)
        .join(' L ')} L ${coordinates[coordinates.length - 1].x} ${pad.top + innerHeight} Z`
    : ''
  const ticks = [0, 0.5, 1]
  const labelIndexes = points.length
    ? [...new Set([0, Math.floor((points.length - 1) / 2), points.length - 1])]
    : []

  return (
    <figure className="seller-chart">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label="Biểu đồ doanh thu theo ngày"
        preserveAspectRatio="xMidYMid meet"
      >
        {ticks.map((tick) => {
          const y = pad.top + innerHeight - tick * innerHeight
          return (
            <g key={tick}>
              <line
                x1={pad.left}
                x2={width - pad.right}
                y1={y}
                y2={y}
                className="seller-chart__grid"
              />
              <text x={pad.left - 10} y={y + 4} textAnchor="end" className="seller-chart__tick">
                {formatCompactCurrency(max * tick)}
              </text>
            </g>
          )
        })}
        {area && <path d={area} className="seller-chart__area" />}
        {line && <polyline points={line} className="seller-chart__line" />}
        {coordinates.map((point, index) => (
          <circle key={index} cx={point.x} cy={point.y} r="3" className="seller-chart__dot">
            <title>
              {formatDate(point.date)}: {formatCurrency(point.revenue)} · {point.orders} đơn
            </title>
          </circle>
        ))}
        {labelIndexes.map((index) => (
          <text
            key={index}
            x={pad.left + (points.length > 1 ? index * step : innerWidth / 2)}
            y={height - 8}
            textAnchor={index === 0 ? 'start' : index === points.length - 1 ? 'end' : 'middle'}
            className="seller-chart__tick"
          >
            {formatShortDate(points[index].date)}
          </text>
        ))}
      </svg>
      <figcaption>
        Doanh thu ghi nhận theo ngày tạo đơn, không gồm đơn đã hủy. Múi giờ Việt Nam.
      </figcaption>
    </figure>
  )
}

function downloadCsv(shop, range, stats, recent) {
  const rows = [
    ['Gian hàng', shop.name],
    ['Khoảng thời gian', range],
    ['Tổng doanh thu', stats.summary.grossRevenue],
    ['Doanh thu đã giao', stats.summary.deliveredRevenue],
    ['Tổng đơn', stats.summary.totalOrders],
    ['Đơn hủy', stats.summary.cancelledOrders],
    ['Sản phẩm đang bán', stats.activeProducts ?? ''],
    [],
    ['Ngày', 'Doanh thu', 'Số đơn'],
    ...stats.daily.map((row) => [row.date, row.revenue, row.orders]),
    [],
    ['Trạng thái', 'Số đơn'],
    ...stats.ordersByStatus.map((row) => [orderStatusLabels[row.status] || row.status, row.count]),
    [],
    ['Sản phẩm', 'Số lượng bán', 'Doanh thu'],
    ...stats.topProducts.map((row) => [row.name || row.productId, row.quantity, row.revenue]),
    [],
    ['Mã đơn', 'Trạng thái', 'Ngày tạo', 'Tiền của gian hàng', 'Số lượng'],
    ...recent.map((row) => [
      row.orderId,
      orderStatusLabels[row.status] || row.status,
      row.createdAt,
      row.shopSubtotal,
      row.itemCount,
    ]),
  ]
  const csv = rows
    .map((row) => row.map((cell) => `"${String(cell ?? '').replaceAll('"', '""')}"`).join(','))
    .join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `vmarket-${shop.id}-${range}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}

export default function SellerDashboardPage() {
  const [shopState, setShopState] = useState({ status: 'loading', shop: null, error: '' })
  const [range, setRange] = useState('7d')
  const [reloadKey, setReloadKey] = useState(0)
  const [statsState, setStatsState] = useState({ status: 'loading', stats: null, error: null })
  const [recentState, setRecentState] = useState({ status: 'loading', rows: [], error: null })

  useEffect(() => {
    let current = true
    getMyShop()
      .then((shop) => {
        if (current) setShopState({ status: 'ready', shop, error: '' })
      })
      .catch((failure) => {
        if (current) setShopState({ status: 'error', shop: null, error: getApiError(failure) })
      })
    return () => {
      current = false
    }
  }, [])

  const shop = shopState.shop
  const active = shop?.status === 'ACTIVE'

  useEffect(() => {
    if (!active) return
    let current = true
    setStatsState((previous) => ({
      ...previous,
      status: previous.stats ? 'refreshing' : 'loading',
    }))
    getShopStats(range)
      .then((stats) => {
        if (current) setStatsState({ status: 'ready', stats, error: null })
      })
      .catch((failure) => {
        if (current) setStatsState({ status: 'error', stats: null, error: getApiError(failure) })
      })
    return () => {
      current = false
    }
  }, [active, range, reloadKey])

  useEffect(() => {
    if (!active) return
    let current = true
    setRecentState((previous) => ({ ...previous, status: 'loading' }))
    getRecentOrders(5)
      .then((rows) => {
        if (current) setRecentState({ status: 'ready', rows, error: null })
      })
      .catch((failure) => {
        if (current) setRecentState({ status: 'error', rows: [], error: getApiError(failure) })
      })
    return () => {
      current = false
    }
  }, [active, reloadKey])

  const stats = statsState.stats
  const summary = stats?.summary
  const today = stats?.daily?.[stats.daily.length - 1]
  const previous = stats?.daily?.[stats.daily.length - 2]
  const todayDelta = percentChange(today?.revenue, previous?.revenue)
  const statusCounts = useMemo(
    () => Object.fromEntries((stats?.ordersByStatus || []).map((row) => [row.status, row.count])),
    [stats],
  )
  const needsAction = (statusCounts.PENDING || 0) + (statusCounts.WAITING_PAYMENT || 0)
  const cancelRate = summary?.totalOrders
    ? ((summary.cancelledOrders || 0) / summary.totalOrders) * 100
    : 0
  const maxStatus = Math.max(1, ...(stats?.ordersByStatus || []).map((row) => row.count))

  if (shopState.status === 'loading') {
    return (
      <section className="seller-page">
        <p className="account-notice" role="status">
          Đang tải dashboard…
        </p>
      </section>
    )
  }

  if (shopState.status === 'error') {
    return (
      <section className="seller-page">
        <section className="account-card account-state">
          <h1>Chưa thể tải dashboard</h1>
          <p role="alert">{shopState.error?.message}</p>
          <button className="account-button" type="button" onClick={() => window.location.reload()}>
            Thử lại
          </button>
        </section>
      </section>
    )
  }

  if (!active) {
    return (
      <section className="seller-page">
        <section className="account-card account-state">
          <span className="account-eyebrow">Tổng quan bán hàng</span>
          <ShopStatusBadge status={shop.status} />
          <h1>Dashboard mở khi gian hàng hoạt động</h1>
          <p>
            Gian hàng đang ở trạng thái “{shop.status}”. Hoàn tất hồ sơ hoặc chờ VMarket duyệt để
            xem doanh thu và đơn hàng.
          </p>
          <div className="account-actions">
            <Link className="account-button" to="/seller">
              Xem trạng thái hồ sơ
            </Link>
            <Link className="account-button account-button--secondary" to="/seller/settings">
              Cài đặt gian hàng
            </Link>
          </div>
        </section>
      </section>
    )
  }

  return (
    <section className="seller-page" aria-labelledby="seller-dashboard-heading">
      <div className="seller-page__header seller-page__header--row">
        <div>
          <span className="account-eyebrow">Tổng quan bán hàng</span>
          <h1 id="seller-dashboard-heading">{shop.name}</h1>
          <p className="account-subtitle">
            Theo dõi doanh thu, đơn cần xử lý và hiệu quả bán hàng của gian hàng.
          </p>
        </div>
        <div className="seller-page__header-actions">
          <div className="seller-range" role="group" aria-label="Khoảng thời gian">
            {ranges.map(([value, label]) => (
              <button
                key={value}
                type="button"
                aria-pressed={range === value}
                onClick={() => setRange(value)}
              >
                {label}
              </button>
            ))}
          </div>
          <button
            className="account-button account-button--secondary"
            type="button"
            disabled={statsState.status === 'loading'}
            onClick={() => setReloadKey((key) => key + 1)}
          >
            Làm mới
          </button>
          <button className="account-button" type="button" disabled title="Chưa khả dụng">
            Thêm sản phẩm
          </button>
        </div>
      </div>

      {shop.statusReason && (
        <p className="account-notice account-notice--warning">{shop.statusReason}</p>
      )}

      {statsState.status === 'error' && (
        <section className="seller-dashboard__error account-card">
          <div>
            <strong>Chưa tải được thống kê đơn hàng</strong>
            <p role="alert">
              {statsState.error?.message ||
                'Dịch vụ đơn hàng tạm thời không khả dụng. Vui lòng thử lại.'}
            </p>
          </div>
          <button
            className="account-button account-button--secondary"
            type="button"
            onClick={() => setReloadKey((key) => key + 1)}
          >
            Thử lại
          </button>
        </section>
      )}

      {statsState.status === 'loading' && !stats && (
        <div className="seller-stat-grid" aria-busy="true">
          {Array.from({ length: 4 }, (_, index) => (
            <div key={index} className="seller-stat seller-stat--skeleton" />
          ))}
        </div>
      )}

      {stats && (
        <>
          <section className="seller-stat-grid" aria-label="Chỉ số chính">
            <StatCard
              label="Doanh thu hôm nay"
              value={formatCurrency(today?.revenue)}
              hint={
                todayDelta === null
                  ? 'Chưa có dữ liệu hôm qua'
                  : `${todayDelta >= 0 ? '+' : ''}${formatPercent(todayDelta)} so với hôm qua`
              }
              tone="revenue"
              icon="₫"
            />
            <StatCard
              label="Đơn mới / chờ xử lý"
              value={needsAction}
              hint={`${summary?.totalOrders || 0} đơn trong kỳ · ${statusCounts.PROCESSING || 0} đang xử lý`}
              tone="orders"
              icon="🛎"
            />
            <StatCard
              label="Sản phẩm đang bán"
              value={stats.activeProducts ?? '—'}
              hint="Đếm từ Product Catalog"
              tone="products"
              icon="◫"
            />
            <StatCard
              label="Tỉ lệ hủy"
              value={formatPercent(cancelRate)}
              hint={`${summary?.cancelledOrders || 0} đơn hủy trên ${summary?.totalOrders || 0} đơn`}
              tone={cancelRate >= 10 ? 'danger' : 'default'}
              icon="↺"
            />
          </section>

          <div className="seller-dashboard__grid">
            <section className="account-card seller-panel seller-panel--chart">
              <div className="seller-panel__header">
                <div>
                  <h2>Doanh thu theo ngày</h2>
                  <p>Đơn không bị hủy, tính theo ngày tạo đơn.</p>
                </div>
                <strong className="seller-panel__total">
                  {formatCurrency(summary?.grossRevenue)}
                </strong>
              </div>
              <RevenueChart daily={stats.daily} />
            </section>

            <section className="account-card seller-panel">
              <div className="seller-panel__header">
                <div>
                  <h2>Phân bổ đơn hàng</h2>
                  <p>Tất cả trạng thái trong kỳ đã chọn.</p>
                </div>
              </div>
              <ul className="seller-status-bars">
                {(stats.ordersByStatus || []).map((row) => (
                  <li key={row.status}>
                    <span>{orderStatusLabels[row.status] || row.status}</span>
                    <span className="seller-status-bars__track">
                      <span style={{ width: `${(row.count / maxStatus) * 100}%` }} />
                    </span>
                    <strong>{row.count}</strong>
                  </li>
                ))}
              </ul>
            </section>
          </div>

          <div className="seller-dashboard__grid seller-dashboard__grid--tables">
            <section className="account-card seller-panel">
              <div className="seller-panel__header">
                <div>
                  <h2>Sản phẩm bán chạy</h2>
                  <p>Top 5 theo số lượng bán, không gồm đơn hủy.</p>
                </div>
              </div>
              {stats.topProducts.length === 0 ? (
                <p className="account-notice">Chưa có sản phẩm nào được bán trong kỳ.</p>
              ) : (
                <div className="seller-table-wrap">
                  <table className="seller-table">
                    <thead>
                      <tr>
                        <th scope="col">Sản phẩm</th>
                        <th scope="col">Đã bán</th>
                        <th scope="col">Doanh thu</th>
                      </tr>
                    </thead>
                    <tbody>
                      {stats.topProducts.map((row) => (
                        <tr key={row.productId}>
                          <td>
                            <div className="seller-product-cell">
                              {imageUrlOrEmpty(row.imageUrl) ? (
                                <img src={row.imageUrl} alt="" />
                              ) : (
                                <span
                                  className="seller-product-cell__placeholder"
                                  aria-hidden="true"
                                >
                                  ◫
                                </span>
                              )}
                              <span>{row.name || `Sản phẩm #${row.productId.slice(-6)}`}</span>
                            </div>
                          </td>
                          <td>{row.quantity}</td>
                          <td>{formatCurrency(row.revenue)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>

            <section className="account-card seller-panel">
              <div className="seller-panel__header">
                <div>
                  <h2>Đơn hàng gần đây</h2>
                  <p>Chỉ tính phần tiền và số lượng thuộc gian hàng.</p>
                </div>
                <button
                  className="link-button"
                  type="button"
                  disabled={!stats}
                  onClick={() => downloadCsv(shop, range, stats, recentState.rows)}
                >
                  Xuất CSV
                </button>
              </div>
              {recentState.status === 'loading' && <p role="status">Đang tải đơn gần đây…</p>}
              {recentState.status === 'error' && (
                <p className="account-notice account-notice--warning" role="alert">
                  {recentState.error?.message || 'Chưa tải được đơn gần đây.'}
                </p>
              )}
              {recentState.status === 'ready' && recentState.rows.length === 0 && (
                <p className="account-notice">Chưa có đơn hàng nào.</p>
              )}
              {recentState.rows.length > 0 && (
                <div className="seller-table-wrap">
                  <table className="seller-table">
                    <thead>
                      <tr>
                        <th scope="col">Mã đơn</th>
                        <th scope="col">Trạng thái</th>
                        <th scope="col">Giá trị shop</th>
                        <th scope="col">SL</th>
                        <th scope="col">Tạo lúc</th>
                      </tr>
                    </thead>
                    <tbody>
                      {recentState.rows.map((row) => (
                        <tr key={row.orderId}>
                          <td>
                            <span title={row.orderId}>#{row.orderId.slice(-8)}</span>
                          </td>
                          <td>
                            <span
                              className={`order-status order-status--${row.status.toLowerCase()}`}
                            >
                              {orderStatusLabels[row.status] || row.status}
                            </span>
                          </td>
                          <td>{formatCurrency(row.shopSubtotal)}</td>
                          <td>{row.itemCount}</td>
                          <td>{formatDateTime(row.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        </>
      )}
    </section>
  )
}
