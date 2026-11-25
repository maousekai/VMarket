// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import SellerPaymentInfoPage from './SellerPaymentInfoPage'
import ShopForm from '../components/ShopForm'
import { getMyShop, updateMyBankAccount } from '../services/api/shopApi'

vi.mock('../services/api/shopApi', async (importOriginal) => ({
  ...(await importOriginal()),
  getMyShop: vi.fn(),
  updateMyBankAccount: vi.fn(),
}))

const shop = { id: 's1', name: 'Shop giày', bankCode: 'VCB', bankAccountNumber: '0123456789' }

const renderPage = () =>
  render(
    <MemoryRouter>
      <SellerPaymentInfoPage />
    </MemoryRouter>,
  )

function pickBank(query) {
  const combo = screen.getByRole('combobox', { name: /Ngân hàng/ })
  fireEvent.focus(combo)
  fireEvent.change(combo, { target: { value: query } })
  fireEvent.keyDown(combo, { key: 'Enter' })
}

beforeEach(() => {
  vi.clearAllMocks()
  getMyShop.mockResolvedValue(shop)
})

describe('SellerPaymentInfoPage', () => {
  it('shows the current account and saves a new bank and number', async () => {
    updateMyBankAccount.mockImplementation(async (values) => ({ ...shop, ...values }))
    renderPage()
    const current = (await screen.findByRole('heading', { name: 'Tài khoản nhận tiền hiện tại' }))
      .parentElement
    expect(within(current).getByText('Vietcombank')).toBeInTheDocument()
    expect(within(current).getByText('0123456789')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Lưu thay đổi' })
    expect(save).toBeDisabled()

    pickBank('ky thuong')
    expect(screen.getByRole('combobox', { name: /Ngân hàng/ })).toHaveValue(
      'Techcombank – Ngân hàng TMCP Kỹ thương Việt Nam',
    )
    fireEvent.change(screen.getByLabelText(/Số tài khoản/), { target: { value: '19036688990011' } })
    fireEvent.click(save)

    expect(await screen.findByText('Đã cập nhật tài khoản nhận tiền.')).toBeInTheDocument()
    expect(updateMyBankAccount).toHaveBeenCalledWith({
      bankCode: 'TCB',
      bankAccountNumber: '19036688990011',
    })
    expect(within(current).getByText('Techcombank')).toBeInTheDocument()
  })

  it('rejects an account number that is not 6-19 digits', async () => {
    renderPage()
    const number = await screen.findByLabelText(/Số tài khoản/)
    fireEvent.change(number, { target: { value: '12ab' } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu thay đổi' }))
    expect(await screen.findByText('Số tài khoản gồm 6 đến 19 chữ số.')).toBeInTheDocument()
    expect(updateMyBankAccount).not.toHaveBeenCalled()
  })

  it('asks a shop without bank details to add them', async () => {
    getMyShop.mockResolvedValue({ id: 's1', name: 'Shop cũ' })
    renderPage()
    expect(
      await screen.findByText('Gian hàng chưa có tài khoản nhận tiền. Vui lòng bổ sung bên dưới.'),
    ).toBeInTheDocument()
  })
})

describe('BankSelect', () => {
  it('filters by short or full name and picks with the keyboard', async () => {
    const onSubmit = vi.fn()
    render(<ShopForm withBank onSubmit={onSubmit} />)
    const combo = screen.getByRole('combobox', { name: /Ngân hàng/ })
    fireEvent.focus(combo)
    fireEvent.change(combo, { target: { value: 'quan doi' } })
    const options = within(screen.getByRole('listbox')).getAllByRole('option')
    expect(options).toHaveLength(1)
    expect(options[0]).toHaveTextContent('MB Bank')
    fireEvent.change(combo, { target: { value: 'không có' } })
    expect(screen.getByText('Không tìm thấy ngân hàng phù hợp.')).toBeInTheDocument()
    fireEvent.keyDown(combo, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('requires a bank account when registering a shop', async () => {
    const onSubmit = vi.fn()
    render(<ShopForm withBank onSubmit={onSubmit} />)
    fireEvent.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))
    expect(await screen.findAllByText('Vui lòng chọn ngân hàng.')).not.toHaveLength(0)
    expect(screen.getAllByText('Vui lòng nhập số tài khoản.')).not.toHaveLength(0)
    await waitFor(() => expect(onSubmit).not.toHaveBeenCalled())
  })
})
