'use client'

import { useTranslations } from 'next-intl'
import { useCallback, useEffect, useRef } from 'react'
import { useWalletAuth } from '@/lib/hooks/use-wallet-auth'
import { QrCodeLogin } from './qr-code-login'
import { IdvReconciliation } from './idv-reconciliation'
import { Spinner } from '@/components/ui/spinner'
import { Button } from '@/components/ui/button'

export type WalletFlowPhase = 'active' | 'reconciliation' | 'completing'

interface WalletAuthFlowProps {
  onBack?: () => void
  resumeSessionId?: string | null
  forceReconciliation?: boolean
  onFlowPhaseChange?: (phase: WalletFlowPhase) => void
}

export function WalletAuthFlow({ onBack, resumeSessionId, forceReconciliation, onFlowPhaseChange }: WalletAuthFlowProps) {
  const t = useTranslations('wallet')
  const tc = useTranslations('common')
  const { state, startSession, pollStatus, initiateIdv, completeAuth, reset } = useWalletAuth()
  const completingRef = useRef(false)

  useEffect(() => {
    if (resumeSessionId && state.status === 'idle') {
      // Returning from IDV reconciliation -- go straight to completion
      // Do NOT create a new OID4VP session
      if (completingRef.current) return
      completingRef.current = true
      completeAuth(resumeSessionId)
      return
    }
    if (state.status === 'idle' && !resumeSessionId) {
      startSession(forceReconciliation ? { forceReconciliation: true } : undefined)
    }
  }, [state.status, startSession, resumeSessionId, completeAuth, forceReconciliation])

  const handleRetry = useCallback(() => {
    completingRef.current = false
    reset()
  }, [reset])

  const handleComplete = useCallback(async (sid: string) => {
    if (completingRef.current) return
    completingRef.current = true
    await completeAuth(sid)
  }, [completeAuth])

  // Notify parent of flow phase changes
  useEffect(() => {
    if (!onFlowPhaseChange) return
    const s = state.status
    if (s === 'idv_required' || s === 'reconciling' || s === 'idv_redirecting') {
      onFlowPhaseChange('reconciliation')
    } else if (s === 'completing' || s === 'authenticated') {
      onFlowPhaseChange('completing')
    } else {
      onFlowPhaseChange('active')
    }
  }, [state.status, onFlowPhaseChange])

  // Trigger completion when polling detects VERIFIED/COMPLETED status
  const completingSessionId = state.status === 'completing' ? state.sessionId : null
  useEffect(() => {
    if (completingSessionId) {
      handleComplete(completingSessionId)
    }
  }, [completingSessionId, handleComplete])

  if (state.status === 'creating') {
    return (
      <div
        style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px', padding: '32px' }}
        data-testid="wallet-auth-flow"
      >
        <Spinner />
        <p>{t('startingLogin')}</p>
      </div>
    )
  }

  if (state.status === 'error') {
    return (
      <div
        style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px', padding: '32px' }}
        data-testid="wallet-auth-error"
      >
        <p>{state.message}</p>
        <div style={{ display: 'flex', gap: '12px' }}>
          {state.retryable && (
            <Button variant="secondary" onClick={handleRetry}>
              {tc('tryAgain')}
            </Button>
          )}
          {onBack && (
            <Button variant="outline" onClick={onBack}>
              {tc('back')}
            </Button>
          )}
        </div>
      </div>
    )
  }

  if (state.status === 'completing' || state.status === 'authenticated') {
    return (
      <div
        style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px', padding: '32px' }}
        data-testid="wallet-auth-flow"
      >
        <Spinner />
        <p>{t('completingAuth')}</p>
      </div>
    )
  }

  return (
    <div data-testid="wallet-auth-flow">
      <QrCodeLogin state={state} onPollStatus={pollStatus} onRetry={handleRetry} />
      <IdvReconciliation state={state} onInitiateIdv={initiateIdv} onBack={onBack} />
      {onBack && state.status !== 'idv_required' && (
        <div style={{ display: 'flex', justifyContent: 'center', paddingTop: '16px' }}>
          <Button variant="outline" size="large" onClick={onBack}>
            {tc('back')}
          </Button>
        </div>
      )}
    </div>
  )
}
