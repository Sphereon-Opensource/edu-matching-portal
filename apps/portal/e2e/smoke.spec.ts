import { test, expect } from '@playwright/test'

/**
 * Smoke tests that verify the test infrastructure is operational.
 * These run against the Docker Compose test stack.
 */

test.describe('Infrastructure smoke tests', () => {
  test('portal loads the home page', async ({ page }) => {
    await page.goto('/')
    // The page should load without a server error
    await expect(page).not.toHaveTitle(/500|error/i)
  })

  test('Keycloak portal realm OIDC discovery is reachable', async ({ request }) => {
    const keycloakUrl = process.env.KEYCLOAK_BASE_URL || 'http://localhost:8083'
    const response = await request.get(
      `${keycloakUrl}/realms/portal/.well-known/openid-configuration`
    )
    expect(response.ok()).toBeTruthy()
    const body = await response.json()
    expect(body.issuer).toContain('realms/portal')
    expect(body.authorization_endpoint).toBeTruthy()
    expect(body.token_endpoint).toBeTruthy()
  })

  test('Keycloak SURF realm OIDC discovery is reachable', async ({ request }) => {
    const keycloakSurfUrl = process.env.KEYCLOAK_SURF_BASE_URL || 'http://localhost:8084'
    const response = await request.get(
      `${keycloakSurfUrl}/realms/surf/.well-known/openid-configuration`
    )
    expect(response.ok()).toBeTruthy()
    const body = await response.json()
    expect(body.issuer).toContain('realms/surf')
  })

  test('STS health endpoint responds', async ({ request }) => {
    const stsUrl = process.env.STS_BASE_URL || 'http://localhost:8080'
    const response = await request.get(`${stsUrl}/health`)
    expect(response.ok()).toBeTruthy()
  })

  test('Auth Bridge health endpoint responds', async ({ request }) => {
    const authBridgeUrl = process.env.AUTH_BRIDGE_BASE_URL || 'http://localhost:8090'
    const response = await request.get(`${authBridgeUrl}/health`)
    expect(response.ok()).toBeTruthy()
  })
})

test.describe('OIDC login flow', () => {
  test('login button redirects to STS authorization endpoint', async ({ page }) => {
    await page.goto('/')

    // Look for a login/sign-in button or link
    const loginLink = page.getByRole('link', { name: /login|sign in|inloggen/i })
      .or(page.getByRole('button', { name: /login|sign in|inloggen/i }))

    if (await loginLink.count() > 0) {
      await loginLink.first().click()

      // Should redirect to STS or Keycloak for authentication
      await page.waitForURL(/localhost:(8080|8083)|\/api\/auth/, { timeout: 15_000 })
    } else {
      // If no login button, the app may auto-redirect to auth
      test.skip()
    }
  })

  test('Keycloak accepts test user credentials', async ({ request }) => {
    const keycloakUrl = process.env.KEYCLOAK_BASE_URL || 'http://localhost:8083'

    // Use resource owner password grant to verify test user credentials work
    const response = await request.post(
      `${keycloakUrl}/realms/portal/protocol/openid-connect/token`,
      {
        form: {
          grant_type: 'password',
          client_id: 'portal',
          client_secret: 'dev-client-secret',
          username: process.env.TEST_USER_EMAIL || 'test@kw1c.nl',
          password: process.env.TEST_USER_PASSWORD || 'password',
          scope: 'openid profile email',
        },
      }
    )

    expect(response.ok()).toBeTruthy()
    const body = await response.json()
    expect(body.access_token).toBeTruthy()
    expect(body.id_token).toBeTruthy()
  })

  test('SURF realm token contains eduid claim', async ({ request }) => {
    const keycloakSurfUrl = process.env.KEYCLOAK_SURF_BASE_URL || 'http://localhost:8084'

    const response = await request.post(
      `${keycloakSurfUrl}/realms/surf/protocol/openid-connect/token`,
      {
        form: {
          grant_type: 'password',
          client_id: 'portal-federation-client',
          client_secret: 'dev-federation-secret',
          username: 'student1@kw1c.nl',
          password: 'password',
          scope: 'openid profile email',
        },
      }
    )

    expect(response.ok()).toBeTruthy()
    const body = await response.json()
    expect(body.access_token).toBeTruthy()

    // Decode the access token payload (JWT) and check for eduid
    const payload = JSON.parse(
      Buffer.from(body.access_token.split('.')[1], 'base64').toString()
    )
    expect(payload.eduid).toBeTruthy()
    expect(payload.schac_home_organization).toBe('kw1c.nl')
  })
})
