import { useMutation, useQueryClient } from '@tanstack/react-query'
import { isAxiosError } from 'axios'
import { router, useLocalSearchParams } from 'expo-router'
import { useState } from 'react'
import { Pressable, StyleSheet, View } from 'react-native'

import { addCartItem } from '@/api/cart'
import { getApiError } from '@/api/client'
import {
  AppText,
  Button,
  Card,
  ErrorState,
  InfoCard,
  QuantityStepper,
  Screen,
  Skeleton,
  TextField,
  useToast,
} from '@/components/ui'
import { useRequireAuth } from '@/auth/useRequireAuth'
import { ImageGallery } from '@/features/catalog/components/ImageGallery'
import { VariantSheet } from '@/features/catalog/components/VariantSheet'
import { useProductDetail, useShop } from '@/features/catalog/hooks'
import { reviewsHref, similarHref } from '@/features/catalog/routes'
import { defaultVariant, hasVariantOptions, variantLabel } from '@/features/catalog/utils'
import { formatVnd } from '@/lib/format'

const MAX_PER_ITEM = 999
const DESCRIPTION_LINES = 6

function addToCartMessage(error: unknown): string {
  return isAxiosError(error)
    ? getApiError(error).message
    : error instanceof Error
      ? error.message
      : 'Không thể thêm vào giỏ hàng. Vui lòng thử lại.'
}

