import { useQuery } from '@tanstack/react-query'
import * as Location from 'expo-location'

/** "lat, lng" with 5 decimals, the fallback when no address is available. */
export const formatCoords = (latitude: number, longitude: number) =>
  `${latitude.toFixed(5)}, ${longitude.toFixed(5)}`

/** Reverse-geocodes a position to a short address; resolves to coordinates when unavailable. */
export function useAddress(point: { latitude: number; longitude: number } | null) {
  const fallback = point ? formatCoords(point.latitude, point.longitude) : ''
  const q = useQuery({
    queryKey: ['shipper', 'geocode', fallback],
    enabled: !!point,
    staleTime: Infinity,
    retry: false,
    queryFn: async () => {
      try {
        const [p] = await Location.reverseGeocodeAsync(point!)
        const text = [p?.name ?? p?.street, p?.district ?? p?.subregion, p?.city ?? p?.region]
          .filter(Boolean)
          .join(', ')
        return text || null
      } catch {
        return null
      }
    },
  })
  return q.data ?? fallback
}
