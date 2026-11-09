import { useId } from 'react'
import { PRODUCT_LIMITS } from '../services/api/productApi'
import { flattenTree } from '../utils/category'
import { emptyVariant } from '../utils/productForm'
import ProductImagesField from './ProductImagesField'

function FieldError({ id, message }) {
  if (!message) return null
  return (
    <p id={id} className="field-error" role="alert">
      {message}
    </p>
  )
}

function VariantRow({ variant, index, errors, onChange, onRemove, canRemove, disabled }) {
  const prefix = `variants.${index}`
  const base = useId()
  const set = (changes) => onChange({ ...variant, ...changes })
  const setAttribute = (position, changes) =>
    set({
      attributes: variant.attributes.map((item, i) =>
        i === position ? { ...item, ...changes } : item,
      ),
    })
  return (
    <fieldset className="variant-row">
      <legend>Phân loại {index + 1}</legend>
      <div className="variant-row__grid">
        <label className="product-field">
          <span>SKU *</span>
          <input
            value={variant.sku}
            maxLength={PRODUCT_LIMITS.sku}
            disabled={disabled}
            aria-invalid={Boolean(errors[`${prefix}.sku`])}
            aria-describedby={`${base}-sku`}
            onChange={(event) => set({ sku: event.target.value })}
          />
          <FieldError id={`${base}-sku`} message={errors[`${prefix}.sku`]} />
        </label>
        <label className="product-field">
          <span>Giá bán (₫) *</span>
          <input
            inputMode="numeric"
            value={variant.price}
            disabled={disabled}
            aria-invalid={Boolean(errors[`${prefix}.price`])}
            aria-describedby={`${base}-price`}
            onChange={(event) => set({ price: event.target.value.replace(/[^\d]/g, '') })}
          />
          <FieldError id={`${base}-price`} message={errors[`${prefix}.price`]} />
        </label>
        <label className="product-field">
          <span>Tồn kho *</span>
          <input
            inputMode="numeric"
            value={variant.stock}
            disabled={disabled}
            aria-invalid={Boolean(errors[`${prefix}.stock`])}
            aria-describedby={`${base}-stock`}
            onChange={(event) => set({ stock: event.target.value.replace(/[^\d]/g, '') })}
          />
          {variant.reservedStock > 0 && (
            <small className="product-field__hint">
              {variant.reservedStock} đang được giữ cho đơn chưa hoàn tất
            </small>
          )}
          <FieldError id={`${base}-stock`} message={errors[`${prefix}.stock`]} />
        </label>
      </div>
      <div className="variant-row__attributes">
        <span className="variant-row__label">Thuộc tính (ví dụ: Màu – Đen, Size – L)</span>
        {variant.attributes.map((item, position) => (
          <div className="variant-row__attribute" key={position}>
            <input
              aria-label={`Tên thuộc tính ${position + 1} của phân loại ${index + 1}`}
              placeholder="Tên (Màu)"
              value={item.name}
              disabled={disabled}
              onChange={(event) => setAttribute(position, { name: event.target.value })}
            />
            <input
              aria-label={`Giá trị thuộc tính ${position + 1} của phân loại ${index + 1}`}
              placeholder="Giá trị (Đen)"
              value={item.value}
              disabled={disabled}
              onChange={(event) => setAttribute(position, { value: event.target.value })}
            />
            <button
              type="button"
              className="text-button"
              aria-label={`Bỏ thuộc tính ${position + 1}`}
              disabled={disabled || variant.attributes.length === 1}
              onClick={() =>
                set({ attributes: variant.attributes.filter((_, i) => i !== position) })
              }
            >
              Bỏ
            </button>
          </div>
        ))}
        <button
          type="button"
          className="text-button"
          disabled={disabled}
          onClick={() => set({ attributes: [...variant.attributes, { name: '', value: '' }] })}
        >
          + Thêm thuộc tính
        </button>
        <FieldError message={errors[`${prefix}.attributes`]} />
      </div>
      {canRemove && (
        <button
          type="button"
          className="text-button variant-row__remove"
          disabled={disabled || variant.reservedStock > 0}
          title={variant.reservedStock > 0 ? 'Phân loại đang được giữ cho đơn hàng' : undefined}
          onClick={onRemove}
        >
          Xoá phân loại
        </button>
      )}
    </fieldset>
  )
}

