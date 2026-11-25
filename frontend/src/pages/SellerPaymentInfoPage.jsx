import { useEffect, useState } from 'react'
import AccountField from '../components/AccountField'
import BankSelect from '../components/BankSelect'
import { validateBankAccount } from '../auth/validation'
import { findBank } from '../data/vietnamBanks'
import { getApiError } from '../services/api/apiClient'
import { getMyShop, updateMyBankAccount } from '../services/api/shopApi'
import '../components/account.css'
import '../components/accountForms.css'
import '../components/seller.css'

const valuesFrom = (shop) => ({
  bankCode: shop?.bankCode || '',
  bankAccountNumber: shop?.bankAccountNumber || '',
})

function CurrentAccount({ shop }) {
  const bank = findBank(shop.bankCode)
  if (!shop.bankCode || !shop.bankAccountNumber) {
    return (
      <p className="account-notice account-notice--warning" role="status">
        Gian hàng chưa có tài khoản nhận tiền. Vui lòng bổ sung bên dưới.
      </p>
    )
  }
  return (
    <dl className="seller-payment__current">
      <div>
        <dt>Ngân hàng</dt>
        <dd>
          <strong>{bank?.shortName || shop.bankCode}</strong>
          {bank && <small>{bank.name}</small>}
        </dd>
      </div>
      <div>
        <dt>Số tài khoản</dt>
        <dd>
          <strong className="seller-payment__number">{shop.bankAccountNumber}</strong>
        </dd>
      </div>
    </dl>
  )
}

/** Thong tin thanh toan: tai khoan ngan hang nhan tien cua gian hang, xem va doi ngay tai day. */
export default function SellerPaymentInfoPage() {
  const [state, setState] = useState({ status: 'loading', shop: null, error: '' })
  const [values, setValues] = useState(valuesFrom(null))
  const [fieldErrors, setFieldErrors] = useState({})
  const [message, setMessage] = useState({ type: '', text: '' })
  const [saving, setSaving] = useState(false)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let current = true
    getMyShop()
      .then((shop) => {
        if (!current) return
        setState({ status: 'ready', shop, error: '' })
        setValues(valuesFrom(shop))
      })
      .catch(
        (failure) =>
          current && setState({ status: 'error', shop: null, error: getApiError(failure).message }),
      )
    return () => {
      current = false
    }
  }, [reload])

  const saved = valuesFrom(state.shop)
  const dirty =
    values.bankCode !== saved.bankCode ||
    values.bankAccountNumber.trim() !== saved.bankAccountNumber

  function update(name, value) {
    setValues((current) => ({ ...current, [name]: value }))
    setFieldErrors((current) => ({ ...current, [name]: undefined }))
    setMessage({ type: '', text: '' })
  }

  async function onSubmit(event) {
    event.preventDefault()
    if (saving) return
    const errors = validateBankAccount(values)
    setFieldErrors(errors)
    if (Object.keys(errors).length) return
    setSaving(true)
    setMessage({ type: '', text: '' })
    try {
      const shop = await updateMyBankAccount(values)
      setState({ status: 'ready', shop, error: '' })
      setValues(valuesFrom(shop))
      setMessage({ type: 'success', text: 'Đã cập nhật tài khoản nhận tiền.' })
    } catch (failure) {
      const apiError = getApiError(failure)
      setFieldErrors(apiError.fieldErrors || {})
      setMessage({ type: 'error', text: apiError.message })
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="seller-page" aria-labelledby="seller-payment-heading">
      <div className="seller-page__header">
        <span className="account-eyebrow">Kênh người bán</span>
        <h1 id="seller-payment-heading">Thông tin thanh toán</h1>
        <p className="account-subtitle">
          Tài khoản ngân hàng VMarket dùng để chuyển tiền bán hàng cho gian hàng của bạn.
        </p>
      </div>

      {state.status === 'loading' && (
        <p className="account-notice" role="status">
          Đang tải thông tin thanh toán…
        </p>
      )}
      {state.status === 'error' && (
        <section className="account-card account-state">
          <p role="alert">{state.error}</p>
          <button
            className="account-button"
            type="button"
            onClick={() => setReload((value) => value + 1)}
          >
            Thử lại
          </button>
        </section>
      )}
      {state.status === 'ready' && (
        <>
          <section className="account-card seller-payment" aria-labelledby="seller-payment-current">
            <h2 id="seller-payment-current">Tài khoản nhận tiền hiện tại</h2>
            <CurrentAccount shop={state.shop} />
          </section>
          <form
            className="account-card seller-payment profile-form"
            noValidate
            onSubmit={onSubmit}
            aria-labelledby="seller-payment-edit"
          >
            <h2 id="seller-payment-edit">Đổi tài khoản nhận tiền</h2>
            <div className="shop-form__row">
              <BankSelect
                required
                value={values.bankCode}
                error={fieldErrors.bankCode}
                disabled={saving}
                onChange={(code) => update('bankCode', code)}
              />
              <AccountField
                label="Số tài khoản"
                required
                name="bankAccountNumber"
                inputMode="numeric"
                maxLength={19}
                placeholder="Ví dụ: 0123456789"
                value={values.bankAccountNumber}
                error={fieldErrors.bankAccountNumber}
                disabled={saving}
                onChange={(event) => update('bankAccountNumber', event.target.value)}
              />
            </div>
            <p className="account-field__hint">
              Kiểm tra kỹ số tài khoản trước khi lưu. Mỗi lần thay đổi đều được ghi lại để VMarket
              đối chiếu khi cần.
            </p>
            {message.text && (
              <p
                className={`account-notice account-notice--${message.type}`}
                role={message.type === 'error' ? 'alert' : 'status'}
              >
                {message.text}
              </p>
            )}
            <div className="account-actions">
              <button className="account-button" type="submit" disabled={!dirty || saving}>
                {saving ? 'Đang lưu…' : 'Lưu thay đổi'}
              </button>
              {dirty && (
                <button
                  className="account-button account-button--secondary"
                  type="button"
                  disabled={saving}
                  onClick={() => {
                    setValues(saved)
                    setFieldErrors({})
                  }}
                >
                  Huỷ thay đổi
                </button>
              )}
            </div>
          </form>
        </>
      )}
    </section>
  )
}
