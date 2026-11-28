import { useEffect, useRef, useState } from 'react'
import { Linking, Text, View } from 'react-native'
import { WebView } from 'react-native-webview'
import { makeStyles, radius, spacing } from '../theme'

const SOURCE = {
  html: `<!doctype html>
    <html lang="vi">
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src https://unpkg.com 'nonce-vmarket-map'; style-src https://unpkg.com 'unsafe-inline'; img-src https://tile.openstreetmap.org data:">
      <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" integrity="sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=" crossorigin="anonymous">
      <style>html,body,#map{height:100%;margin:0}body{background:#eef4fa}</style>
      <div id="map" aria-label="Bản đồ chuyến giao"></div>
      <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" integrity="sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo=" crossorigin="anonymous"></script>
      <script nonce="vmarket-map">
        if (window.L) {
          const map = L.map('map', {attributionControl:false});
          const layers = {};
          let centered = false;
          L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom:19}).addTo(map);
          window.renderDelivery = ({dropoff, shipper}) => {
            const points = [];
            for (const [name, point, color] of [
              ['Điểm giao', dropoff, '#b54708'],
              ['Shipper', shipper, '#0670ce']
            ]) {
              if (!point) {
                if (layers[name]) layers[name].remove();
                delete layers[name];
                continue;
              }
              points.push(point);
              if (layers[name]) layers[name].setLatLng(point);
              else layers[name] = L.circleMarker(point, {radius:8,color:'#fff',weight:3,fillColor:color,fillOpacity:1})
                .addTo(map).bindTooltip(name, {direction:'top'});
            }
            if ((!centered || points.some(point => !map.getBounds().contains(point))) && points.length) {
              if (points.length > 1) map.fitBounds(points, {padding:[25,25],maxZoom:16});
              else map.setView(points[0], 15);
              centered = true;
            }
          };
          window.ReactNativeWebView.postMessage('ready');
        } else window.ReactNativeWebView.postMessage('error');
      </script>
    </html>`,
}

function point(value) {
  const { latitude, longitude } = value || {}
  return Number.isFinite(latitude) &&
    Math.abs(latitude) <= 90 &&
    Number.isFinite(longitude) &&
    Math.abs(longitude) <= 180
    ? [latitude, longitude]
    : null
}

export default function DeliveryMap({ location, deliveryPoint }) {
  const styles = useStyles()
  const web = useRef(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState(false)
  const coordinates = JSON.stringify({ shipper: point(location), dropoff: point(deliveryPoint) })
  useEffect(() => {
    if (ready) web.current?.injectJavaScript(`window.renderDelivery(${coordinates}); true;`)
  }, [coordinates, ready])
  if (!point(location) && !point(deliveryPoint)) return null
  if (error) return <Text style={styles.legend}>Không tải được bản đồ.</Text>
  return (
    <View style={styles.container}>
      <View style={styles.map}>
        <WebView
          ref={web}
          source={SOURCE}
          originWhitelist={['*']}
          onShouldStartLoadWithRequest={({ url }) => url === 'about:blank'}
          applicationNameForUserAgent="VMarketMobile/1.0"
          setSupportMultipleWindows={false}
          allowFileAccess={false}
          domStorageEnabled={false}
          onError={() => setError(true)}
          onMessage={({ nativeEvent }) => {
            if (nativeEvent.data === 'ready') {
              web.current?.injectJavaScript(`window.renderDelivery(${coordinates}); true;`)
              setReady(true)
            } else if (nativeEvent.data === 'error') setError(true)
          }}
        />
      </View>
      <Text style={styles.legend}>
        {point(location) && <Text style={styles.shipper}>● Shipper </Text>}
        {point(deliveryPoint) && <Text style={styles.dropoff}>● Điểm giao </Text>}
        <Text
          onPress={() => Linking.openURL('https://www.openstreetmap.org/copyright').catch(() => {})}
        >
          © OpenStreetMap
        </Text>
      </Text>
    </View>
  )
}

const useStyles = makeStyles((colors) => ({
  container: { gap: spacing(2) },
  map: { height: 240, overflow: 'hidden', borderRadius: radius.md },
  legend: { color: colors.muted, fontSize: 13 },
  shipper: { color: colors.primaryText },
  dropoff: { color: colors.warning },
}))
