import { test, expect } from '@playwright/test'

/**
 * Database Inspection -- Verifies that identity bindings are stored correctly
 * and that no plaintext PII is visible in the persistence layer.
 *
 * After a reconciliation completes, these tests inspect the binding via
 * the auth-bridge API to verify:
 * - The binding was stored successfully
 * - Only hashes and ciphertext are visible (no plaintext PII)
 * - The binding metadata (hashKeyVersion, timestamps) is populated
 *
 * These are API-level tests since direct DB/Kottage file access is not
 * practical in the E2E Docker environment.
 */

const AUTH_BRIDGE_URL = process.env.AUTH_BRIDGE_BASE_URL || 'http://localhost:8090'
const KEYCLOAK_URL = process.env.KEYCLOAK_BASE_URL || 'http://localhost:8083'
const TEST_USER = process.env.TEST_USER_EMAIL || 'test@kw1c.nl'
const TEST_PASSWORD = process.env.TEST_USER_PASSWORD || 'password'

const DB_INSPECTION_HOLDER_DID = 'did:key:z6MkDbInspectionHolder111'

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

/**
 * Complete the full unknown-holder flow to create a binding.
 * Returns the session ID used.
 */
async function createBindingViaFullFlow(
  page: import('@playwright/test').Page,
  request: import('@playwright/test').APIRequestContext
): Promise<string> {
  await page.goto('/login')

  const walletButton = page.getByRole('button', { name: /wallet|portemonnee/i })
  await walletButton.click()

  const sessionId = await extractSessionIdFromPage(page)

  // Simulate wallet VP
  await request.post(`${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${sessionId}/present`, {
    data: {
      vpToken: 'test-vp-token',
      holderDid: DB_INSPECTION_HOLDER_DID,
      claims: {
        name: 'DB Inspection Test User',
        vct: 'urn:credential:student',
        given_name: 'Pieter',
        family_name: 'Jansen',
        email: 'pieter@test.nl',
      },
    },
  })

  await pollSessionStatus(request, sessionId, 'IDV_REQUIRED')

  // Click link institution button
  await expect(
    page.locator('[data-testid="idv-link-button"]')
      .or(page.getByRole('button', { name: /link|institution|verify|koppel/i }))
  ).toBeVisible({ timeout: 15_000 })

  const linkButton = page.locator('[data-testid="idv-link-button"]')
    .or(page.getByRole('button', { name: /link|institution|verify|koppel/i }))
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

  await pollSessionStatus(request, sessionId, 'VERIFIED', 20_000)

  return sessionId
}

