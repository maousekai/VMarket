import { assertBackendHealthy, assertIsolatedStack } from './backend.js'
import process from 'node:process'

export default async function globalSetup() {
  assertIsolatedStack()
  assertBackendHealthy()
  const gateway = process.env.E2E_GATEWAY_URL || 'http://127.0.0.1:8080'
  for (const service of ['auth', 'shops']) {
    const response = await fetch(`${gateway}/api/${service}/health`)
    if (!response.ok) throw new Error(`Real ${service} service is not healthy (${response.status})`)
  }
}
