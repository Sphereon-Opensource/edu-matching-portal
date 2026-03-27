'use client'

import { useCallback, useReducer } from 'react'
import { signIn } from 'next-auth/react'
import type { IdvStep } from '@/lib/services/auth-bridge'

export type WalletAuthState =
  | { status: 'idle' }
  | { status: 'creating' }
  | { status: 'pending'; sessionId: string; qrCodeDataUri: string; requestUri: string }
  | { status: 'idv_required'; sessionId: string; message: string; steps: IdvStep[]; idvMethod?: string }
  | { status: 'idv_redirecting'; redirectUrl: string }
  | { status: 'idv_input_needed'; inputType: string; prompt: string }
  | { status: 'reconciling'; sessionId: string; message: string }
  | { status: 'completing'; sessionId: string }
  | { status: 'authenticated' }
  | { status: 'error'; message: string; retryable: boolean }

type WalletAuthAction =
  | { type: 'CREATE' }
  | { type: 'SESSION_CREATED'; sessionId: string; qrCodeDataUri: string; requestUri: string }
  | { type: 'IDV_REQUIRED'; sessionId: string; message: string; steps: IdvStep[]; idvMethod?: string }
  | { type: 'IDV_REDIRECT'; redirectUrl: string }
  | { type: 'RECONCILING'; sessionId: string; message: string }
  | { type: 'COMPLETING'; sessionId: string }
  | { type: 'AUTHENTICATED' }
  | { type: 'ERROR'; message: string; retryable: boolean }
  | { type: 'RESET' }

function reducer(_state: WalletAuthState, action: WalletAuthAction): WalletAuthState {
  switch (action.type) {
    case 'CREATE':
      return { status: 'creating' }
    case 'SESSION_CREATED':
      return {
        status: 'pending',
        sessionId: action.sessionId,
        qrCodeDataUri: action.qrCodeDataUri,
        requestUri: action.requestUri,
      }
    case 'IDV_REQUIRED':
      return {
        status: 'idv_required',
        sessionId: action.sessionId,
        message: action.message,
        steps: action.steps,
        idvMethod: action.idvMethod,
      }
    case 'IDV_REDIRECT':
      return { status: 'idv_redirecting', redirectUrl: action.redirectUrl }
    case 'RECONCILING':
      return { status: 'reconciling', sessionId: action.sessionId, message: action.message }
    case 'COMPLETING':
      return { status: 'completing', sessionId: action.sessionId }
    case 'AUTHENTICATED':
      return { status: 'authenticated' }
    case 'ERROR':
      return { status: 'error', message: action.message, retryable: action.retryable }
    case 'RESET':
      return { status: 'idle' }
  }
}

