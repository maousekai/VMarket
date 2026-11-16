/** Dang mo VMarket tren dien thoai (trinh duyet di dong hoac webview cua ung dung). */
export function isMobileDevice() {
  if (typeof navigator === 'undefined') return false
  if (typeof navigator.userAgentData?.mobile === 'boolean') return navigator.userAgentData.mobile
  return /Android|iPhone|iPad|iPod|Mobile|Windows Phone/i.test(navigator.userAgent || '')
}
