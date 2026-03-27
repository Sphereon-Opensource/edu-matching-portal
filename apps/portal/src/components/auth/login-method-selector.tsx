'use client'

import { useState, useEffect, useCallback } from 'react'
import { useSearchParams } from 'next/navigation'
import { signIn } from 'next-auth/react'
import { useTranslations } from 'next-intl'
import { WalletAuthFlow } from './wallet-auth-flow'
import type { WalletFlowPhase } from './wallet-auth-flow'
import styles from './login-method-selector.module.css'

interface FederationProvider {
  id: string
  name: string
  enabled: boolean
}

export function LoginMethodSelector() {
  const t = useTranslations('login')
  const searchParams = useSearchParams()
  const walletSession = searchParams.get('wallet_session')
  const idvComplete = searchParams.get('idv') === 'complete'
  const forceReconciliation = searchParams.get('force_reconciliation') === 'true'
  const error = searchParams.get('error')
  const [stsAvailable, setStsAvailable] = useState(true)
  const [authBridgeAvailable, setAuthBridgeAvailable] = useState(true)
  const [providers, setProviders] = useState<FederationProvider[]>([])
  const [activeTab, setActiveTab] = useState<'federation' | 'wallet'>('federation')
  const [walletKey, setWalletKey] = useState(0)
  const [walletFlowPhase, setWalletFlowPhase] = useState<WalletFlowPhase>('active')

  const checkHealth = useCallback(async () => {
    const [stsRes, bridgeRes] = await Promise.allSettled([
      fetch('/api/health/sts'),
      fetch('/api/health/auth-bridge'),
    ])
    setStsAvailable(stsRes.status === 'fulfilled' && stsRes.value.ok)
    setAuthBridgeAvailable(bridgeRes.status === 'fulfilled' && bridgeRes.value.ok)
  }, [])

  const fetchProviders = useCallback(async () => {
    try {
      const res = await fetch('/api/federation/providers')
      if (res.ok) {
        const data: FederationProvider[] = await res.json()
        setProviders(data.filter((p) => p.enabled))
      }
    } catch {
      setProviders([])
    }
  }, [])

  useEffect(() => {
    checkHealth()
    fetchProviders()
  }, [checkHealth, fetchProviders])

  // Auto-enter wallet tab when returning from IDV reconciliation
  useEffect(() => {
    if (walletSession && idvComplete) {
      setActiveTab('wallet')
    }
  }, [walletSession, idvComplete])

  const handleFederatedLogin = (providerId?: string) => {
    signIn(
      'sts',
      { redirectTo: '/wallet/holder' },
      providerId ? { provider: providerId } : undefined,
    )
  }

  const tabsLocked = activeTab === 'wallet' && (walletFlowPhase === 'reconciliation' || walletFlowPhase === 'completing')

  return (
    <div className={styles.container}>
      {error && (
        <p className={styles.error}>
          {error === 'Configuration'
            ? t('stsUnavailable')
            : error.replace(/_/g, ' ')}
        </p>
      )}
      {!stsAvailable && !error && (
        <p className={styles.error}>{t('stsUnavailable')}</p>
      )}
      {!authBridgeAvailable && (
        <p className={styles.error}>{t('authBridgeUnavailable')}</p>
      )}

      {!tabsLocked && (
        <p className={styles.description}>{t('description')}</p>
      )}

      {tabsLocked ? (
        <div className={styles.tabLocked}>
          <span className={styles.tabLockedLabel}>
            {walletFlowPhase === 'reconciliation' ? t('tabReconciliation') : t('tabCompleting')}
          </span>
        </div>
      ) : (
        <div className={styles.tabs}>
          <button
            className={`${styles.tab} ${activeTab === 'federation' ? styles.tabActive : ''}`}
            onClick={() => setActiveTab('federation')}
          >
            {t('tabFederation')}
          </button>
          <button
            className={`${styles.tab} ${activeTab === 'wallet' ? styles.tabActive : ''}`}
            onClick={() => { setActiveTab('wallet'); setWalletKey((k) => k + 1) }}
          >
            {t('tabWallet')}
          </button>
        </div>
      )}

      {activeTab === 'federation' && (
        <div className={styles.tabContent}>
          <p className={styles.tabHint}>{t('federationHint')}</p>
          {providers.map((provider) => (
            <button
              key={provider.id}
              className={styles.providerButton}
              onClick={() => handleFederatedLogin(provider.id)}
              disabled={!stsAvailable}
            >
              {provider.name}
            </button>
          ))}
        </div>
      )}

      {activeTab === 'wallet' && (
        <div className={styles.tabContent}>
          <WalletAuthFlow
            key={walletKey}
            onBack={() => setActiveTab('federation')}
            resumeSessionId={idvComplete ? walletSession : undefined}
            forceReconciliation={forceReconciliation}
            onFlowPhaseChange={setWalletFlowPhase}
          />
        </div>
      )}
    </div>
  )
}
