import { Ionicons } from '@expo/vector-icons'
import { router, useLocalSearchParams } from 'expo-router'
import { useCallback, useEffect, useState } from 'react'
import { Pressable } from 'react-native'

import { AppText, Button, ErrorState, InfoCard, Screen, Skeleton, TextField } from '@/components/ui'
import { openProduct } from '@/features/catalog/components/ProductGrid'
import { LineItem } from '@/features/catalog/components/LineItem'
import { SEARCH_PLACEHOLDER } from '@/features/catalog/components/SearchBox'
import { useDebouncedValue, useSearchPreview } from '@/features/catalog/hooks'
import { imageSearchHref, resultsHref } from '@/features/catalog/routes'
import {
  clearRecentSearches,
  loadRecentSearches,
  rememberSearch,
} from '@/features/catalog/recentSearches'
import { formatVnd } from '@/lib/format'
import { colors } from '@/theme'

export default function SearchEntryScreen() {
  const params = useLocalSearchParams<{ keyword?: string }>()
  const [text, setText] = useState(params.keyword ?? '')
  const keyword = text.trim()
  const debounced = useDebouncedValue(keyword)
  const preview = useSearchPreview(debounced)
  const [recent, setRecent] = useState<string[]>([])

  useEffect(() => {
    void loadRecentSearches().then(setRecent)
  }, [])

  const search = useCallback((term: string) => {
    const value = term.trim()
    if (!value) return
    void rememberSearch(value)
    router.push(resultsHref({ keyword: value }))
  }, [])

  const pick = (term: string) => {
    setText(term)
    search(term)
  }

  const shortInput = keyword.length < 2
  const suggestions = preview.data?.suggestions ?? []
  const products = preview.data?.products ?? []

  return (
    <Screen title="Tìm kiếm" onBack={() => router.back()} testID="search-screen">
      <TextField
        testID="search-input"
        value={text}
        onChangeText={setText}
        placeholder={SEARCH_PLACEHOLDER}
        returnKeyType="search"
        maxLength={200}
        onSubmitEditing={() => search(text)}
        right={
          <>
            {text ? (
              <Pressable
                testID="search-clear"
                accessibilityLabel="Xóa"
                hitSlop={10}
                onPress={() => setText('')}
              >
                <Ionicons name="close-circle" size={18} color={colors.textSecondary} />
              </Pressable>
            ) : null}
            <Pressable
              testID="search-image"
              accessibilityLabel="Tìm bằng ảnh"
              hitSlop={10}
              onPress={() => router.push(imageSearchHref)}
            >
              <Ionicons name="camera-outline" size={20} color={colors.textSecondary} />
            </Pressable>
          </>
        }
        autoFocus
      />

      {shortInput && recent.length ? (
        <InfoCard title="Tìm kiếm gần đây" testID="search-recent">
          {recent.map((term) => (
            <LineItem key={term} text={term} onPress={() => pick(term)} />
          ))}
          <Button
            title="Xóa lịch sử"
            kind="neutral"
            size="M"
            onPress={() => {
              void clearRecentSearches()
              setRecent([])
            }}
          />
        </InfoCard>
      ) : null}

      {!shortInput && !preview.data && !preview.isError ? (
        <Skeleton height={96} radius={10} />
      ) : null}

      {!shortInput && preview.isError ? (
        <ErrorState error={preview.error} onRetry={() => void preview.refetch()} />
      ) : null}

      {!shortInput && suggestions.length ? (
        <InfoCard title="Gợi ý" testID="search-suggestions">
          {suggestions.map((s) => (
            <LineItem key={s} text={s} onPress={() => pick(s)} />
          ))}
        </InfoCard>
      ) : null}

      {!shortInput && products.length ? (
        <InfoCard title="Sản phẩm" testID="search-products">
          {products.map((p) => (
            <LineItem
              key={p.id}
              testID={`search-product-${p.id}`}
              text={`${p.name} · ${formatVnd(p.minPrice)}`}
              onPress={() => openProduct(p.id)}
            />
          ))}
        </InfoCard>
      ) : null}

      {keyword ? (
        <Button testID="search-submit" title={`Tìm “${keyword}”`} onPress={() => search(keyword)} />
      ) : (
        <AppText variant="caption" color="textSecondary">
          Nhập ít nhất 2 ký tự để xem gợi ý.
        </AppText>
      )}
    </Screen>
  )
}
