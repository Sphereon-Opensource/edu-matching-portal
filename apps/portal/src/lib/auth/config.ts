import type { NextAuthConfig } from 'next-auth'
import { env } from '@/lib/config/env'

/**
 * OIDC Core §5.5 `claims` request parameter — declares which claims the RP
 * wants where. Listed claims are returned by the AS in the id_token regardless
 * of whether the auth-code flow's default would route them through /userinfo.
 *
 * Drives the federation login flow's per-request claim contract — replaces
 * relying on the (non-standard, server-wide) `embed-userinfo-claims-in-id-token`
 * knob for spec-correct deployments. The portal app reads these claims off
 * the id_token directly via the `profile()` callback below, so anything the
 * portal expects on `session.user` MUST be listed here.
 *
 * The AS also accepts `null` to mean "voluntary" or `{essential: true}` to
 * mean "must be returned"; we keep them voluntary because losing one of them
 * shouldn't fail the whole login.
 *
 * Auth.js v5 detects `claims` as a special key and JSON.stringify's the value
 * itself when serialising the authorization URL (see
 * @auth/core/src/lib/utils/providers.ts → normalizeEndpoint). Pass a plain
 * object here — pre-stringifying would result in a double-encoded string
 * literal that the AS rejects with "claims parameter must be a JSON object".
 */
const REQUESTED_ID_TOKEN_CLAIMS = {
  id_token: {
    given_name: null,
    family_name: null,
    name: null,
    email: null,
    email_verified: null,
    eduid: null,
    eduperson_principal_name: null,
    eduperson_scoped_affiliation: null,
    eduperson_assurance: null,
    schac_home_organization: null,
    institution_id: null,
    federated_subject: null,
  },
}

// Diagnostic — confirms which STS URLs the OIDC provider config sees at runtime.
// Logged once at module load.
if (process.env.AUTH_DEBUG === 'true') {
  // eslint-disable-next-line no-console
  console.log('[auth-config] STS_ISSUER_URL=%s STS_INTERNAL_URL=%s', env.STS_ISSUER_URL, env.STS_INTERNAL_URL)
  // eslint-disable-next-line no-console
  console.log('[auth-config] derived wellKnown=%s/.well-known/openid-configuration token=%s/token userinfo=%s/userinfo',
    env.STS_INTERNAL_URL, env.STS_INTERNAL_URL, env.STS_INTERNAL_URL)
}

