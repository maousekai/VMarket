import { useQuery } from '@tanstack/react-query'
import { router } from 'expo-router'
import { useMemo, useState } from 'react'
import { Pressable, StyleSheet } from 'react-native'

import { listCategories } from '@/api/imageSearch'
import { AppText, BottomSheet, Button, Screen, TextField } from '@/components/ui'
import { NoImage } from '@/features/imageSearch/components/NoImage'
import {
  flattenCategories,
  formatPriceInput,
  parsePriceInput,
} from '@/features/imageSearch/filterForm'
import { useImageSearch } from '@/features/imageSearch/ImageSearchContext'
import { colors } from '@/theme'

const priceText = (value?: number) => (value === undefined ? '' : formatPriceInput(String(value)))

/** Frame 3.49 - category and price filter; the photo stays selected. */
export default function FilterScreen() {
  const { image, filter, setFilter } = useImageSearch()
  const [categoryId, setCategoryId] = useState(filter.categoryId)
  const [categoryName, setCategoryName] = useState(filter.categoryName)
  const [minText, setMinText] = useState(priceText(filter.minPrice))
  const [maxText, setMaxText] = useState(priceText(filter.maxPrice))
  const [pickerOpen, setPickerOpen] = useState(false)

  const categories = useQuery({
    queryKey: ['image-search', 'categories'],
    queryFn: listCategories,
    staleTime: 10 * 60_000,
  })
  const options = useMemo(() => flattenCategories(categories.data ?? []), [categories.data])

  if (!image) return <NoImage title="Lọc kết quả theo ảnh" />

  const min = parsePriceInput(minText)
  const max = parsePriceInput(maxText)
  const rangeError =
    min !== undefined && max !== undefined && min > max
      ? 'Giá đến phải lớn hơn hoặc bằng giá từ'
      : null

  const apply = () => {
    setFilter({ categoryId, categoryName, minPrice: min, maxPrice: max })
    router.back()
  }
  const clear = () => {
    setCategoryId(undefined)
    setCategoryName(undefined)
    setMinText('')
    setMaxText('')
    setFilter({})
  }
  const choose = (id?: string, name?: string) => {
    setCategoryId(id)
    setCategoryName(name)
    setPickerOpen(false)
  }

  return (
    <Screen
      title="Lọc kết quả theo ảnh"
      onBack={() => router.back()}
      testID="image-search-filter-screen"
    >
      <AppText variant="body">Giữ ảnh đã chọn khi lọc</AppText>
      <TextField
        label="Danh mục"
        picker
        value={categoryName}
        placeholder="Tất cả danh mục"
        onPress={() => setPickerOpen(true)}
        testID="image-search-filter-category"
      />
      <TextField
        label="Giá từ"
        value={minText}
        onChangeText={(t) => setMinText(formatPriceInput(t))}
        placeholder="0"
        keyboardType="number-pad"
        right={<AppText color="textSecondary">đ</AppText>}
        testID="image-search-filter-min"
      />
      <TextField
        label="Giá đến"
        value={maxText}
        onChangeText={(t) => setMaxText(formatPriceInput(t))}
        placeholder="Không giới hạn"
        keyboardType="number-pad"
        error={rangeError}
        right={<AppText color="textSecondary">đ</AppText>}
        testID="image-search-filter-max"
      />
      <Button
        testID="image-search-filter-apply"
        title="Áp dụng bộ lọc"
        onPress={apply}
        disabled={!!rangeError}
      />
      <Button title="Xóa bộ lọc" kind="secondary" onPress={clear} />

      <BottomSheet visible={pickerOpen} onClose={() => setPickerOpen(false)} title="Danh mục">
        <Pressable accessibilityRole="button" style={styles.option} onPress={() => choose()}>
          <AppText variant="body">Tất cả danh mục</AppText>
        </Pressable>
        {categories.isPending ? (
          <AppText variant="body" color="textSecondary">
            Đang tải danh mục...
          </AppText>
        ) : categories.isError ? (
          <Button
            title="Tải lại danh mục"
            kind="secondary"
            onPress={() => void categories.refetch()}
          />
        ) : (
          options.map((o) => (
            <Pressable
              key={o.id}
              accessibilityRole="button"
              style={[styles.option, { paddingLeft: 16 * (o.depth + 1) }]}
              onPress={() => choose(o.id, o.name)}
            >
              <AppText variant={o.id === categoryId ? 'label' : 'body'}>{o.name}</AppText>
            </Pressable>
          ))
        )}
      </BottomSheet>
    </Screen>
  )
}

const styles = StyleSheet.create({
  option: {
    minHeight: 44,
    justifyContent: 'center',
    paddingRight: 16,
    borderBottomWidth: 1,
    borderBottomColor: colors.border,
  },
})
