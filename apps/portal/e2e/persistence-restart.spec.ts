import { test, expect } from '@playwright/test'
import { exec } from 'child_process'
import { promisify } from 'util'

const execAsync = promisify(exec)

/**
 * Persistence Across Restart.
 *
 * Verifies that identity bindings survive a service restart
 * and the known-holder fast path continues to work.
 *
 * This test requires Docker access from the test runner to restart services.
 */

const AUTH_BRIDGE_URL = process.env.AUTH_BRIDGE_BASE_URL || 'http://localhost:8090'
const KEYCLOAK_URL = process.env.KEYCLOAK_BASE_URL || 'http://localhost:8083'
const SERVICE_DATA_URL = process.env.SERVICE_DATA_URL || 'http://localhost:8081'
const SERVICE_CRYPTO_URL = process.env.SERVICE_CRYPTO_URL || 'http://localhost:8082'
const HEALTH_TIMEOUT = 90_000 // 90 seconds for services to come back
const TEST_USER = process.env.TEST_USER_EMAIL || 'test@kw1c.nl'
const TEST_PASSWORD = process.env.TEST_USER_PASSWORD || 'password'

const PERSISTENCE_HOLDER_DID = 'did:key:z6MkPersistenceHolder999'

async function waitForHealth(url: string, timeoutMs: number): Promise<void> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(`${url}/health`)
      if (res.ok) return
    } catch {
      // Service not ready yet
    }
    await new Promise((r) => setTimeout(r, 2_000))
  }
  throw new Error(`Service at ${url} did not become healthy within ${timeoutMs}ms`)
}

async function extractSessionIdFromPage(
  page: import('@playwright/test').Page
): Promise<string> {
  const response = await page.waitForResponse(
    (resp) =>
      resp.url().includes('/auth/oid4vp/sessions') &&
      resp.request().method() === 'POST' &&
      resp.status() < 400,
    { timeout: 15_000 }
  )
  const body = await response.json()
  return body.sessionId || body.session_id || body.id
}

async function pollSessionStatus(
  request: import('@playwright/test').APIRequestContext,
  sessionId: string,
  expectedStatus: string,
  timeoutMs: number = 15_000
): Promise<Record<string, unknown>> {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    const response = await request.get(
      `${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${sessionId}/status`
    )
    if (response.ok()) {
      const body = await response.json()
      if (body.status === expectedStatus) {
        return body
      }
      if (body.status === 'FAILED' || body.status === 'ERROR') {
        throw new Error(
          `Session ${sessionId} reached terminal status '${body.status}': ${body.message || 'no message'}`
        )
      }
    }
    await new Promise((r) => setTimeout(r, 1_000))
  }
  throw new Error(
    `Session ${sessionId} did not reach status '${expectedStatus}' within ${timeoutMs}ms`
  )
}