/** Controlled product editor. `setForm` accepts a value or an updater function. */
export default function ProductForm({ form, setForm, errors, categories, disabled, onUploaded }) {
  const ids = useId()
  const options = flattenTree(categories)
  const set = (changes) => setForm((current) => ({ ...current, ...changes }))
  const setImages = (updater) =>
    setForm((current) => ({
      ...current,
      imageUrls: typeof updater === 'function' ? updater(current.imageUrls) : updater,
    }))
  const setVariant = (index, variant) =>
    setForm((current) => ({
      ...current,
      variants: current.variants.map((item, i) => (i === index ? variant : item)),
    }))

  return (
    <div className="product-form">
      <section className="account-card product-form__section" aria-labelledby={`${ids}-basic`}>
        <h2 id={`${ids}-basic`}>Thông tin cơ bản</h2>
        <label className="product-field">
          <span>Tên sản phẩm *</span>
          <input
            value={form.name}
            maxLength={PRODUCT_LIMITS.name}
            disabled={disabled}
            aria-invalid={Boolean(errors.name)}
            aria-describedby={`${ids}-name`}
            onChange={(event) => set({ name: event.target.value })}
          />
          <small className="product-field__hint">
            {form.name.length}/{PRODUCT_LIMITS.name}
          </small>
          <FieldError id={`${ids}-name`} message={errors.name} />
        </label>
        <label className="product-field">
          <span>Danh mục *</span>
          <select
            value={form.categoryId}
            disabled={disabled}
            aria-invalid={Boolean(errors.categoryId)}
            aria-describedby={`${ids}-category`}
            onChange={(event) => set({ categoryId: event.target.value })}
          >
            <option value="">Chọn danh mục</option>
            {options.map((node) => (
              <option key={node.id} value={node.id}>
                {`${'— '.repeat(node.depth)}${node.name}`}
              </option>
            ))}
          </select>
          <FieldError id={`${ids}-category`} message={errors.categoryId} />
        </label>
        <label className="product-field">
          <span>Mô tả</span>
          <textarea
            rows={6}
            value={form.description}
            maxLength={PRODUCT_LIMITS.description}
            disabled={disabled}
            aria-invalid={Boolean(errors.description)}
            onChange={(event) => set({ description: event.target.value })}
          />
          <FieldError message={errors.description} />
        </label>
      </section>

      <section className="account-card product-form__section" aria-label="Ảnh sản phẩm">
        <ProductImagesField
          value={form.imageUrls}
          onChange={setImages}
          onUploaded={onUploaded}
          error={errors.imageUrls}
          disabled={disabled}
        />
      </section>

      <section className="account-card product-form__section" aria-labelledby={`${ids}-variants`}>
        <div className="product-form__section-head">
          <h2 id={`${ids}-variants`}>Phân loại & tồn kho</h2>
          <button
            type="button"
            className="account-button account-button--secondary"
            disabled={disabled}
            onClick={() => set({ variants: [...form.variants, emptyVariant()] })}
          >
            + Thêm phân loại
          </button>
        </div>
        {form.variants.map((variant, index) => (
          <VariantRow
            key={variant.key}
            variant={variant}
            index={index}
            errors={errors}
            disabled={disabled}
            canRemove={form.variants.length > 1}
            onChange={(next) => setVariant(index, next)}
            onRemove={() =>
              setForm((current) => ({
                ...current,
                variants: current.variants.filter((item) => item.key !== variant.key),
              }))
            }
          />
        ))}
      </section>
    </div>
  )
}