export const authConfig: NextAuthConfig = {
  // Enable verbose Auth.js logging when AUTH_DEBUG=true. The default condition
  // (NODE_ENV === 'development') never trips on a Next.js standalone build, so
  // debug stays off in containers unless explicitly opted in.
  debug:
    process.env.AUTH_DEBUG === 'true' ||
    process.env.NODE_ENV === 'development',
  providers: [
    // OIDC Provider: STS (federated login — supports multiple upstream providers)
    // The `provider` parameter is passed dynamically at signIn() time to select
    // the upstream IdP (e.g., "surf", "keycloak"). If omitted, the STS uses its default.
    {
      id: 'sts',
      name: 'Federation (STS)',
      type: 'oidc',
      issuer: env.STS_ISSUER_URL,
      // Explicit endpoints — bypass Auth.js's discovery fetch entirely so we don't
      // depend on `${issuer}/.well-known/openid-configuration` being reachable from
      // inside the docker network (it isn't: issuer is the browser-facing localhost).
      // Auth.js v5 short-circuits its `discoveryRequest(issuer)` call when both
      // `authorization.url` is set (signin path) AND `token.url` + `userinfo.url`
      // are set (callback path). `wellKnown` is *not* honored on the signin path
      // (only `authorization.url` is checked there) — same for the callback path.
      authorization: {
        url: `${env.STS_ISSUER_URL}/authorize`, // browser-facing — user is redirected here
        params: {
          scope: 'openid profile email',
          // OIDC Core §5.5: ask the AS to embed our required claims directly
          // into the id_token. See REQUESTED_ID_TOKEN_CLAIMS above.
          claims: REQUESTED_ID_TOKEN_CLAIMS,
          // provider / login_hint are set dynamically at signIn() time
        },
      },
      // Server-side fetches go to the docker-internal URL.
      token: `${env.STS_INTERNAL_URL}/token`,
      userinfo: `${env.STS_INTERNAL_URL}/userinfo`,
      // The STS embeds all projected claims into the id_token via the
      // `embed-userinfo-claims-in-id-token` knob (deviates from OIDC §5.4 but
      // saves a round-trip and matches Auth.js v5's id_token-first profile
      // model). Leaving Auth.js's default behaviour (idToken: true) so the
      // profile comes straight from the verified id_token claims.
      clientId: env.STS_CLIENT_ID,
      clientSecret: env.STS_CLIENT_SECRET,
      // The STS registers `portal` with `token_endpoint_auth_method=client_secret_post`
      // (credentials in the form body, not the Authorization header). Auth.js v5
      // defaults to `client_secret_basic` — flip it to match what the AS expects,
      // otherwise the token call returns 401 invalid_client.
      // `id_token_signed_response_alg` tells oauth4webapi which JWS alg to expect
      // on the ID token; default is RS256 but STS signs with ES256, otherwise
      // the validator throws `unexpected JWT "alg" header parameter`.
      client: {
        token_endpoint_auth_method: 'client_secret_post',
        id_token_signed_response_alg: 'ES256',
      },
      checks: ['pkce', 'state', 'nonce'],
      profile(profile) {
        return {
          id: profile.sub,
          name: profile.name,
          email: profile.email,
          given_name: profile.given_name,
          family_name: profile.family_name,
          institutionId: profile.institution_id,
          federated_subject: profile.federated_subject,
          eduid: profile.eduid,
          edupersonPrincipalName: profile.eduperson_principal_name,
        }
      },
    },
    // OIDC Provider: STS-Wallet (wallet login via OID4VP + STS)
    // Uses the same STS issuer but with login_hint=oid4vp:{sessionId}
    // to trigger the wallet authentication path through the STS.
    {
      id: 'sts-wallet',
      name: 'Wallet (OID4VP)',
      type: 'oidc',
      issuer: env.STS_ISSUER_URL,
      // Same browser-vs-server split as the federation provider above.
      authorization: {
        url: `${env.STS_ISSUER_URL}/authorize`,
        params: {
          scope: 'openid profile email',
          // login_hint is set dynamically at signIn() time
        },
      },
      token: `${env.STS_INTERNAL_URL}/token`,
      userinfo: `${env.STS_INTERNAL_URL}/userinfo`,
      // STS embeds projected claims in the id_token (see `sts` provider above).
      clientId: env.STS_CLIENT_ID,
      clientSecret: env.STS_CLIENT_SECRET,
      client: {
        token_endpoint_auth_method: 'client_secret_post',
        id_token_signed_response_alg: 'ES256',
      },
      checks: ['pkce', 'state', 'nonce'],
      profile(profile) {
        return {
          id: profile.sub,
          name: profile.name,
          email: profile.email,
          given_name: profile.given_name,
          family_name: profile.family_name,
          institutionId: profile.institution_id,
          federated_subject: profile.federated_subject,
          eduid: profile.eduid,
          edupersonPrincipalName: profile.eduperson_principal_name,
        }
      },
    },
  ],

  callbacks: {
    async jwt({ token, user, account }) {
      if (account && user) {
        token.accessToken = account.access_token
        token.idToken = account.id_token
        token.refreshToken = account.refresh_token
        token.expiresAt = account.expires_at
        token.institutionId = (user as any).institutionId
        token.given_name = (user as any).given_name
        token.family_name = (user as any).family_name
        token.federated_subject = (user as any).federated_subject
        token.eduid = (user as any).eduid
        token.edupersonPrincipalName = (user as any).edupersonPrincipalName
        token.sub = user.id // Use the STS sub, not next-auth's generated UUID
        token.authMethod = account.provider === 'sts-wallet' ? 'wallet' : 'federated'
      }
      return token
    },

    async session({ session, token }) {
      session.accessToken = token.accessToken as string | undefined
      session.idToken = token.idToken as string | undefined
      if (session.user) {
        session.user.id = token.sub || ''
        session.user.institutionId = token.institutionId as string | undefined
        session.user.given_name = token.given_name as string | undefined
        session.user.family_name = token.family_name as string | undefined
        session.user.federated_subject = token.federated_subject as string | undefined
        session.user.eduid = token.eduid as string | undefined
        session.user.edupersonPrincipalName = token.edupersonPrincipalName as string | undefined
        session.user.authMethod = token.authMethod as 'federated' | 'wallet' | undefined
        session.user.assurance = token.assurance
      }
      return session
    },
  },

  // Auth.js redirects every server-side OIDC failure to `?error=Configuration` and
  // hides the cause behind an opaque label. Hook into the `logger` so the actual
  // cause (e.g. `fetch failed: ECONNREFUSED`, `token endpoint returned 401
  // invalid_client`, `id_token signature did not verify`) lands in the portal
  // stdout next to the fetch-trace lines — operators get one place to look when
  // an OIDC flow breaks. Production should silence these via NODE_ENV.
  logger: {
    error(error) {
      // Walk the cause chain — Auth.js wraps oauth4webapi errors which often
      // carry their actual diagnostic two or three levels deep. Print every
      // layer so the failing URL / status / reason is never hidden behind a
      // top-level "CallbackRouteError" label.
      // eslint-disable-next-line no-console
      console.error('[auth][error]', error?.name ?? '?', error?.message ?? String(error))
      // Dump the full error tree (own-properties + nested causes). oauth4webapi
      // errors often pack their diagnostic into non-enumerable fields or unusual
      // shapes that the structured-field path above can miss; JSON-with-Error-replacer
      // surfaces whatever's actually there.
      const seen = new WeakSet<object>()
      const replacer = (_key: string, value: unknown) => {
        if (typeof value === 'object' && value !== null) {
          if (seen.has(value)) return '[Circular]'
          seen.add(value)
          if (value instanceof Error) {
            return {
              __error: value.constructor.name,
              name: value.name,
              message: value.message,
              ...(value as unknown as Record<string, unknown>),
              cause: (value as { cause?: unknown }).cause,
            }
          }
        }
        return value
      }
      try {
        // eslint-disable-next-line no-console
        console.error('[auth][error]   tree', JSON.stringify(error, replacer, 2))
      } catch (e) {
        // eslint-disable-next-line no-console
        console.error('[auth][error]   tree-failed', (e as Error).message)
      }
    },
    warn(code) {
      // eslint-disable-next-line no-console
      console.warn('[auth][warn]', code)
    },
    debug(message, metadata) {
      if (process.env.AUTH_DEBUG === 'true') {
        // eslint-disable-next-line no-console
        console.debug('[auth][debug]', message, metadata ?? '')
      }
    },
  },

  pages: {
    signIn: '/login',
    error: '/login',
  },

  session: {
    strategy: 'jwt',
    maxAge: 8 * 60 * 60, // 8 hours
  },

  // Per architecture §6.3: __Host- prefix enforces Secure, disallows Domain, restricts Path to /
  cookies: {
    sessionToken: {
      name: '__Host-authjs.session-token',
      options: { httpOnly: true, secure: true, sameSite: 'lax' as const, path: '/' },
    },
    csrfToken: {
      name: '__Host-authjs.csrf-token',
      options: { httpOnly: true, secure: true, sameSite: 'lax' as const, path: '/' },
    },
    callbackUrl: {
      name: '__Host-authjs.callback-url',
      options: { httpOnly: true, secure: true, sameSite: 'lax' as const, path: '/' },
    },
  },
}
