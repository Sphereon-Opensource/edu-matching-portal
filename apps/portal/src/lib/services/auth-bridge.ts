import { env } from '@/lib/config/env'

const AUTH_BRIDGE_BASE = env.AUTH_BRIDGE_URL

export interface CreateSessionResponse {
  sessionId: string
  qrCodeDataUri: string
  requestUri: string
  statusEndpoint: string
}

export type SessionStatus =
  | 'CREATED'
  | 'PENDING'
  | 'INTERACTION_STARTED'
  | 'VERIFYING'
  | 'VERIFIED'
  | 'IDV_REQUIRED'
  | 'RECONCILING'
  | 'COMPLETED'
  | 'EXPIRED'
  | 'FAILED'
  | 'ERROR'

export interface AssuranceMetadata {
  acr?: string
  amr?: string[]
  assuranceLevel?: string
}

export interface CanonicalClaim {
  value: string
  source?: string
  assurance?: string
}

export interface SessionStatusResponse {
  sessionId: string
  status: SessionStatus
  /** Informational text for in-progress statuses (IDV, reconciling). */
  message?: string
  /** Failure detail set by the auth-bridge when status is ERROR/FAILED/EXPIRED. */
  errorMessage?: string
  mappedClaims?: Record<string, string | CanonicalClaim>
  idvSteps?: IdvStep[]
  idvMethod?: string
  assurance?: AssuranceMetadata
}

export interface IdvStep {
  type: string
  status: string
  label: string
}

export interface IdvInitiateResponse {
  redirectUrl: string
  sessionId: string
  planType?: string
}

export interface IdvSubmitResponse {
  status: SessionStatus
  message?: string
}

export interface CompleteAuthResponse {
  userId: string
  claims: Record<string, string | CanonicalClaim>
  isNewUser: boolean
  idvRequired?: boolean
  idvMethod?: string
  idvSteps?: IdvStep[]
  message?: string
  assurance?: AssuranceMetadata
}

export const authBridgeClient = {
  async createSession(opts?: { forceReconciliation?: boolean }): Promise<CreateSessionResponse> {
    const body = opts?.forceReconciliation ? JSON.stringify({ forceReconciliation: true }) : undefined
    const res = await fetch(`${AUTH_BRIDGE_BASE}/auth/oid4vp/sessions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      ...(body ? { body } : {}),
    })
    if (!res.ok) throw new Error(`Failed to create session: ${res.status}`)
    return res.json()
  },

  async getSessionStatus(sessionId: string): Promise<SessionStatusResponse> {
    const res = await fetch(`${AUTH_BRIDGE_BASE}/auth/oid4vp/sessions/${sessionId}/status`)
    if (!res.ok) throw new Error(`Failed to get status: ${res.status}`)
    return res.json()
  },

  async completeAuth(sessionId: string): Promise<CompleteAuthResponse> {
    const res = await fetch(`${AUTH_BRIDGE_BASE}/auth/oid4vp/sessions/${sessionId}/complete`, {
      method: 'POST',
    })
    if (res.status === 202) {
      // IDV required — parse the actual response body from the auth bridge
      const data = await res.json()
      return {
        userId: data.userId ?? '',
        claims: data.claims ?? {},
        isNewUser: data.isNewUser ?? false,
        idvRequired: true,
        idvMethod: data.idvMethod,
        idvSteps: data.idvSteps,
        message: data.message,
      }
    }
    if (!res.ok) throw new Error(`Failed to complete auth: ${res.status}`)
    return res.json()
  },

  async initiateIdv(sessionId: string): Promise<IdvInitiateResponse> {
    const res = await fetch(`${AUTH_BRIDGE_BASE}/auth/oid4vp/sessions/${sessionId}/idv/initiate`, {
      method: 'POST',
    })
    if (!res.ok) throw new Error(`Failed to initiate IDV: ${res.status}`)
    return res.json()
  },

  async getIdvStatus(sessionId: string): Promise<SessionStatusResponse> {
    const res = await fetch(`${AUTH_BRIDGE_BASE}/auth/oid4vp/sessions/${sessionId}/idv/status`)
    if (!res.ok) throw new Error(`Failed to get IDV status: ${res.status}`)
    return res.json()
  },

  async submitIdv(sessionId: string, body: Record<string, unknown>): Promise<IdvSubmitResponse> {
    const res = await fetch(`${AUTH_BRIDGE_BASE}/auth/oid4vp/sessions/${sessionId}/idv/submit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!res.ok) throw new Error(`IDV submission failed: ${res.status}`)
    return res.json()
  },
}
