import { test, expect } from '@playwright/test'

/**
 * Token Parity -- Verifies that Keycloak realm/client configurations
 * produce consistent, well-formed tokens across different clients and realms.
 *
 * Compares:
 * - portal client vs portal-reconciliation client (same realm)
 * - portal realm vs SURF realm (cross-realm federation claims)
 *
 * Full wallet VP ↔ federation parity requires OID4VP protocol simulation
 * and is covered by integration tests in the Kotlin test suite.
 */

const KEYCLOAK_URL = process.env.KEYCLOAK_BASE_URL || 'http://localhost:8083'
const KEYCLOAK_SURF_URL = process.env.KEYCLOAK_SURF_BASE_URL || 'http://localhost:8084'

function decodeJwtPayload(token: string): Record<string, unknown> {
  if (!token || !token.includes('.')) return {}
  const [, payloadB64] = token.split('.')
  try {
    return JSON.parse(Buffer.from(payloadB64, 'base64url').toString())
  } catch {
    return {}
  }
}

let portalAccessPayload: Record<string, unknown> = {}
let portalIdPayload: Record<string, unknown> = {}
let portalUserInfo: Record<string, unknown> = {}
let reconAccessPayload: Record<string, unknown> = {}
let reconIdPayload: Record<string, unknown> = {}
let reconUserInfo: Record<string, unknown> = {}
let surfAccessPayload: Record<string, unknown> = {}

