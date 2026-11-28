module.exports = ({ config }) => {
  if (['preview', 'production'].includes(process.env.EAS_BUILD_PROFILE)) {
    for (const key of ['EXPO_PUBLIC_API_URL', 'EXPO_PUBLIC_WEB_URL']) {
      const url = URL.parse(process.env[key] || '')
      if (!url || url.protocol !== 'https:' || url.hostname.startsWith('change-me')) {
        throw new Error(`${key} cần URL HTTPS thật trong môi trường EAS của bản build.`)
      }
    }
    const origin = process.env.EXPO_PUBLIC_DELIVERY_WS_ORIGIN
    if (origin && !['https:', 'wss:'].includes(URL.parse(origin)?.protocol)) {
      throw new Error('EXPO_PUBLIC_DELIVERY_WS_ORIGIN cần dùng HTTPS hoặc WSS trong bản phát hành.')
    }
  }
  return config
}
