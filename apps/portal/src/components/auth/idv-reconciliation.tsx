'use client'

import { useTranslations } from 'next-intl'
import { Spinner } from '@/components/ui/spinner'
import type { WalletAuthState } from '@/lib/hooks/use-wallet-auth'
import styles from './idv-reconciliation.module.css'

interface IdvReconciliationProps {
  state: WalletAuthState
  onInitiateIdv: (sessionId: string) => Promise<string | null>
  onBack?: () => void
}

export function IdvReconciliation({ state, onInitiateIdv, onBack }: IdvReconciliationProps) {
  const t = useTranslations('idv')
  const tc = useTranslations('common')

  // Show reconciling state (backend is actively processing)
  if (state.status === 'reconciling') {
    return (
      <div className={styles.container} data-testid="idv-reconciling">
        <h2 className={styles.title}>{t('reconcilingTitle')}</h2>
        <p className={styles.description}>
          {state.message || t('reconcilingDescription')}
        </p>
        <div className={styles.waitingIndicator}>
          <Spinner size="small" />
          <span>{t('reconcilingWaiting')}</span>
        </div>
      </div>
    )
  }

  if (state.status !== 'idv_required') return null

  const handleLink = async () => {
    const url = await onInitiateIdv(state.sessionId)
    if (url) {
      window.location.href = url
    }
  }

  const idvMethod = state.idvMethod

  // Manual review: show waiting state, no user action needed
  if (idvMethod === 'MANUAL_REVIEW') {
    return (
      <div className={styles.container} data-testid="idv-required">
        <h2 className={styles.title}>{t('manualReviewTitle')}</h2>
        <p className={styles.description}>
          {state.message || t('manualReviewDescription')}
        </p>
        <div className={styles.waitingIndicator}>
          <Spinner size="small" />
          <span>{t('manualReviewWaiting')}</span>
        </div>
      </div>
    )
  }

  // Default: federation redirect flow
  return (
    <div className={styles.container} data-testid="idv-required">
      <h2 className={styles.title}>{t('title')}</h2>
      <p className={styles.description}>{t('description')}</p>

      {state.steps.length > 0 && (
        <div className={styles.steps}>
          {state.steps.map((step, i) => (
            <div key={i} className={styles.step}>
              <span className={styles.stepBadge}>{i + 1}</span>
              {step.label}
            </div>
          ))}
        </div>
      )}

      <div className={styles.actions}>
        <button className={styles.providerButton} onClick={handleLink} data-testid="idv-link-button">
          {t('linkButton')}
        </button>
        {onBack && (
          <button className={styles.backButton} onClick={onBack}>
            {tc('back')}
          </button>
        )}
      </div>
    </div>
  )
}