export function useWalletAuth() {
  const [state, dispatch] = useReducer(reducer, { status: 'idle' })

  const startSession = useCallback(async (opts?: { forceReconciliation?: boolean }) => {
    dispatch({ type: 'CREATE' })
    try {
      const body = opts?.forceReconciliation ? JSON.stringify({ forceReconciliation: true }) : undefined
      const res = await fetch('/api/wallet/sessions', {
        method: 'POST',
        ...(body ? { headers: { 'Content-Type': 'application/json' }, body } : {}),
      })
      if (!res.ok) throw new Error('Failed to create session')
      const data = await res.json()
      dispatch({
        type: 'SESSION_CREATED',
        sessionId: data.sessionId,
        qrCodeDataUri: data.qrCodeDataUri,
        requestUri: data.requestUri,
      })
    } catch (err) {
      dispatch({
        type: 'ERROR',
        message: err instanceof Error ? err.message : 'Failed to start wallet login',
        retryable: true,
      })
    }
  }, [])

  const pollStatus = useCallback(async (sessionId: string) => {
    try {
      const res = await fetch(`/api/wallet/sessions/${sessionId}/status`)
      if (!res.ok) throw new Error('Failed to poll status')
      const data = await res.json()

      switch (data.status) {
        case 'VERIFIED':
        case 'COMPLETED':
          dispatch({ type: 'COMPLETING', sessionId })
          break
        case 'IDV_REQUIRED':
          dispatch({
            type: 'IDV_REQUIRED',
            sessionId,
            message: data.message || 'Identity verification required',
            steps: data.idvSteps || [],
            idvMethod: data.idvMethod,
          })
          break
        case 'RECONCILING':
        case 'VERIFYING':
          dispatch({
            type: 'RECONCILING',
            sessionId,
            message: data.message || 'Reconciliation in progress',
          })
          break
        case 'FAILED':
          dispatch({
            type: 'ERROR',
            message: data.message || 'Reconciliation failed',
            retryable: false,
          })
          break
        case 'ERROR':
        case 'EXPIRED':
          dispatch({ type: 'ERROR', message: data.message || 'Session expired', retryable: true })
          break
        // CREATED, PENDING, INTERACTION_STARTED: keep polling
      }
      return data.status
    } catch (err) {
      dispatch({
        type: 'ERROR',
        message: err instanceof Error ? err.message : 'Polling failed',
        retryable: true,
      })
      return 'ERROR'
    }
  }, [])

  const completeAuth = useCallback(async (sessionId: string) => {
    dispatch({ type: 'COMPLETING', sessionId })
    try {
      // First, complete the auth bridge session (marks it as COMPLETED)
      const res = await fetch(`/api/wallet/sessions/${sessionId}/complete`, {
        method: 'POST',
      })

      if (res.status === 202) {
        // IDV required -- transition to IDV flow
        const data = await res.json()
        dispatch({
          type: 'IDV_REQUIRED',
          sessionId,
          message: data.message || 'Identity verification required',
          steps: data.idvSteps || [],
          idvMethod: data.idvMethod,
        })
        return false
      }

      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}))
        throw new Error(errorData.error || 'Failed to complete auth bridge session')
      }

      // Trigger STS OIDC flow with login_hint=oid4vp:{sessionId}
      // This makes NextAuth perform a standard OIDC authorize flow to the STS,
      // which finds the completed OID4VP session and issues STS tokens.
      // The tokens are stored server-side by NextAuth (BFF pattern).
      // Note: OIDC providers require browser redirects, so we let signIn redirect.
      // Third argument = additional authorization URL parameters (forwarded to STS).
      await signIn(
        'sts-wallet',
        { redirectTo: '/wallet/holder' },
        { login_hint: `oid4vp:${sessionId}` },
      )

      // signIn redirects the browser, so we won't reach here
      dispatch({ type: 'AUTHENTICATED' })
      return true
    } catch (err) {
      dispatch({
        type: 'ERROR',
        message: err instanceof Error ? err.message : 'Auth completion failed',
        retryable: true,
      })
      return false
    }
  }, [])

  const initiateIdv = useCallback(async (sessionId: string) => {
    try {
      const res = await fetch(`/api/wallet/sessions/${sessionId}/idv/initiate`, {
        method: 'POST',
      })
      if (!res.ok) throw new Error('Failed to initiate IDV')
      const data = await res.json()

      // Defensive: if orchestrator resolved without redirect (SkipReconciliation / UseExistingBinding),
      // complete auth directly. This should not happen in normal flow since the selector now runs
      // at /complete time, but guards against edge cases.
      if (!data.redirectUrl) {
        await completeAuth(sessionId)
        return null
      }

      dispatch({ type: 'IDV_REDIRECT', redirectUrl: data.redirectUrl })
      return data.redirectUrl
    } catch (err) {
      dispatch({
        type: 'ERROR',
        message: err instanceof Error ? err.message : 'IDV initiation failed',
        retryable: true,
      })
      return null
    }
  }, [completeAuth])

  const reset = useCallback(() => dispatch({ type: 'RESET' }), [])

  return { state, startSession, pollStatus, initiateIdv, completeAuth, reset }
}
