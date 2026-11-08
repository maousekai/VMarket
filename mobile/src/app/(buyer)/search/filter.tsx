import { Pressable, StyleSheet, View } from 'react-native'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'

import { AppText, BottomSheet, Button, Screen, TextField } from '@/components/ui'
import { useCategories } from '@/features/catalog/hooks'
import { resultsHref } from '@/features/catalog/routes'
import { findCategoryName, flattenCategories, parseQuery, toParams } from '@/features/catalog/utils'
import { colors } from '@/theme'

const digitsOnly = (text: string) => text.replace(/\D/g, '').slice(0, 15)
const withDots = (digits: string) => digits.replace(/\B(?=(\d{3})+(?!\d))/g, '.')

function PriceField({
  label,
  value,
  placeholder,
  error,
  onChange,
  testID,
}: {
  label: string
  value: string
  placeholder: string
  error?: string
  onChange: (digits: string) => void
  testID: string
}) {
  return (
    <TextField
      testID={testID}
      label={label}
      value={withDots(value)}
      onChangeText={(t) => onChange(digitsOnly(t))}
      placeholder={placeholder}
      keyboardType="number-pad"
      error={error}
      right={value ? <AppText color="textSecondary">đ</AppText> : undefined}
    />
  )
}

export default function SearchFilterScreen() {
  const params = useLocalSearchParams()
  const query = parseQuery(params)
  const categories = useCategories()
  const [categoryId, setCategoryId] = useState(query.categoryId)
  const [minPrice, setMinPrice] = useState(query.minPrice?.toString() ?? '')
  const [maxPrice, setMaxPrice] = useState(query.maxPrice?.toString() ?? '')
  const [pickerOpen, setPickerOpen] = useState(false)

  const rangeInvalid = !!minPrice && !!maxPrice && Number(minPrice) > Number(maxPrice)
  const categoryName = findCategoryName(categories.data, categoryId)
  const categoryRows: { id?: string; name: string; depth: number }[] = [
    { name: 'Tất cả danh mục', depth: 0 },
    ...flattenCategories(categories.data).map(({ category, depth }) => ({
      id: category.id,
      name: category.name,
      depth,
    })),
  ]

  const apply = () => {
    if (rangeInvalid) return
    const next = toParams({
      ...query,
      categoryId,
      minPrice: minPrice ? Number(minPrice) : undefined,
      maxPrice: maxPrice ? Number(maxPrice) : undefined,
    })
    router.dismissTo(resultsHref(next))
  }

  const clear = () => {
    setCategoryId(undefined)
    setMinPrice('')
    setMaxPrice('')
  }

  return (
    <Screen title="Lọc kết quả tìm kiếm" onBack={() => router.back()} testID="filter-screen">
      {query.keyword ? <AppText>Từ khóa: {query.keyword}</AppText> : null}

      <TextField
        testID="filter-category"
        label="Danh mục"
        picker
        value={categoryName}
        placeholder="Tất cả danh mục"
        onPress={() => setPickerOpen(true)}
      />
      <PriceField
        testID="filter-min-price"
        label="Giá từ"
        value={minPrice}
        placeholder="0đ"
        onChange={setMinPrice}
      />
      <PriceField
        testID="filter-max-price"
        label="Giá đến"
        value={maxPrice}
        placeholder="Không giới hạn"
        error={rangeInvalid ? 'Giá đến phải lớn hơn hoặc bằng giá từ' : undefined}
        onChange={setMaxPrice}
      />

      <Button
        testID="filter-apply"
        title="Áp dụng bộ lọc"
        disabled={rangeInvalid}
        onPress={apply}
      />
      <Button testID="filter-clear" title="Xóa bộ lọc" kind="secondary" onPress={clear} />

      <BottomSheet
        visible={pickerOpen}
        onClose={() => setPickerOpen(false)}
        title="Danh mục"
        testID="category-sheet"
      >
        {categoryRows.map((row) => (
          <Pressable
            key={row.id ?? 'all'}
            testID={`category-${row.id ?? 'all'}`}
            accessibilityRole="button"
            onPress={() => {
              setCategoryId(row.id)
              setPickerOpen(false)
            }}
            style={[styles.row, { paddingLeft: row.depth * 16 }]}
          >
            <AppText color={row.id === categoryId ? 'primary700' : 'text'}>{row.name}</AppText>
          </Pressable>
        ))}
        {categories.isError ? (
          <View>
            <AppText color="danger">Không tải được danh mục.</AppText>
            <Button title="Thử lại" kind="secondary" onPress={() => void categories.refetch()} />
          </View>
        ) : null}
      </BottomSheet>
    </Screen>
  )
}

const styles = StyleSheet.create({
  row: {
    minHeight: 48,
    justifyContent: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
})
