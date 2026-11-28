import client from './client'

export const listBuyerOrders = async (signal) => (await client.get('/api/orders', { signal })).data

export const listBuyerNotifications = async (page, signal) =>
  (await client.get('/api/notifications', { params: { page, size: 20 }, signal })).data
