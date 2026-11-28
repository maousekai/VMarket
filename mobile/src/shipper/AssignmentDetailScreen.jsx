import { useEffect, useState } from 'react'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { listMyAssignments } from '../api/deliveryApi'
import { Button, Card, Body, Loading, Notice, Screen } from '../components/ui'
import AssignmentDetail from './AssignmentDetail'
import { useShipper } from './ShipperProvider'
import TrackingBanner from './TrackingBanner'

/**
 * Chi tiet chuyen (frame 3.12-3.20, 3.28-3.29). Chuyen dang chay lay tu ShipperProvider de GPS va
 * danh sach luon dong bo; chuyen da ket thuc (mo tu Lich su) tai rieng tu scope=finished.
 */
export default function AssignmentDetailScreen({ id }) {
  const { items, status, load, onChanged, tracking } = useShipper()
  const active = items.find((item) => item.id === id)
  const [finished, setFinished] = useState(null)
  const [error, setError] = useState('')
  const [justFinished, setJustFinished] = useState(null)

  useEffect(() => {
    if (active || status === 'loading' || justFinished) return undefined
    let current = true
    listMyAssignments({ scope: 'finished', size: 100 })
      .then((rows) => {
        if (!current) return
        const found = rows.find((row) => row.id === id)
        if (found) setFinished(found)
        else setError('Không tìm thấy chuyến này hoặc chuyến đã được giao cho shipper khác.')
      })
      .catch((failure) => current && setError(getApiError(failure).message))
    return () => {
      current = false
    }
  }, [active, status, id, justFinished])

  const assignment = active || justFinished || finished

  const changed = (updated) => {
    // Giao xong / tra xong: chuyen roi khoi danh sach dang chay nhung van hien ket qua tai day.
    setJustFinished(updated)
    onChanged(updated)
  }

  return (
    <Screen title="VMarket Shipper" role="shipper">
      <TrackingBanner tracking={tracking} />
      {!assignment && !error && <Loading />}
      {error ? <Notice tone="danger">{error}</Notice> : null}
      {assignment && (
        <AssignmentDetail assignment={assignment} onChanged={changed} onStale={load} />
      )}
      {assignment?.status === 'DELIVERED' && (
        <Card title="Đã giao xong">
          <Body>Đơn đã giao xong; việc chia sẻ vị trí cho đơn này đã dừng.</Body>
        </Card>
      )}
      <Button
        title="Về đơn được phân công"
        variant="secondary"
        onPress={() => (router.canGoBack() ? router.back() : router.replace('/'))}
      />
    </Screen>
  )
}
