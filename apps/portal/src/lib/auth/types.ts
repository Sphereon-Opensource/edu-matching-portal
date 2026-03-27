import type { DefaultSession } from 'next-auth'
import type { AssuranceMetadata, CanonicalClaim } from '@/lib/services/auth-bridge'

declare module 'next-auth' {
  interface Session extends DefaultSession {
    accessToken?: string
    idToken?: string
    user: {
      id: string
      institutionId?: string
      given_name?: string
      family_name?: string
      federated_subject?: string
      eduid?: string
      edupersonPrincipalName?: string
      authMethod?: 'federated' | 'wallet'
      assurance?: AssuranceMetadata
    } & DefaultSession['user']
  }
}

declare module '@auth/core/jwt' {
  interface JWT {
    accessToken?: string
    idToken?: string
    refreshToken?: string
    institutionId?: string
    given_name?: string
    family_name?: string
    federated_subject?: string
    eduid?: string
    edupersonPrincipalName?: string
    authMethod?: 'federated' | 'wallet'
    expiresAt?: number
    assurance?: AssuranceMetadata
  }
}

export interface WalletSessionInfo {
  sessionId: string
  userId: string
  claims: Record<string, string | CanonicalClaim>
  assurance?: AssuranceMetadata
}