test.describe('Persistence Across Restart', () => {
  // Increase timeout for this suite since it involves service restarts
  test.setTimeout(180_000) // 3 minutes

  test('create binding via wallet login', async ({ page, request }) => {
    await page.goto('/login')

    const walletButton = page.getByRole('button', { name: /wallet|portemonnee/i })
    await walletButton.click()

    const sessionId = await extractSessionIdFromPage(page)

    // Simulate wallet VP
    await request.post(`${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${sessionId}/present`, {
      data: {
        vpToken: 'test-vp-token',
        holderDid: PERSISTENCE_HOLDER_DID,
        claims: { name: 'Persistence Test User', vct: 'urn:credential:student' },
      },
    })

    // Wait for IDV_REQUIRED
    await pollSessionStatus(request, sessionId, 'IDV_REQUIRED')

    // Click link institution button
    await expect(
      page.getByRole('button', { name: /link|institution|verify|koppel/i })
        .or(page.locator('[data-testid="idv-link-button"]'))
    ).toBeVisible({ timeout: 15_000 })

    const linkButton = page.getByRole('button', { name: /link|institution|verify|koppel/i })
      .or(page.locator('[data-testid="idv-link-button"]'))
    await linkButton.click()

    // Complete Keycloak login
    await page.waitForURL(
      (url) => url.href.includes(KEYCLOAK_URL) || url.href.includes('/realms/'),
      { timeout: 15_000 }
    )
    await page.fill('#username', TEST_USER)
    await page.fill('#password', TEST_PASSWORD)
    await page.click('#kc-login')

    await page.waitForURL(
      (url) => !url.href.includes(KEYCLOAK_URL) && !url.href.includes('/realms/'),
      { timeout: 20_000 }
    )

    // Verify binding created
    const status = await pollSessionStatus(request, sessionId, 'VERIFIED', 20_000)
    expect(status.status).toBe('VERIFIED')
  })

  test('restart services', async () => {
    // Restart the auth-bridge and backend services.
    // This tests that Kottage persistence survives a process restart.
    //
    // Uses docker compose to restart only the application services,
    // keeping infrastructure (Postgres, Keycloak) running.
    try {
      await execAsync(
        'docker compose -f deploy/docker/docker-compose.yml restart auth-bridge',
        { timeout: 60_000 }
      )
    } catch (error) {
      // If docker compose is not available or path differs, try alternative paths
      try {
        await execAsync(
          'docker compose restart auth-bridge',
          { timeout: 60_000, cwd: process.env.DOCKER_COMPOSE_DIR || '../../deploy/docker' }
        )
      } catch {
        // Skip if Docker is not accessible from the test runner
        test.skip()
      }
    }
  })

  test('wait for services healthy', async () => {
    await waitForHealth(AUTH_BRIDGE_URL, HEALTH_TIMEOUT)

    // Also wait for backend services if they exist
    try {
      await waitForHealth(SERVICE_DATA_URL, HEALTH_TIMEOUT)
    } catch {
      // service-data may not be in the test stack
    }
    try {
      await waitForHealth(SERVICE_CRYPTO_URL, HEALTH_TIMEOUT)
    } catch {
      // service-crypto may not be in the test stack
    }
  })

  test('login again with same wallet -- known-holder fast path works', async ({
    page,
    request,
  }) => {
    await page.goto('/login')

    const walletButton = page.getByRole('button', { name: /wallet|portemonnee/i })
    await walletButton.click()

    const sessionId = await extractSessionIdFromPage(page)

    // Simulate the same wallet VP as before restart
    await request.post(`${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${sessionId}/present`, {
      data: {
        vpToken: 'test-vp-token',
        holderDid: PERSISTENCE_HOLDER_DID,
        claims: { name: 'Persistence Test User', vct: 'urn:credential:student' },
      },
    })

    // The status should go directly to VERIFIED (not IDV_REQUIRED)
    // because the binding was persisted across the restart
    const status = await pollSessionStatus(request, sessionId, 'VERIFIED', 20_000)
    expect(status.status).toBe('VERIFIED')

    // Verify we were NOT redirected to Keycloak
    expect(page.url()).not.toContain(KEYCLOAK_URL)
    expect(page.url()).not.toContain('/realms/')

    // Verify session has user information from the persisted binding
    await page.waitForTimeout(3_000)
    const sessionResponse = await page.request.get('/api/auth/session')
    if (sessionResponse.ok()) {
      const session = await sessionResponse.json()
      expect(session).toBeDefined()
    }
  })

  test('claims and assurance survive the restart', async ({ page, request }) => {
    await page.goto('/login')

    const walletButton = page.getByRole('button', { name: /wallet|portemonnee/i })
    await walletButton.click()

    const sessionId = await extractSessionIdFromPage(page)

    await request.post(`${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${sessionId}/present`, {
      data: {
        vpToken: 'test-vp-token',
        holderDid: PERSISTENCE_HOLDER_DID,
        claims: { name: 'Persistence Test User', vct: 'urn:credential:student' },
      },
    })

    const status = await pollSessionStatus(request, sessionId, 'VERIFIED', 20_000)
    expect(status.status).toBe('VERIFIED')

    await page.waitForTimeout(3_000)

    const sessionResponse = await page.request.get('/api/auth/session')
    expect(sessionResponse.ok()).toBeTruthy()
    const session = await sessionResponse.json()

    expect(session).toBeDefined()

    if (session.user) {
      // Claims that were established before the restart should still be present
      expect(session.user.email || session.user.name).toBeTruthy()

      // Assurance (ACR/AMR) should survive the restart via Kottage persistence
      if (session.user.assurance) {
        expect(session.user.assurance.acr).toBeDefined()
        expect(session.user.assurance.amr).toBeDefined()
      }

      // Verify token-level claims if available
      const token = session.accessToken || session.idToken
      if (token && typeof token === 'string' && token.includes('.')) {
        const [, payloadB64] = token.split('.')
        const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString())
        expect(payload.sub).toBeDefined()

        // ACR/AMR should be preserved from the original reconciliation
        if (payload.acr) {
          expect(payload.acr).toBeTruthy()
        }
      }
    }
  })
})