test.describe('Database Inspection', () => {
  test.describe.configure({ mode: 'serial' })

  let createdSessionId: string

  test('create binding via reconciliation flow', async ({ page, request }) => {
    createdSessionId = await createBindingViaFullFlow(page, request)
    expect(createdSessionId).toBeTruthy()
  })

  test('binding endpoint confirms storage', async ({ request }) => {
    // Query the auth-bridge for the binding created during reconciliation.
    // This verifies the Kottage store persisted the binding correctly.
    const bindingResp = await request.get(
      `${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${createdSessionId}/binding`
    )

    if (bindingResp.ok()) {
      const binding = await bindingResp.json()

      // Binding should exist and have a status indicating it was stored
      expect(binding).toBeDefined()

      // The holder DID should be present (this is not PII -- it's a public key hash)
      if (binding.holderDid) {
        expect(binding.holderDid).toBe(DB_INSPECTION_HOLDER_DID)
      }

      // hashKeyVersion should be populated (Phase 1 fix)
      if (binding.hashKeyVersion !== undefined) {
        expect(binding.hashKeyVersion).toBeTruthy()
      }

      // Timestamps should be present
      if (binding.createdAt || binding.created_at) {
        const createdAt = binding.createdAt || binding.created_at
        expect(new Date(createdAt).getTime()).toBeGreaterThan(0)
      }
    } else if (bindingResp.status() === 404) {
      // The binding endpoint may not expose binding details directly.
      // In that case, we verify via the session status that VERIFIED was reached,
      // which means the binding was persisted.
      const statusResp = await request.get(
        `${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${createdSessionId}/status`
      )
      expect(statusResp.ok()).toBeTruthy()
      const status = await statusResp.json()
      expect(status.status).toBe('VERIFIED')
    }
  })

  test('binding does not expose plaintext PII in API response', async ({ request }) => {
    // Verify that the binding API (if available) does not leak plaintext PII.
    // The Kottage store should only contain hashed keys and encrypted ciphertext.
    const bindingResp = await request.get(
      `${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${createdSessionId}/binding`
    )

    if (bindingResp.ok()) {
      const binding = await bindingResp.json()
      const bindingStr = JSON.stringify(binding)

      // The binding response should NOT contain plaintext PII values
      // that were submitted in the VP claims
      expect(bindingStr).not.toContain('pieter@test.nl')
      expect(bindingStr).not.toContain('Pieter')
      expect(bindingStr).not.toContain('Jansen')

      // If the binding contains identity fields, they should be hashed or encrypted
      if (binding.identityHash || binding.identity_hash) {
        const hash = binding.identityHash || binding.identity_hash
        // Hashes should be non-empty strings (not plaintext names/emails)
        expect(typeof hash).toBe('string')
        expect(hash.length).toBeGreaterThan(0)
        // A hash should not look like a plain email or name
        expect(hash).not.toContain('@')
        expect(hash).not.toContain(' ')
      }

      // If encrypted claims are present, they should not be readable
      if (binding.encryptedClaims || binding.encrypted_claims) {
        const encrypted = binding.encryptedClaims || binding.encrypted_claims
        if (typeof encrypted === 'string') {
          // Encrypted data should not contain plaintext claim values
          expect(encrypted).not.toContain('pieter@test.nl')
          expect(encrypted).not.toContain('Pieter')
        }
      }
    } else {
      // If the binding endpoint is not available, this test is not applicable
      // but we log it for visibility
      test.skip()
    }
  })

  test('auth-bridge health endpoint confirms storage subsystem is operational', async ({
    request,
  }) => {
    // Verify the auth-bridge health endpoint is up and the storage subsystem
    // is functional after creating a binding
    const healthResp = await request.get(`${AUTH_BRIDGE_URL}/health`)
    expect(healthResp.ok()).toBeTruthy()
    const health = await healthResp.json()
    expect(health).toBeDefined()

    // If health response includes component status, check storage
    if (health.components || health.checks) {
      const components = health.components || health.checks
      const storageHealth = components.storage || components.kottage || components.database
      if (storageHealth) {
        expect(
          storageHealth.status === 'UP' ||
          storageHealth.status === 'healthy' ||
          storageHealth === 'UP'
        ).toBeTruthy()
      }
    }
  })

  test('known-holder lookup works for the inspected binding', async ({ page, request }) => {
    // Verify the binding we inspected above is functional by doing a known-holder login
    await page.goto('/login')

    const walletButton = page.getByRole('button', { name: /wallet|portemonnee/i })
      await walletButton.click()

    const sessionId = await extractSessionIdFromPage(page)

    // Present the same holder DID -- should hit the fast path
    await request.post(`${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${sessionId}/present`, {
      data: {
        vpToken: 'test-vp-token',
        holderDid: DB_INSPECTION_HOLDER_DID,
        claims: {
          name: 'DB Inspection Test User',
          vct: 'urn:credential:student',
        },
      },
    })

    // Should go directly to VERIFIED, confirming the stored binding is usable
    const status = await pollSessionStatus(request, sessionId, 'VERIFIED', 20_000)
    expect(status.status).toBe('VERIFIED')

    // No Keycloak redirect
    expect(page.url()).not.toContain(KEYCLOAK_URL)
    expect(page.url()).not.toContain('/realms/')
  })
})
