import { useCallback, useEffect, useState } from 'react'
import { Image, View } from 'react-native'
import { router } from 'expo-router'
import { getApiError } from '../api/client'
import { addCartItem, getProduct } from '../api/shopApi'
import {
  Body,
  Button,
  Card,
  Choice,
  Heading,
  Label,
  Loading,
  Notice,
  Price,
  Screen,
} from '../components/ui'
import { makeStyles, radius, spacing } from '../theme'
import BuyerNav from './BuyerNav'
import { formatPrice, priceRange, productImage, useShopLookup, variantLabel } from './catalog'

const MAX_QUANTITY = 999

/** Chi tiet san pham (frame 3.5, "San pham het hang"): phan loai, ton kho, so luong, them vao gio. */
export default function ProductScreen({ productId }) {
  const styles = useStyles()
  const [state, setState] = useState({ loading: true, product: null, similar: [], error: '' })
  const [variantId, setVariantId] = useState(null)
  const [quantity, setQuantity] = useState(1)
  const [adding, setAdding] = useState(false)
  const [result, setResult] = useState(null)
  const product = state.product
  const shops = useShopLookup([product?.shopId])
  const shopName = shops[product?.shopId]?.value?.name

  const load = useCallback(async () => {
    setState((previous) => ({ ...previous, loading: true, error: '' }))
    try {
      const data = await getProduct(productId)
      setState({
        loading: false,
        product: data.product,
        similar: data.similarProducts || [],
        error: '',
      })
      const variants = data.product?.variants || []
      const firstAvailable = variants.find((variant) => variant.availableStock > 0) || variants[0]
      setVariantId(firstAvailable?.id || null)
    } catch (failure) {
      const error = getApiError(failure)
      setState({
        loading: false,
        product: null,
        similar: [],
        error: error.status === 404 ? 'Sản phẩm không còn được bán.' : error.message,
      })
    }
  }, [productId])

  useEffect(() => {
    load()
  }, [load])

  const variants = product?.variants || []
  const variant = variants.find((item) => item.id === variantId) || null
  const stock = variant ? variant.availableStock : (product?.availableStock ?? 0)
  const soldOut = Boolean(product) && stock <= 0
  const maxQuantity = Math.max(1, Math.min(stock, MAX_QUANTITY))
  const image = productImage(product)

  async function add() {
    setAdding(true)
    setResult(null)
    try {
      await addCartItem({
        productId: product.id,
        variantId: variant?.id || null,
        shopId: product.shopId,
        quantity,
      })
      setResult({ tone: 'success', message: 'Đã thêm vào giỏ hàng.' })
    } catch (failure) {
      setResult({ tone: 'danger', message: getApiError(failure).message })
    } finally {
      setAdding(false)
    }
  }

  return (
    <Screen title="Chi tiết sản phẩm" footer={<BuyerNav active="home" />}>
      {state.loading && <Loading />}
      {state.error ? (
        <>
          <Notice tone="danger">{state.error}</Notice>
          <Button title="Về trang chủ" variant="secondary" onPress={() => router.navigate('/')} />
        </>
      ) : null}
      {product && (
        <>
          {image ? (
            <Image
              source={{ uri: image }}
              style={styles.image}
              accessibilityIgnoresInvertColors
              accessibilityLabel={product.name}
            />
          ) : (
            <View style={[styles.image, styles.placeholder]} />
          )}
          <Heading>{product.name}</Heading>
          <Price large>{variant ? formatPrice(variant.price) : priceRange(product)}</Price>
          <Body>{[shopName, 'Đà Nẵng'].filter(Boolean).join(' · ')}</Body>
          {variants.length > 0 && (
            <>
              <Label>Phân loại</Label>
              {variants.map((item) => (
                <Choice
                  key={item.id}
                  value={`${variantLabel(item) || item.sku || 'Mặc định'} · ${formatPrice(item.price)}${item.availableStock > 0 ? '' : ' · Hết hàng'}`}
                  selected={item.id === variantId}
                  onPress={() => {
                    setVariantId(item.id)
                    setQuantity(1)
                    setResult(null)
                  }}
                />
              ))}
            </>
          )}
          {soldOut ? (
            <Card title="Sản phẩm hết hàng">
              <Body>
                {variants.length > 1
                  ? 'Phân loại này đã hết hàng. Chọn phân loại khác hoặc quay lại sau.'
                  : 'Gian hàng sẽ cập nhật khi có hàng trở lại.'}
              </Body>
            </Card>
          ) : (
            <>
              <Body muted>{`Còn ${stock} sản phẩm · Số lượng ${quantity}`}</Body>
              <View style={styles.row}>
                <Button
                  title="−"
                  variant="secondary"
                  style={styles.flex}
                  disabled={quantity <= 1}
                  onPress={() => setQuantity((value) => Math.max(1, value - 1))}
                />
                <Button
                  title="+"
                  variant="secondary"
                  style={styles.flex}
                  disabled={quantity >= maxQuantity}
                  onPress={() => setQuantity((value) => Math.min(maxQuantity, value + 1))}
                />
              </View>
            </>
          )}
          <Button
            title="Thêm vào giỏ"
            loading={adding}
            disabled={soldOut || (variants.length > 0 && !variant)}
            onPress={add}
          />
          {result ? <Notice tone={result.tone}>{result.message}</Notice> : null}
          {result?.tone === 'success' ? (
            <Button
              title="Xem giỏ hàng"
              variant="secondary"
              onPress={() => router.navigate('/cart')}
            />
          ) : null}
          {product.description ? (
            <Card title="Thông tin sản phẩm">
              <Body>{product.description}</Body>
            </Card>
          ) : null}
          <Card title="Đánh giá và sản phẩm tương tự">
            <Body>
              {product.ratingCount
                ? `${Number(product.ratingAverage).toFixed(1)}/5 từ ${product.ratingCount} đánh giá · đã bán ${product.soldCount}`
                : `Chưa có đánh giá · đã bán ${product.soldCount || 0}`}
            </Body>
          </Card>
          {state.similar.slice(0, 4).map((item) => (
            <Card
              key={item.id}
              title={item.name}
              onPress={() => router.push({ pathname: '/product/[id]', params: { id: item.id } })}
              accessibilityLabel={`Xem ${item.name}`}
            >
              <Body>{priceRange(item)}</Body>
            </Card>
          ))}
        </>
      )}
    </Screen>
  )
}

const useStyles = makeStyles((colors) => ({
  image: {
    width: '100%',
    height: 240,
    borderRadius: radius.md,
    backgroundColor: colors.surfaceMuted,
  },
  placeholder: { borderWidth: 1, borderColor: colors.border },
  row: { flexDirection: 'row', gap: spacing(2) },
  flex: { flex: 1 },
}))
