import apiClient from './apiClient'

export const getHealth = async () => {
  const response = await apiClient.get('/api/health')
  return response.data
}
