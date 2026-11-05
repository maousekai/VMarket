// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import ShopForm from './ShopForm'
import { deleteMyShopMedia, uploadMyShopLogo } from '../services/api/mediaApi'

vi.mock('../services/api/mediaApi', async (importOriginal) => ({
  ...(await importOriginal()),
  uploadMyShopLogo: vi.fn(),
  uploadMyShopCover: vi.fn(),
  deleteMyShopMedia: vi.fn(),
}))

const shop = {
  id: 'shop-1',
  name: 'Gốm Thanh Hà',
  description: '',
  logoUrl: 'https://cdn.example.com/logo.png',
  coverUrl: 'https://cdn.example.com/cover.jpg',
  policies: '',
  contactEmail: 'shop@example.com',
  contactPhone: '0905123456',
  province: 'Đà Nẵng',
  ward: 'Hòa An',
  streetAddress: '12 Phạm Phán',
}

const imageFile = (name = 'logo.png') =>
  new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: 'image/png' })

beforeEach(() => {
  vi.clearAllMocks()
  deleteMyShopMedia.mockResolvedValue(undefined)
})

afterEach(cleanup)

describe('ShopForm media', () => {
  it('uploads a chosen logo on save and sends the returned URL', async () => {
    const uploaded = 'http://localhost:9000/vmarket-media/shops/u1/logo/new.png'
    uploadMyShopLogo.mockResolvedValue({ url: uploaded })
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    const file = imageFile()
    render(<ShopForm initial={shop} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('Tệp logo'), { target: { files: [file] } })
    expect(uploadMyShopLogo).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(uploadMyShopLogo).toHaveBeenCalledWith(file)
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      name: 'Gốm Thanh Hà',
      logoUrl: uploaded,
      coverUrl: shop.coverUrl,
    })
  })

  it('removes an uploaded image when saving the shop fails', async () => {
    const uploaded = 'http://localhost:9000/vmarket-media/shops/u1/logo/new.png'
    uploadMyShopLogo.mockResolvedValue({ url: uploaded })
    const onSubmit = vi.fn().mockRejectedValue({
      response: {
        status: 409,
        data: { error: { code: 'SHOP_NAME_TAKEN', message: 'Tên đã dùng.' } },
      },
    })
    render(<ShopForm initial={shop} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('Tệp logo'), { target: { files: [imageFile()] } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    await waitFor(() => expect(deleteMyShopMedia).toHaveBeenCalledWith(uploaded))
    expect((await screen.findByRole('alert')).textContent).toContain('Tên đã dùng.')
  })

  it('does not delete the stored logo when the same image is re-uploaded and saving fails', async () => {
    // Content-addressed keys mean the same bytes return the URL already in the shop.
    uploadMyShopLogo.mockResolvedValue({ url: shop.logoUrl })
    const onSubmit = vi.fn().mockRejectedValue({
      response: {
        status: 409,
        data: { error: { code: 'SHOP_NAME_TAKEN', message: 'Tên đã dùng.' } },
      },
    })
    render(<ShopForm initial={shop} onSubmit={onSubmit} />)

    fireEvent.change(screen.getByLabelText('Tệp logo'), { target: { files: [imageFile()] } })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    await screen.findByRole('alert')
    expect(deleteMyShopMedia).not.toHaveBeenCalled()
  })

  it('keeps the URL input path working without uploading a file', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<ShopForm initial={shop} onSubmit={onSubmit} />)

    fireEvent.click(screen.getAllByRole('button', { name: 'Dùng đường dẫn' })[0])
    fireEvent.change(screen.getAllByLabelText('Đường dẫn ảnh')[0], {
      target: { value: 'https://cdn.example.com/new-logo.webp' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(uploadMyShopLogo).not.toHaveBeenCalled()
    expect(onSubmit.mock.calls[0][0].logoUrl).toBe('https://cdn.example.com/new-logo.webp')
  })
})
