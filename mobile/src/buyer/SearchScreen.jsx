import { useCallback, useEffect, useRef, useState } from 'react'
import { View } from 'react-native'
import { getApiError } from '../api/client'
import { browseProducts, PRODUCT_SORT } from '../api/shopApi'
import {
  Body,
  Button,
  Card,
  Choice,
  Field,
  Heading,
  Label,
  Loading,
  Notice,
  Screen,
} from '../components/ui'
import { makeStyles, spacing } from '../theme'
import BuyerNav from './BuyerNav'
import { useShopLookup } from './catalog'
import ProductCard from './ProductCard'

const SORTS = [
  [PRODUCT_SORT.relevant, 'Sắp xếp: liên quan'],
  [PRODUCT_SORT.bestSelling, 'Bán chạy'],
  [PRODUCT_SORT.priceAsc, 'Giá thấp đến cao'],
  [PRODUCT_SORT.priceDesc, 'Giá cao đến thấp'],
]
const PAGE_SIZE = 20
const digits = (value) => value.replace(/\D/g, '')
const toNumber = (value) => (value ? Number(value) : undefined)

/** Ket qua tim kiem va bo loc (frame 3.4, 3.62, "Khong co ket qua"). */
export default function SearchScreen({ initialKeyword = '' }) {
  const styles = useStyles()
  const [keyword, setKeyword] = useState(initialKeyword)
  const [query, setQuery] = useState({
    keyword: initialKeyword,
    sort: SORTS[0][0],
    min: '',
    max: '',
  })
  const [draft, setDraft] = useState({ sort: SORTS[0][0], min: '', max: '' })
  const [filtering, setFiltering] = useState(false)
  const [state, setState] = useState({ items: [], page: 0, more: false, loading: true, error: '' })
  const request = useRef(null)
  const shops = useShopLookup(state.items.map((product) => product.shopId))

  const load = useCallback(
    async (page = 0) => {
      request.current?.abort()
      const controller = new AbortController()
      request.current = controller
      setState((previous) => ({ ...previous, loading: true, error: '' }))
      try {
        const data = await browseProducts({
          keyword: query.keyword,
          sort: query.sort,
          minPrice: toNumber(query.min),
          maxPrice: toNumber(query.max),
          page,
          size: PAGE_SIZE,
          signal: controller.signal,
        })
        if (controller.signal.aborted) return
        const items = data?.content || []
        setState((previous) => ({
          items: page ? [...previous.items, ...items] : items,
          page,
          more: page + 1 < (data?.totalPages ?? 1),
          loading: false,
          error: '',
        }))
      } catch (failure) {
        if (controller.signal.aborted) return
        setState((previous) => ({
          ...previous,
          loading: false,
          error: getApiError(failure).message,
        }))
      }
    },
    [query],
  )

  useEffect(() => {
    load()
    return () => request.current?.abort()
  }, [load])

  const priceError =
    draft.min && draft.max && Number(draft.min) > Number(draft.max)
      ? 'Giá tối thiểu phải nhỏ hơn giá tối đa.'
      : ''

  return (
    <Screen title="Kết quả tìm kiếm" footer={<BuyerNav active="home" />}>
      <Field
        placeholder="Tìm sản phẩm, gian hàng…"
        value={keyword}
        onChangeText={setKeyword}
        returnKeyType="search"
        onSubmitEditing={() => setQuery((previous) => ({ ...previous, keyword: keyword.trim() }))}
      />
      <Heading>Sản phẩm phù hợp</Heading>
      <Button
        title="Lọc · Giá, sắp xếp"
        variant="secondary"
        onPress={() => {
          setDraft({ sort: query.sort, min: query.min, max: query.max })
          setFiltering((value) => !value)
        }}
      />
      {filtering ? (
        <Card title="Lọc kết quả tìm kiếm">
          <Label>Sắp xếp</Label>
          {SORTS.map(([value, label]) => (
            <Choice
              key={value}
              value={label}
              selected={draft.sort === value}
              onPress={() => setDraft((previous) => ({ ...previous, sort: value }))}
            />
          ))}
          <View style={styles.row}>
            <Field
              style={styles.flex}
              label="Giá từ (đ)"
              keyboardType="number-pad"
              value={draft.min}
              onChangeText={(value) =>
                setDraft((previous) => ({ ...previous, min: digits(value) }))
              }
            />
            <Field
              style={styles.flex}
              label="Đến (đ)"
              keyboardType="number-pad"
              value={draft.max}
              onChangeText={(value) =>
                setDraft((previous) => ({ ...previous, max: digits(value) }))
              }
            />
          </View>
          {priceError ? <Notice tone="danger">{priceError}</Notice> : null}
          <Button
            title="Áp dụng bộ lọc"
            disabled={Boolean(priceError)}
            onPress={() => {
              setQuery((previous) => ({ ...previous, ...draft }))
              setFiltering(false)
            }}
          />
          <Button
            title="Xoá bộ lọc"
            variant="secondary"
            onPress={() => {
              setDraft({ sort: SORTS[0][0], min: '', max: '' })
              setQuery((previous) => ({ ...previous, sort: SORTS[0][0], min: '', max: '' }))
              setFiltering(false)
            }}
          />
        </Card>
      ) : (
        <Choice value={SORTS.find(([value]) => value === query.sort)?.[1] || SORTS[0][1]} />
      )}
      {state.error ? (
        <>
          <Notice tone="danger">{state.error}</Notice>
          <Button title="Thử lại" variant="secondary" onPress={() => load()} />
        </>
      ) : null}
      {!state.loading && !state.items.length && !state.error ? (
        <Card title="Không có kết quả">
          <Body>
            {query.keyword
              ? `Chưa có sản phẩm phù hợp với "${query.keyword}". Thử từ khoá khác hoặc bỏ bớt bộ lọc.`
              : 'Chưa có sản phẩm phù hợp với bộ lọc.'}
          </Body>
        </Card>
      ) : null}
      {state.items.map((product) => (
        <ProductCard
          key={product.id}
          product={product}
          shopName={shops[product.shopId]?.value?.name}
          compact
          actionLabel="Xem chi tiết"
        />
      ))}
      {state.loading ? <Loading /> : null}
      {state.more && !state.loading ? (
        <Button title="Tải thêm" variant="secondary" onPress={() => load(state.page + 1)} />
      ) : null}
    </Screen>
  )
}

const useStyles = makeStyles(() => ({
  row: { flexDirection: 'row', gap: spacing(2) },
  flex: { flex: 1 },
}))