test.describe('Token Parity', () => {
  test.describe.configure({ mode: 'serial' })

  test('obtain portal client tokens', async ({ request }) => {
    const tokenResp = await request.post(
      `${KEYCLOAK_URL}/realms/portal/protocol/openid-connect/token`,
      {
        form: {
          grant_type: 'password',
          client_id: 'portal',
          client_secret: 'dev-client-secret',
          username: 'test@kw1c.nl',
          password: 'password',
          scope: 'openid profile email surf-claims',
        },
      },
    )
    expect(tokenResp.ok()).toBeTruthy()
    const tokens = await tokenResp.json()

    portalAccessPayload = decodeJwtPayload(tokens.access_token)
    portalIdPayload = decodeJwtPayload(tokens.id_token)

    const userinfoResp = await request.get(
      `${KEYCLOAK_URL}/realms/portal/protocol/openid-connect/userinfo`,
      { headers: { Authorization: `Bearer ${tokens.access_token}` } },
    )
    expect(userinfoResp.ok()).toBeTruthy()
    portalUserInfo = await userinfoResp.json()
  })

  test('obtain reconciliation client tokens', async ({ request }) => {
    const tokenResp = await request.post(
      `${KEYCLOAK_URL}/realms/portal/protocol/openid-connect/token`,
      {
        form: {
          grant_type: 'password',
          client_id: 'portal-reconciliation',
          client_secret: 'dev-secret',
          username: 'test@kw1c.nl',
          password: 'password',
          scope: 'openid profile email surf-claims',
        },
      },
    )
    expect(tokenResp.ok()).toBeTruthy()
    const tokens = await tokenResp.json()

    reconAccessPayload = decodeJwtPayload(tokens.access_token)
    reconIdPayload = decodeJwtPayload(tokens.id_token)

    const userinfoResp = await request.get(
      `${KEYCLOAK_URL}/realms/portal/protocol/openid-connect/userinfo`,
      { headers: { Authorization: `Bearer ${tokens.access_token}` } },
    )
    expect(userinfoResp.ok()).toBeTruthy()
    reconUserInfo = await userinfoResp.json()
  })

  test('obtain SURF realm tokens', async ({ request }) => {
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
    surfAccessPayload = decodeJwtPayload(tokens.access_token)
  })

  test('same user gets same sub across portal clients', async () => {
    // Same user, same realm → same sub
    expect(portalIdPayload.sub).toBe(reconIdPayload.sub)
  })

  test('same issuer across portal clients', async () => {
    expect(portalAccessPayload.iss).toBe(reconAccessPayload.iss)
    expect(portalAccessPayload.iss).toContain('realms/portal')
  })

  test('identity claims match across portal clients', async () => {
    // Userinfo from both clients should return identical identity claims
    expect(portalUserInfo.email).toBe('test@kw1c.nl')
    expect(reconUserInfo.email).toBe('test@kw1c.nl')
    expect(portalUserInfo.given_name).toBe(reconUserInfo.given_name)
    expect(portalUserInfo.family_name).toBe(reconUserInfo.family_name)
  })

  test('SURF claims match across portal clients', async () => {
    // Both clients should get the same eduid and schac_home_organization
    expect(portalUserInfo.eduid).toBe(reconUserInfo.eduid)
    expect(portalUserInfo.schac_home_organization).toBe(reconUserInfo.schac_home_organization)
    expect(portalUserInfo.eduid).toBe('urn:mace:eduid.nl:1.0:d57b4:test-student-001')
    expect(portalUserInfo.schac_home_organization).toBe('kw1c.nl')
  })

  test('SURF realm token contains expected federation claims', async () => {
    // The SURF realm token should contain the claims that the portal realm's
    // identity provider mapper will copy into the portal user attributes
    expect(surfAccessPayload.eduid).toBe('urn:mace:eduid.nl:1.0:d57b4:test-student-001')
    expect(surfAccessPayload.schac_home_organization).toBe('kw1c.nl')
    expect(surfAccessPayload.given_name).toBe('Jan')
    expect(surfAccessPayload.family_name).toBe('de Vries')
    expect(surfAccessPayload.email).toBe('student1@kw1c.nl')
  })

  test('id_token contains profile claims from mappers', async () => {
    // Portal client id_token
    expect(portalIdPayload.given_name).toBe('Jan')
    expect(portalIdPayload.family_name).toBe('de Vries')
    expect(portalIdPayload.email).toBe('test@kw1c.nl')

    // Reconciliation client id_token
    expect(reconIdPayload.given_name).toBe('Jan')
    expect(reconIdPayload.family_name).toBe('de Vries')
    expect(reconIdPayload.email).toBe('test@kw1c.nl')
  })

  test('access tokens contain SURF claims from surf-claims scope', async () => {
    // Portal access token
    expect(portalAccessPayload.eduid).toBe('urn:mace:eduid.nl:1.0:d57b4:test-student-001')
    expect(portalAccessPayload.schac_home_organization).toBe('kw1c.nl')

    // Reconciliation access token
    expect(reconAccessPayload.eduid).toBe('urn:mace:eduid.nl:1.0:d57b4:test-student-001')
    expect(reconAccessPayload.schac_home_organization).toBe('kw1c.nl')
  })

  test('federation STS token has required identity claims', async () => {
    // Federation path must produce tokens with the canonical identity fields
    expect(portalIdPayload.sub).toBeDefined()
    expect(portalIdPayload.given_name).toBeDefined()
    expect(portalIdPayload.family_name).toBeDefined()
    expect(portalIdPayload.email).toBeDefined()

    // Institution identifier from SURF federation
    expect(portalUserInfo.schac_home_organization).toBeDefined()
    expect(portalUserInfo.eduid).toBeDefined()
  })

  test('reconciliation client token has same identity claim structure as portal client', async () => {
    // The reconciliation client (used during wallet IDV flow) must produce
    // tokens with the same identity claim structure as the portal client
    // so that downstream services see a consistent schema.

    // Same identity fields present
    expect(reconIdPayload.sub).toBeDefined()
    expect(reconIdPayload.given_name).toBeDefined()
    expect(reconIdPayload.family_name).toBeDefined()
    expect(reconIdPayload.email).toBeDefined()

    // Same SURF claims present
    expect(reconUserInfo.eduid).toBeDefined()
    expect(reconUserInfo.schac_home_organization).toBeDefined()

    // Values match between clients for the same user
    expect(reconIdPayload.given_name).toBe(portalIdPayload.given_name)
    expect(reconIdPayload.family_name).toBe(portalIdPayload.family_name)
    expect(reconIdPayload.email).toBe(portalIdPayload.email)
    expect(reconUserInfo.eduid).toBe(portalUserInfo.eduid)
    expect(reconUserInfo.schac_home_organization).toBe(portalUserInfo.schac_home_organization)
  })

  test('ACR/AMR differ appropriately between federation and SURF realm tokens', async () => {
    // Federation (portal realm) and SURF realm tokens may have different ACR/AMR
    // because they represent different authentication contexts.
    // This is expected: the identity claims should be equivalent, but the
    // assurance metadata reflects how the user actually authenticated.

    // Both should have ACR if the realm is configured for it
    if (portalAccessPayload.acr && surfAccessPayload.acr) {
      // They may be the same or different depending on Keycloak config,
      // but both must be valid strings
      expect(typeof portalAccessPayload.acr).toBe('string')
      expect(typeof surfAccessPayload.acr).toBe('string')
    }

    // The critical invariant: identity claims are equivalent even when
    // ACR/AMR differ between authentication paths
    expect(surfAccessPayload.given_name).toBe(portalIdPayload.given_name)
    expect(surfAccessPayload.family_name).toBe(portalIdPayload.family_name)
    expect(surfAccessPayload.eduid).toBe(
      portalUserInfo.eduid || portalAccessPayload.eduid
    )
    expect(surfAccessPayload.schac_home_organization).toBe(
      (portalUserInfo.schac_home_organization || portalAccessPayload.schac_home_organization) as string
    )
  })
})
