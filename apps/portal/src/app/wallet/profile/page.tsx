'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useTranslations } from 'next-intl'
import { useSession } from 'next-auth/react'
import { Card } from '@/components/ui/card'
import styles from './profile.module.css'

interface LinkedWallet {
  id: string
  providerId: string
  institutionId?: string
  createdAt: string
  lastUsedAt?: string
  holderHashPrefix?: string
  reconcileRuleVersion?: string
}

function formatDateTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

export default function ProfilePage() {
  const { data: session } = useSession()
  const t = useTranslations('profile')
  const [wallets, setWallets] = useState<LinkedWallet[]>([])

  const fetchWallets = useCallback(() => {
    fetch('/api/wallet/bindings')
      .then((res) => (res.ok ? res.json() : []))
      .then(setWallets)
      .catch(() => setWallets([]))
  }, [])

  useEffect(() => { fetchWallets() }, [fetchWallets])

  const handleRevoke = async (bindingId: string) => {
    if (!confirm(t('revokeConfirm'))) return
    const res = await fetch(`/api/wallet/bindings/${bindingId}`, { method: 'DELETE' })
    if (res.ok) {
      fetchWallets()
    }
  }

  const user = session?.user

  return (
    <div>
      <h1>{t('title')}</h1>

      <div className={styles.grid}>
        <Card title={t('userInfoTitle')}>
          <table className={styles.infoTable}>
            <tbody>
              <tr>
                <td>{t('name')}</td>
                <td>{user?.name || '—'}</td>
              </tr>
              <tr>
                <td>{t('email')}</td>
                <td>{user?.email || '—'}</td>
              </tr>
              <tr>
                <td>{t('institution')}</td>
                <td>{user?.institutionId || '—'}</td>
              </tr>
              <tr>
                <td>{t('eduid')}</td>
                <td>
                  {user?.eduid ? (
                    <span className={styles.walletId}>{user.eduid}</span>
                  ) : '—'}
                </td>
              </tr>
              <tr>
                <td>{t('principalName')}</td>
                <td>{user?.edupersonPrincipalName || '—'}</td>
              </tr>
              <tr>
                <td>{t('authMethod')}</td>
                <td>
                  <span className={styles.badgePrimary}>
                    {user?.authMethod === 'wallet'
                      ? t('authMethodWallet')
                      : t('authMethodFederated')}
                  </span>
                </td>
              </tr>
              {user?.assurance?.acr && (
                <tr>
                  <td>{t('acr')}</td>
                  <td><span className={styles.badge}>{user.assurance.acr}</span></td>
                </tr>
              )}
              {user?.assurance?.amr && (
                <tr>
                  <td>{t('amr')}</td>
                  <td>
                    {user.assurance.amr.map((m: string) => (
                      <span key={m} className={styles.badge} style={{ marginRight: 4 }}>{m}</span>
                    ))}
                  </td>
                </tr>
              )}
              <tr>
                <td>{t('sessionExpires')}</td>
                <td>{session?.expires ? new Date(session.expires).toLocaleString() : '—'}</td>
              </tr>
            </tbody>
          </table>
          <Link href="/wallet/profile/tokens" className={styles.tokenLink}>
            {t('tokensLink')} &rarr;
          </Link>
        </Card>

        <Card title={t('walletsTitle')}>
          <p style={{ fontSize: '0.9rem', color: 'var(--color-secondary)', marginBottom: 'var(--spacing-md)' }}>
            {t('walletsDescription')}
          </p>
          {wallets.length > 0 ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--spacing-md)' }}>
              {wallets.map((w) => (
                <div key={w.id} style={{
                  padding: 'var(--spacing-md)',
                  border: '1px solid var(--color-outline)',
                  borderRadius: 'var(--shape-cornerSmall)',
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'flex-start',
                }}>
                  <div>
                    <div style={{ fontWeight: 500 }}>
                      {w.providerId === 'surf' ? 'eduID (SURFconext)' : w.providerId}
                      {user?.authMethod === 'wallet' && (
                        <span className={styles.badgePrimary} style={{ marginLeft: 8, fontSize: '0.75rem' }}>
                          Active
                        </span>
                      )}
                    </div>
                    <div style={{ fontSize: '0.85rem', color: 'var(--color-secondary)', marginTop: 4 }}>
                      {t('walletLinkedOn')} {formatDateTime(w.createdAt)}
                    </div>
                    {w.lastUsedAt && (
                      <div style={{ fontSize: '0.8rem', color: 'var(--color-secondary)' }}>
                        Last used: {formatDateTime(w.lastUsedAt)}
                      </div>
                    )}
                  </div>
                  <button
                    onClick={() => handleRevoke(w.id)}
                    style={{
                      background: 'none',
                      border: '1px solid var(--color-error, #d32f2f)',
                      color: 'var(--color-error, #d32f2f)',
                      padding: '4px 12px',
                      borderRadius: 'var(--shape-cornerSmall)',
                      cursor: 'pointer',
                      fontSize: '0.8rem',
                    }}
                  >
                    {t('revokeWallet')}
                  </button>
                </div>
              ))}
            </div>
          ) : (
            <p className={styles.emptyState}>{t('walletsEmpty')}</p>
          )}
        </Card>
      </div>
    </div>
  )
}
