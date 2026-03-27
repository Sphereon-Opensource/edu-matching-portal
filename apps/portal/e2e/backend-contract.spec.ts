import { test, expect } from '@playwright/test'

/**
 * Backend Contract Assertions -- API-only tests (no browser).
 *
 * Verifies that the federated flow produces correct STS contract
 * (token structure, claim schema, OIDC compliance) and that the
 * OID4VP session lifecycle endpoints are functional.
 *
 * Note: Full wallet VP presentation requires an actual wallet or deep
 * OID4VP protocol simulation. The wallet-side tests here verify
 * session creation and status endpoints.
 */

const AUTH_BRIDGE_URL = process.env.AUTH_BRIDGE_BASE_URL || 'http://localhost:8090'
const KEYCLOAK_URL = process.env.KEYCLOAK_BASE_URL || 'http://localhost:8083'
const KEYCLOAK_SURF_URL = process.env.KEYCLOAK_SURF_BASE_URL || 'http://localhost:8084'

const TEST_USER = process.env.TEST_USER_EMAIL || 'test@kw1c.nl'
const TEST_PASSWORD = process.env.TEST_USER_PASSWORD || 'password'
const STS_CLIENT_ID = process.env.STS_CLIENT_ID || 'portal'
const STS_CLIENT_SECRET = process.env.STS_CLIENT_SECRET || 'dev-client-secret'

function decodeJwtPayload(token: string): Record<string, unknown> {
  if (!token || !token.includes('.')) return {}
  const [, payloadB64] = token.split('.')
  try {
    return JSON.parse(Buffer.from(payloadB64, 'base64url').toString())
  } catch {
    return {}
  }
}

// Shared state for cross-test assertions
let federationTokenPayload: Record<string, unknown> = {}
let federationIdTokenPayload: Record<string, unknown> = {}
let federationUserInfo: Record<string, unknown> = {}

test.describe('Backend Contract: OID4VP session lifecycle', () => {
  test('session creation returns valid session with authorization request', async ({
    request,
  }) => {
    const createResp = await request.post(`${AUTH_BRIDGE_URL}/auth/oid4vp/sessions`, {
      headers: { 'Content-Type': 'application/json' },
    })
    expect(createResp.ok(), `Session creation failed: ${createResp.status()}`).toBeTruthy()
    const session = await createResp.json()

    // Session should have an ID
    const sessionId = session.sessionId || session.session_id || session.id
    expect(sessionId, 'Session response must contain a session ID').toBeTruthy()

    // Session should have a status
    const status = session.status || session.sessionStatus
    expect(status).toBeTruthy()
  })

  test('session status endpoint returns valid status for new session', async ({
    request,
  }) => {
    // Create a session first
    const createResp = await request.post(`${AUTH_BRIDGE_URL}/auth/oid4vp/sessions`, {
      headers: { 'Content-Type': 'application/json' },
    })
    expect(createResp.ok()).toBeTruthy()
    const session = await createResp.json()
    const sessionId = session.sessionId || session.session_id || session.id

    // Check status
    const statusResp = await request.get(
      `${AUTH_BRIDGE_URL}/auth/oid4vp/sessions/${sessionId}/status`,
    )
    expect(statusResp.ok(), `Status check failed: ${statusResp.status()}`).toBeTruthy()
    const statusBody = await statusResp.json()
    expect(statusBody.status).toBeTruthy()
  })
})

