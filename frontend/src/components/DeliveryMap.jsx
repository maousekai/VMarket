import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import './delivery.css'

// Trung tam mac dinh khi chua co diem GPS nao (Da Nang).
const DEFAULT_CENTER = [16.0544, 108.2022]
const TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'

const toLatLng = (point) =>
  point && Number.isFinite(point.latitude) && Number.isFinite(point.longitude)
    ? [point.latitude, point.longitude]
    : null

/**
 * Ban do Leaflet + OpenStreetMap: duong da di (route) va vi tri shipper hien tai.
 * Dung circleMarker (ve bang SVG) de khong phu thuoc anh marker mac dinh cua Leaflet.
 */
export default function DeliveryMap({
  position,
  route = [],
  destination = null,
  label = 'Bản đồ vị trí shipper',
}) {
  const containerRef = useRef(null)
  const mapRef = useRef(null)
  const layersRef = useRef(null)
  const followRef = useRef(true)

  useEffect(() => {
    const map = L.map(containerRef.current, { zoomControl: true, attributionControl: true })
    L.tileLayer(TILE_URL, { maxZoom: 19, attribution: ATTRIBUTION }).addTo(map)
    map.setView(DEFAULT_CENTER, 13)
    // Nguoi dung tu keo ban do thi ngung tu bam theo shipper.
    map.on('dragstart', () => {
      followRef.current = false
    })
    layersRef.current = {
      path: L.polyline([], { color: '#0670ce', weight: 4, opacity: 0.7 }).addTo(map),
      marker: null,
      destination: null,
    }
    mapRef.current = map
    return () => {
      map.remove()
      mapRef.current = null
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const layers = layersRef.current
    if (!map || !layers) return
    const points = route.map(toLatLng).filter(Boolean)
    const current = toLatLng(position)
    if (current && !points.some(([a, b]) => a === current[0] && b === current[1]))
      points.push(current)
    layers.path.setLatLngs(points)
    if (current) {
      if (!layers.marker) {
        layers.marker = L.circleMarker(current, {
          radius: 9,
          color: '#fff',
          weight: 3,
          fillColor: '#f97316',
          fillOpacity: 1,
        })
          .bindTooltip('Shipper')
          .addTo(map)
      } else {
        layers.marker.setLatLng(current)
      }
      if (followRef.current) map.setView(current, Math.max(map.getZoom(), 15))
    } else if (points.length > 1) {
      map.fitBounds(L.latLngBounds(points), { padding: [24, 24] })
    }
  }, [position, route])

  // Marker diem giao (review PR 68, muc 4.10): toa do nguoi mua chon khi luu dia chi.
  const destinationPoint = toLatLng(destination)
  const destinationKey = destinationPoint ? destinationPoint.join(',') : ''
  useEffect(() => {
    const map = mapRef.current
    const layers = layersRef.current
    if (!map || !layers) return
    const point = destinationKey ? destinationKey.split(',').map(Number) : null
    if (!point) {
      layers.destination?.remove()
      layers.destination = null
      return
    }
    if (!layers.destination) {
      layers.destination = L.circleMarker(point, {
        radius: 8,
        color: '#fff',
        weight: 3,
        fillColor: '#15803d',
        fillOpacity: 1,
      })
        .bindTooltip('Điểm giao')
        .addTo(map)
    } else layers.destination.setLatLng(point)
    if (!toLatLng(position)) map.setView(point, Math.max(map.getZoom(), 14))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [destinationKey])

  return (
    <div className="delivery-map" role="region" aria-label={label}>
      <div ref={containerRef} className="delivery-map__canvas" />
      <button
        type="button"
        className="delivery-map__recenter"
        onClick={() => {
          followRef.current = true
          const current = toLatLng(position)
          if (current) mapRef.current?.setView(current, Math.max(mapRef.current.getZoom(), 15))
        }}
        disabled={!toLatLng(position)}
      >
        Về vị trí shipper
      </button>
    </div>
  )
}
