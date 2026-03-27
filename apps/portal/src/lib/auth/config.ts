import type { NextAuthConfig } from 'next-auth'
import { env } from '@/lib/config/env'

export const authConfig: NextAuthConfig = {
  debug: process.env.NODE_ENV === 'development',
  providers: [
    // OIDC Provider: STS (federated login — supports multiple upstream providers)
    // The `provider` parameter is passed dynamically at signIn() time to select
    // the upstream IdP (e.g., "surf", "keycloak"). If omitted, the STS uses its default.
    {
      id: 'sts',
      name: 'Federation (STS)',
      type: 'oidc',
      issuer: env.STS_ISSUER_URL,
      clientId: env.STS_CLIENT_ID,
      clientSecret: env.STS_CLIENT_SECRET,
      authorization: {
        params: {
          scope: 'openid profile email',
          // provider is set dynamically at signIn() time
        },
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
      clientId: env.STS_CLIENT_ID,
      clientSecret: env.STS_CLIENT_SECRET,
      authorization: {
        params: {
          scope: 'openid profile email',
          // login_hint is set dynamically at signIn() time
        },
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
