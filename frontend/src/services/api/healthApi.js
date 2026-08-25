import apiClient from './apiClient'

export const getHealth = async () => {
  const response = await apiClient.get('/api/auth/health')
  return response.data
}
