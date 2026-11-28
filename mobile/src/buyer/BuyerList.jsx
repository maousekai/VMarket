import { useCallback, useEffect, useRef, useState } from 'react'
import { FlatList, StyleSheet, Text } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { getApiError } from '../api/client'
import { Button, Loading, Notice } from '../components/ui'
import { colors, spacing } from '../theme'

export default function BuyerList({ title, fetchPage, renderItem, emptyMessage }) {
  const [items, setItems] = useState([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const request = useRef(null)

  const load = useCallback(
    async (nextPage) => {
      request.current?.abort()
      const controller = new AbortController()
      request.current = controller
      setLoading(true)
      setError('')
      try {
        const result = await fetchPage(nextPage, controller.signal)
        if (controller.signal.aborted) return
        setItems((previous) => {
          const existing = nextPage ? previous : []
          return [
            ...new Map([...existing, ...result.items].map((item) => [item.id, item])).values(),
          ]
        })
        setPage(nextPage)
        setHasMore(nextPage + 1 < result.totalPages)
      } catch (failure) {
        if (!controller.signal.aborted) setError(getApiError(failure).message)
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    },
    [fetchPage],
  )

  useEffect(() => {
    load(0)
    return () => request.current?.abort()
  }, [load])

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <FlatList
        data={items}
        keyExtractor={(item) => item.id}
        renderItem={renderItem}
        contentContainerStyle={styles.content}
        ListHeaderComponent={
          <Text accessibilityRole="header" style={styles.title}>
            {title}
          </Text>
        }
        refreshing={loading && items.length > 0}
        onRefresh={() => load(0)}
        ListEmptyComponent={
          loading ? <Loading /> : error ? null : <Text style={styles.empty}>{emptyMessage}</Text>
        }
        ListFooterComponent={
          error ? (
            <>
              <Notice tone="danger">{error}</Notice>
              <Button title="Thử lại" variant="secondary" onPress={() => load(0)} />
            </>
          ) : hasMore ? (
            <Button
              title="Tải thêm"
              variant="secondary"
              loading={loading}
              onPress={() => load(page + 1)}
            />
          ) : null
        }
      />
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { gap: spacing(3), padding: spacing(4) },
  title: { fontSize: 22, fontWeight: '800', color: colors.text },
  empty: { marginTop: spacing(6), textAlign: 'center', color: colors.muted },
})
