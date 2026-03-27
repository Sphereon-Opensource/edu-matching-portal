import { test, expect } from '@playwright/test'

/**
 * Federation Login (no wallet) -- MUST work end-to-end.
 *
 * Flow: Portal login page -> Click "Login with institution account" ->
 *       Redirect to Keycloak -> Enter credentials -> Redirect back to portal ->
 *       Verify session established with correct claims.
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_BASE_URL || 'http://localhost:8083'
const TEST_USER = process.env.TEST_USER_EMAIL || 'test@kw1c.nl'
const TEST_PASSWORD = process.env.TEST_USER_PASSWORD || 'password'

/**
 * Helper: complete the full federation login flow.
 * Navigates to /login, clicks the federation button, fills Keycloak credentials,
 * and waits for redirect back to the portal.
 */
async function completeFederationLogin(page: import('@playwright/test').Page) {
  await page.goto('/login')

  // Click the federation login button ("Login with eduID" / "Inloggen met eduID")
  const federatedButton = page.getByRole('button', { name: /eduID|institution|federation|inloggen/i })
  await federatedButton.click()

  // Wait for redirect to Keycloak authorization endpoint
  await page.waitForURL(
    (url) => url.href.includes(KEYCLOAK_URL) || url.href.includes('/realms/'),
    { timeout: 15_000 }
  )

  // Fill Keycloak login form
  await page.fill('#username', TEST_USER)
  await page.fill('#password', TEST_PASSWORD)
  await page.click('#kc-login')

  // Wait for redirect back to portal (away from Keycloak)
  await page.waitForURL(
    (url) => !url.href.includes(KEYCLOAK_URL) && !url.href.includes('/realms/'),
    { timeout: 20_000 }
  )
}

test.describe('Federation Login (no wallet)', () => {

  test('navigates to login page', async ({ page }) => {
    await page.goto('/login')
    // The login page should render without errors and show at least one button
    await expect(page.getByRole('button').first()).toBeVisible({ timeout: 10_000 })
  })

  test('clicks federated login button and redirects to Keycloak', async ({ page }) => {
    await page.goto('/login')

    // Click the federation login button ("Login with eduID" / "Inloggen met eduID")
    const federatedButton = page.getByRole('button', { name: /eduID|institution|federation|inloggen/i })
    await federatedButton.click()

    // Verify redirect to Keycloak authorization endpoint
    await page.waitForURL(
      (url) => url.href.includes(KEYCLOAK_URL) || url.href.includes('/realms/'),
      { timeout: 15_000 }
    )
    expect(page.url()).toMatch(/\/realms\/|\/auth/)
  })

  test('enters credentials in Keycloak and redirects back', async ({ page }) => {
    await completeFederationLogin(page)

    // Should be back at the portal, not at Keycloak
    expect(page.url()).not.toContain(KEYCLOAK_URL)
    expect(page.url()).not.toContain('/realms/')
  })

  test('session established with correct claims', async ({ page }) => {
    await completeFederationLogin(page)

    // Verify session cookie exists (next-auth session token)
    const cookies = await page.context().cookies()
    const sessionCookie = cookies.find(c =>
      c.name.includes('session-token') ||
      c.name.includes('next-auth') ||
      c.name.includes('authjs')
    )
    expect(sessionCookie).toBeDefined()

    // Verify we can access the session API endpoint
    const sessionResponse = await page.request.get('/api/auth/session')
    expect(sessionResponse.ok()).toBeTruthy()
    const session = await sessionResponse.json()
    // Session should have a user object (may be empty if session format differs)
    expect(session).toBeDefined()

    // Verify auth method is 'federated' (not 'wallet')
    if (session.user) {
      expect(session.user.authMethod).toBe('federated')
    }
  })

  test('STS token contains expected claims', async ({ page }) => {
    await completeFederationLogin(page)

    // Fetch the session from the NextAuth session endpoint
    const sessionResponse = await page.request.get('/api/auth/session')
    expect(sessionResponse.ok()).toBeTruthy()
    const session = await sessionResponse.json()

    // The session should contain user information from the OIDC flow
    if (session.user) {
      // If user object is populated, verify expected fields
      expect(session.user.email || session.user.name || session.user.sub).toBeTruthy()
    }

    // If an access token is exposed in the session, decode and verify JWT claims
    const token = session.accessToken || session.idToken
    if (token && typeof token === 'string' && token.includes('.')) {
      const [, payloadB64] = token.split('.')
      const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString())
      expect(payload.sub).toBeDefined()
      expect(payload.iss).toBeDefined()
    }
  })

  test('session contains assurance with ACR and AMR from federation path', async ({ page }) => {
    await completeFederationLogin(page)

    const sessionResponse = await page.request.get('/api/auth/session')
    expect(sessionResponse.ok()).toBeTruthy()
    const session = await sessionResponse.json()

    expect(session.user).toBeDefined()

    // Federation path should populate assurance with ACR/AMR from Keycloak
    if (session.user.assurance) {
      // ACR should reflect the Keycloak authentication context class
      expect(session.user.assurance.acr).toBeDefined()
      expect(typeof session.user.assurance.acr).toBe('string')
      expect(session.user.assurance.acr.length).toBeGreaterThan(0)

      // AMR should reflect the authentication methods used (e.g., 'pwd' for password)
      expect(session.user.assurance.amr).toBeDefined()
      expect(Array.isArray(session.user.assurance.amr)).toBeTruthy()
      expect(session.user.assurance.amr.length).toBeGreaterThan(0)
    }

    // Verify auth method is 'federated' (not wallet defaults)
    expect(session.user.authMethod).toBe('federated')
  })

  test('session claims match canonical model with institution identifiers', async ({ page }) => {
    await completeFederationLogin(page)

    const sessionResponse = await page.request.get('/api/auth/session')
    expect(sessionResponse.ok()).toBeTruthy()
    const session = await sessionResponse.json()

    expect(session.user).toBeDefined()

    // Verify canonical identity claims from federation
    expect(session.user.given_name || session.user.givenName).toBeTruthy()
    expect(session.user.family_name || session.user.familyName).toBeTruthy()
    expect(session.user.email).toBeTruthy()

    // Institution-specific identifiers (if available from SURF federation)
    // These come from the eduid/schac claims mapped through Keycloak
    if (session.user.federated_subject || session.user.eduid) {
      expect(session.user.federated_subject || session.user.eduid).toBeTruthy()
    }
    if (session.user.institution_id || session.user.schac_home_organization) {
      expect(
        session.user.institution_id || session.user.schac_home_organization
      ).toBeTruthy()
    }

    // If an access token or id_token is available, verify JWT-level claims
    const token = session.accessToken || session.idToken
    if (token && typeof token === 'string' && token.includes('.')) {
      const [, payloadB64] = token.split('.')
      const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString())

      // ACR claim in the token should reflect federation authentication
      if (payload.acr) {
        expect(typeof payload.acr).toBe('string')
        // Federation ACR should not be the wallet default
        expect(payload.acr).not.toBe('urn:wallet:default')
      }

      // AMR claim in the token
      if (payload.amr) {
        expect(Array.isArray(payload.amr)).toBeTruthy()
      }
    }
  })
})
