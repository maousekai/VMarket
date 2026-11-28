import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import './delivery.css'

const DEFAULT_CENTER = [16.0544, 108.2022]
const TILE_URL = 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png'
const ATTRIBUTION = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'

/**
 * Chon diem giao tren ban do (review PR 68, muc 4.10): bam vao ban do de dat diem. Toa do di kem
 * dia chi, duoc chep vao don de shipper chi duong va hien marker diem giao.
 */
export default function LocationPicker({ value, onChange }) {
  const containerRef = useRef(null)
  const mapRef = useRef(null)
  const markerRef = useRef(null)
  const onChangeRef = useRef(onChange)

  useEffect(() => {
    onChangeRef.current = onChange
  }, [onChange])

  useEffect(() => {
    const map = L.map(containerRef.current)
    L.tileLayer(TILE_URL, { maxZoom: 19, attribution: ATTRIBUTION }).addTo(map)
    map.setView(DEFAULT_CENTER, 13)
    map.on('click', (event) => {
      onChangeRef.current({
        latitude: Number(event.latlng.lat.toFixed(6)),
        longitude: Number(event.latlng.lng.toFixed(6)),
      })
    })
    mapRef.current = map
    return () => {
      map.remove()
      mapRef.current = null
      markerRef.current = null
    }
  }, [])

  const point =
    value && Number.isFinite(value.latitude) && Number.isFinite(value.longitude)
      ? [value.latitude, value.longitude]
      : null
  const key = point ? point.join(',') : ''

  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const latLng = key ? key.split(',').map(Number) : null
    if (!latLng) {
      markerRef.current?.remove()
      markerRef.current = null
      return
    }
    if (!markerRef.current) {
      markerRef.current = L.circleMarker(latLng, {
        radius: 9,
        color: '#fff',
        weight: 3,
        fillColor: '#15803d',
        fillOpacity: 1,
      })
        .bindTooltip('Điểm giao')
        .addTo(map)
    } else markerRef.current.setLatLng(latLng)
    map.setView(latLng, Math.max(map.getZoom(), 16))
  }, [key])

  return (
    <div className="delivery-map location-picker" role="region" aria-label="Chọn điểm giao">
      <div ref={containerRef} className="delivery-map__canvas" />
    </div>
  )
}
