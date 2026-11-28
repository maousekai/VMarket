import { Image, View } from 'react-native'
import { router } from 'expo-router'
import { Body, Button, Label, Price } from '../components/ui'
import { makeStyles, radius, spacing } from '../theme'
import { priceRange, productImage } from './catalog'

/** San pham trong danh sach (Trang chu 3.2 / Ket qua tim kiem 3.4): anh, ten, gia, nut xem. */
export default function ProductCard({ product, shopName, compact, actionLabel = 'Xem sản phẩm' }) {
  const styles = useStyles()
  const image = productImage(product)
  return (
    <View style={styles.card}>
      {image ? (
        <Image
          source={{ uri: image }}
          style={[styles.image, compact && styles.imageCompact]}
          accessibilityIgnoresInvertColors
          accessibilityLabel={product.name}
        />
      ) : (
        <View style={[styles.image, styles.placeholder, compact && styles.imageCompact]} />
      )}
      <Label>{product.name}</Label>
      {compact ? (
        <Body>{[priceRange(product), shopName].filter(Boolean).join(' · ')}</Body>
      ) : (
        <Price>{priceRange(product)}</Price>
      )}
      {product.availableStock === 0 ? <Body muted>Sản phẩm hết hàng</Body> : null}
      <Button
        title={actionLabel}
        onPress={() => router.push({ pathname: '/product/[id]', params: { id: product.id } })}
      />
    </View>
  )
}

const useStyles = makeStyles((colors) => ({
  card: { gap: spacing(3) },
  image: {
    width: '100%',
    height: 180,
    borderRadius: radius.md,
    backgroundColor: colors.surfaceMuted,
  },
  imageCompact: { height: 150 },
  placeholder: { borderWidth: 1, borderColor: colors.border },
}))