test.describe('Backend Contract: federation flow STS contract', () => {
  test.describe.configure({ mode: 'serial' })

  test('federation flow: obtain tokens via Keycloak portal realm', async ({ request }) => {
    const tokenResp = await request.post(
      `${KEYCLOAK_URL}/realms/portal/protocol/openid-connect/token`,
      {
        form: {
          grant_type: 'password',
          client_id: STS_CLIENT_ID,
          client_secret: STS_CLIENT_SECRET,
          username: TEST_USER,
          password: TEST_PASSWORD,
          scope: 'openid profile email surf-claims',
        },
      },
    )
    expect(tokenResp.ok(), `Token request failed: ${tokenResp.status()}`).toBeTruthy()
    const tokens = await tokenResp.json()
    expect(tokens.access_token).toBeTruthy()
    expect(tokens.id_token).toBeTruthy()

    federationTokenPayload = decodeJwtPayload(tokens.access_token)
    federationIdTokenPayload = decodeJwtPayload(tokens.id_token)

    // Get userinfo
    const userinfoResp = await request.get(
      `${KEYCLOAK_URL}/realms/portal/protocol/openid-connect/userinfo`,
      {
        headers: { Authorization: `Bearer ${tokens.access_token}` },
      },
    )
    expect(userinfoResp.ok()).toBeTruthy()
    federationUserInfo = await userinfoResp.json()
  })

  test('federation token has iss, id_token has sub', async () => {
    expect(federationTokenPayload.iss).toBeDefined()
    expect(federationTokenPayload.iss).toContain('realms/portal')
    // sub is in the id_token (Keycloak 26 access tokens may omit it)
    expect(federationIdTokenPayload.sub).toBeDefined()
    expect(federationIdTokenPayload.sub).toBeTruthy()
  })

  test('federation id_token contains identity claims', async () => {
    // The id_token should contain profile claims from the Keycloak mappers
    expect(federationIdTokenPayload.given_name).toBe('Jan')
    expect(federationIdTokenPayload.family_name).toBe('de Vries')
    expect(federationIdTokenPayload.email).toBe('test@kw1c.nl')
  })

  test('federation userinfo contains SURF claims', async () => {
    // SURF claims (eduid, schac_home_organization) come from the surf-claims scope.
    // They appear in userinfo since default scopes are applied server-side.
    expect(federationUserInfo.eduid).toBe(
      'urn:mace:eduid.nl:1.0:d57b4:test-student-001',
    )
    expect(federationUserInfo.schac_home_organization).toBe('kw1c.nl')
  })

  test('federation userinfo has standard OIDC claims', async () => {
    expect(federationUserInfo.sub).toBeDefined()
    expect(federationUserInfo.email).toBe('test@kw1c.nl')
    expect(federationUserInfo.given_name).toBe('Jan')
    expect(federationUserInfo.family_name).toBe('de Vries')
  })

  test('SURF realm token contains federation claims', async ({ request }) => {
    const tokenResp = await request.post(
      `${KEYCLOAK_SURF_URL}/realms/surf/protocol/openid-connect/token`,
      {
        form: {
          grant_type: 'password',
          client_id: 'portal-federation-client',
          client_secret: 'dev-federation-secret',
          username: 'student1@kw1c.nl',
          password: 'password',
          scope: 'openid profile email surf-claims',
        },
      },
    )
    expect(tokenResp.ok()).toBeTruthy()
    const tokens = await tokenResp.json()
    const surfPayload = decodeJwtPayload(tokens.access_token)

    expect(surfPayload.eduid).toBe('urn:mace:eduid.nl:1.0:d57b4:test-student-001')
    expect(surfPayload.schac_home_organization).toBe('kw1c.nl')
    expect(surfPayload.given_name).toBe('Jan')
    expect(surfPayload.family_name).toBe('de Vries')
    expect(surfPayload.email).toBe('student1@kw1c.nl')
  })

  test('reconciliation client can obtain tokens', async ({ request }) => {
    const tokenResp = await request.post(
      `${KEYCLOAK_URL}/realms/portal/protocol/openid-connect/token`,
      {
        form: {
          grant_type: 'password',
          client_id: 'portal-reconciliation',
          client_secret: 'dev-secret',
          username: TEST_USER,
          password: TEST_PASSWORD,
          scope: 'openid profile email surf-claims',
        },
      },
    )
    expect(tokenResp.ok(), `Reconciliation client token failed: ${tokenResp.status()}`).toBeTruthy()
    const tokens = await tokenResp.json()
    expect(tokens.access_token).toBeTruthy()

    const payload = decodeJwtPayload(tokens.access_token)
    expect(payload.eduid).toBeTruthy()
    expect(payload.schac_home_organization).toBe('kw1c.nl')
  })
})
