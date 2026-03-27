'use client'

import { useEffect } from 'react'
import { useTranslations } from 'next-intl'
import { QRCodeSVG } from 'qrcode.react'
import { Spinner } from '@/components/ui/spinner'
import { Button } from '@/components/ui/button'
import { useWalletSessionStatus } from '@/queries/wallet-sessions'
import type { WalletAuthState } from '@/lib/hooks/use-wallet-auth'
import styles from './qr-code-login.module.css'

interface QrCodeLoginProps {
  state: WalletAuthState
  onPollStatus: (sessionId: string) => Promise<string>
  onRetry?: () => void
}

export function QrCodeLogin({ state, onPollStatus, onRetry }: QrCodeLoginProps) {
  const t = useTranslations('wallet')
  const tc = useTranslations('common')
  const sessionId = state.status === 'pending' ? state.sessionId : null
  const isPending = state.status === 'pending'

  const { data, error } = useWalletSessionStatus(sessionId, isPending)

  // When TanStack Query returns new data, forward it to the state machine
  useEffect(() => {
    if (!data || !sessionId || !isPending) return

    const status = data.status
    if (
      status === 'VERIFIED' ||
      status === 'COMPLETED' ||
      status === 'IDV_REQUIRED' ||
      status === 'RECONCILING' ||
      status === 'VERIFYING' ||
      status === 'FAILED' ||
      status === 'ERROR' ||
      status === 'EXPIRED'
    ) {
      onPollStatus(sessionId)
    }
  }, [data, sessionId, isPending, onPollStatus])

  if (!isPending) return null

  // Session expired or unavailable
  if (error) {
    return (
      <div className={styles.container} data-testid="qr-code-login">
        <p className={styles.errorText}>{t('sessionExpired')}</p>
        {onRetry && (
          <Button variant="secondary" onClick={onRetry}>
            {tc('tryAgain')}
          </Button>
        )}
      </div>
    )
  }

  const handleOpenWallet = () => {
    window.location.href = state.requestUri
  }

  return (
    <div className={styles.container} data-testid="qr-code-login">
      <div className={styles.qrWrapper} data-testid="qr-code">
        <QRCodeSVG value={state.requestUri} size={240} />
      </div>
      <p className={styles.instructions}>{t('qrInstructions')}</p>
      <button type="button" className={styles.openWalletBtn} onClick={handleOpenWallet}>
        {t('openWallet')}
      </button>
      <div className={styles.status}>
        <Spinner size="small" />
        {t('waitingForResponse')}
      </div>
    </div>
  )
}
