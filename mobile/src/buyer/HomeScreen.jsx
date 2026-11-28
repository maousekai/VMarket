import { useCallback, useEffect, useState } from 'react'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { browseProducts, PRODUCT_SORT } from '../api/shopApi'
import { Body, Button, Card, Field, Heading, Loading, Notice, Screen } from '../components/ui'
import ProductCard from './ProductCard'

/**
 * Trang chu nguoi mua (frame 3.2): o tim kiem, Thong bao, khoi kham pha, san pham ban chay. Tim
 * bang anh va tro ly AI duoc luoc bo (review PR 68 lan 4) nen khong hien nut.
 */
export default function HomeScreen() {
  const [keyword, setKeyword] = useState('')
  const [state, setState] = useState({ items: [], loading: true, error: '' })

  const load = useCallback(async () => {
    setState((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const page = await browseProducts({ sort: PRODUCT_SORT.bestSelling, size: 10 })
      setState({ items: page?.content || [], loading: false, error: '' })
    } catch (failure) {
      setState((previous) => ({ ...previous, loading: false, error: getApiError(failure).message }))
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const search = () => router.push({ pathname: '/search', params: { q: keyword.trim() } })

  return (
    <Screen
      title="VMarket · Đà Nẵng"
      refreshing={state.loading && !!state.items.length}
      onRefresh={load}
    >
      <Field
        placeholder="Tìm sản phẩm, gian hàng…"
        value={keyword}
        onChangeText={setKeyword}
        returnKeyType="search"
        onSubmitEditing={search}
      />
      <Button title="Thông báo" variant="secondary" onPress={() => router.push('/notifications')} />
      <Heading>Chọn hàng địa phương</Heading>
      <Body muted>Đặc sản · Hải sản · Đồ dùng mỗi ngày</Body>
      <Card
        title="Khám phá Đà Nẵng"
        onPress={() => router.push('/search')}
        accessibilityLabel="Khám phá Đà Nẵng"
      >
        <Body>Duyệt sản phẩm theo danh mục và gian hàng.</Body>
      </Card>
      <Heading level={3}>Bán chạy cho người mới</Heading>
      {state.error ? (
        <>
          <Notice tone="danger">{state.error}</Notice>
          <Button title="Thử lại" variant="secondary" onPress={load} />
        </>
      ) : null}
      {state.loading && !state.items.length ? <Loading /> : null}
      {!state.loading && !state.items.length && !state.error ? (
        <Body muted>Chưa có sản phẩm đang bán.</Body>
      ) : null}
      {state.items.map((product) => (
        <ProductCard key={product.id} product={product} />
      ))}
    </Screen>
  )
}
