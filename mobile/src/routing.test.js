// Kiem tra dinh tuyen THAT cua src/app (review PR 68 lan 4, muc 1.1): deep link va nut "Theo doi
// giao hang" phai mo dung man theo doi, khong bi day ve Trang chu.
jest.unmock('expo-router')

import { screen } from '@testing-library/react-native'
import * as SecureStore from 'expo-secure-store'
import { renderRouter } from 'expo-router/testing-library'
import { getDelivery, issueTrackingTicket, listMyAssignments } from './api/deliveryApi'

jest.mock('./api/deliveryApi', () => ({
  ...jest.requireActual('./api/deliveryApi'),
  getDelivery: jest.fn(),
  issueTrackingTicket: jest.fn(),
  listMyAssignments: jest.fn(async () => []),
  getShift: jest.fn(async () => ({ onShift: true, unpickedTrips: 0, activeTrips: 0 })),
}))
jest.mock('./api/shopApi', () => ({
  ...jest.requireActual('./api/shopApi'),
  browseProducts: jest.fn(async () => ({ content: [] })),
  getOrder: jest.fn(async () => null),
  getPaymentByOrder: jest.fn(async () => null),
}))
jest.mock('react-native-webview', () => ({ WebView: () => null }))

const encode = (value) =>
  globalThis.btoa(JSON.stringify(value)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')

function signIn(roles) {
  const token = `${encode({ alg: 'none' })}.${encode({
    sub: 'user-1',
    email: 'user@example.com',
    username: 'user',
    roles,
    exp: Math.floor(Date.now() / 1000) + 3600,
  })}.signature`
  SecureStore.__store.set('vmarket.accessToken', token)
}

beforeEach(() => {
  SecureStore.__store.clear()
  getDelivery.mockResolvedValue({
    assignment: { id: 'a1', status: 'ASSIGNED' },
    history: [],
  })
  issueTrackingTicket.mockResolvedValue({ ticket: 't' })
})

it('opens the tracking screen from a deep link for a buyer', async () => {
  signIn(['BUYER'])
  await renderRouter('./src/app', { initialUrl: '/delivery/order-1' })
  expect(await screen.findByText('Theo dõi đơn hàng')).toBeTruthy()
  expect(screen).toHavePathname('/delivery/order-1')
  expect(getDelivery).toHaveBeenCalledWith('order-1', expect.anything())
})

it('shows the three buyer tabs on the home screen', async () => {
  signIn(['BUYER'])
  await renderRouter('./src/app', { initialUrl: '/' })
  expect(await screen.findByText('VMarket · Đà Nẵng')).toBeTruthy()
  for (const tab of ['Trang chủ', 'Giỏ hàng', 'Tài khoản'])
    expect(screen.getByRole('tab', { name: tab })).toBeTruthy()
})

it('shows the three shipper tabs for a shipper account', async () => {
  signIn(['SHIPPER'])
  await renderRouter('./src/app', { initialUrl: '/' })
  expect(await screen.findByText('Đơn được phân công')).toBeTruthy()
  for (const tab of ['Đơn giao', 'Vị trí', 'Lịch sử'])
    expect(screen.getByRole('tab', { name: tab })).toBeTruthy()
  expect(listMyAssignments).toHaveBeenCalledWith({ scope: 'active' })
})

it('sends a signed-out user to the login screen', async () => {
  await renderRouter('./src/app', { initialUrl: '/delivery/order-1' })
  expect(await screen.findByText('Mua gần, chọn đúng.')).toBeTruthy()
})