export default function ProductDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>()
  const detail = useProductDetail(id)
  const product = detail.data?.product
  const shop = useShop(product?.shopId)
  const toast = useToast()
  const requireAuth = useRequireAuth()
  const queryClient = useQueryClient()

  const [variantId, setVariantId] = useState<string>()
  const [quantity, setQuantity] = useState(1)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [expanded, setExpanded] = useState(false)

  const variants = product?.variants ?? []
  const variant = variants.find((v) => v.id === variantId) ?? defaultVariant(variants)
  const stock = variant ? variant.availableStock : (product?.availableStock ?? 0)
  const maxQuantity = Math.max(1, Math.min(stock, MAX_PER_ITEM))
  const qty = Math.min(quantity, maxQuantity)

  const addToCart = useMutation({
    mutationFn: () => addCartItem({ productId: id, variantId: variant?.id ?? null, quantity: qty }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['cart'] })
      toast.show({ type: 'success', message: 'Đã thêm vào giỏ hàng' })
    },
    onError: (error) => toast.show({ type: 'error', message: addToCartMessage(error) }),
  })

  const reload = () => void detail.refetch()
  const similar = similarHref(id)

  if (detail.isPending) {
    return (
      <Screen title="Chi tiết sản phẩm" onBack={() => router.back()} testID="product-loading">
        <Skeleton height={240} radius={10} />
        <Skeleton height={31} width="80%" />
        <Skeleton height={31} width="40%" />
        <Skeleton height={21} width="60%" />
        <Skeleton height={48} radius={10} />
      </Screen>
    )
  }

  const notFound = detail.isError && getApiError(detail.error).status === 404
  const unavailable =
    notFound || (!!product && (product.status !== 'ACTIVE' || product.moderationRemoved))
  if (unavailable || !product) {
    return (
      <Screen title="Chi tiết sản phẩm" onBack={() => router.back()} testID="product-unavailable">
        {unavailable ? (
          <>
            <InfoCard
              title="Sản phẩm không còn khả dụng"
              body="Sản phẩm đã bị gỡ hoặc ngừng bán."
            />
            <Button
              title="Về trang chủ"
              kind="secondary"
              onPress={() => router.replace('/(buyer)')}
            />
          </>
        ) : (
          <ErrorState error={detail.error} onRetry={reload} />
        )}
      </Screen>
    )
  }

  const outOfStock = stock <= 0
  const options = hasVariantOptions(variants)
  const price = variant
    ? formatVnd(variant.price)
    : product.minPrice !== null &&
        product.maxPrice !== null &&
        product.minPrice !== product.maxPrice
      ? `${formatVnd(product.minPrice)} – ${formatVnd(product.maxPrice)}`
      : formatVnd(product.minPrice)
  const description = product.description ?? ''
  const longDescription =
    description.length > 220 || description.split('\n').length > DESCRIPTION_LINES

  const submit = () => {
    if (!requireAuth() || addToCart.isPending || outOfStock) return
    addToCart.mutate()
  }

  return (
    <Screen
      testID="product-screen"
      title="Chi tiết sản phẩm"
      onBack={() => router.back()}
      refreshing={detail.isRefetching}
      onRefresh={reload}
    >
      <ImageGallery urls={product.imageUrls} />
      <AppText variant="h2" testID="product-name">
        {product.name}
      </AppText>
      <AppText variant="h2" color="primary700" testID="product-price">
        {price}
      </AppText>
      {shop.data ? (
        <AppText testID="product-shop">
          {[shop.data.name, shop.data.province].filter(Boolean).join(' · ')}
        </AppText>
      ) : null}

      {options && variant ? (
        <>
          <AppText variant="label">Phân loại</AppText>
          <TextField
            testID="product-variant"
            picker
            value={variantLabel(variant) || variant.sku}
            onPress={() => setSheetOpen(true)}
          />
        </>
      ) : null}

      {outOfStock ? (
        <>
          <AppText testID="product-summary">
            {[product.name, variant && options ? variantLabel(variant) : null]
              .filter(Boolean)
              .join(' · ')}
          </AppText>
          <InfoCard
            testID="product-out-of-stock"
            title="Biến thể đã hết hàng"
            body={
              product.availableStock <= 0
                ? 'Sản phẩm tạm hết hàng. Xem sản phẩm tương tự.'
                : 'Chọn size khác hoặc xem sản phẩm tương tự.'
            }
          />
          <Button testID="product-add" title="Thêm vào giỏ" disabled onPress={submit} />
          <Button
            testID="product-similar"
            title="Xem sản phẩm tương tự"
            kind="secondary"
            onPress={() => router.push(similar)}
          />
        </>
      ) : (
        <>
          <AppText color="textSecondary" testID="product-stock">
            Còn {stock} sản phẩm · Số lượng {qty}
          </AppText>
          <View style={styles.qtyRow}>
            <AppText variant="label">Số lượng</AppText>
            <QuantityStepper
              testID="product-qty"
              value={qty}
              min={1}
              max={maxQuantity}
              onChange={setQuantity}
            />
          </View>
          <Button
            testID="product-add"
            title="Thêm vào giỏ"
            loading={addToCart.isPending}
            onPress={submit}
          />
        </>
      )}

      <Card testID="product-description">
        <AppText variant="label">Thông tin sản phẩm</AppText>
        <AppText numberOfLines={expanded ? undefined : DESCRIPTION_LINES}>{description}</AppText>
        {longDescription ? (
          <Pressable
            testID="product-description-toggle"
            accessibilityRole="button"
            onPress={() => setExpanded((e) => !e)}
          >
            <AppText variant="label" color="primary600">
              {expanded ? 'Thu gọn' : 'Xem thêm'}
            </AppText>
          </Pressable>
        ) : null}
      </Card>

      <Card testID="product-reviews" onPress={() => router.push(reviewsHref(id))}>
        <AppText variant="label">Đánh giá và sản phẩm tương tự</AppText>
        {product.ratingCount > 0 || product.soldCount > 0 ? (
          <AppText variant="caption" color="textSecondary">
            {[
              product.ratingCount > 0
                ? `★ ${product.ratingAverage.toFixed(1)} · ${product.ratingCount} đánh giá`
                : null,
              `${product.soldCount} đã bán`,
            ]
              .filter(Boolean)
              .join(' · ')}
          </AppText>
        ) : null}
        <AppText>Xem nhận xét của người đã mua và các lựa chọn cùng danh mục.</AppText>
      </Card>

      {outOfStock ? null : (
        <Button
          testID="product-similar"
          title="Xem sản phẩm tương tự"
          kind="secondary"
          onPress={() => router.push(similar)}
        />
      )}

      <VariantSheet
        visible={sheetOpen}
        variants={variants}
        selected={variant}
        onSelect={(v) => setVariantId(v.id)}
        onClose={() => setSheetOpen(false)}
      />
    </Screen>
  )
}

const styles = StyleSheet.create({
  qtyRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
})
