'use client'

import { useState } from 'react'
import Link from 'next/link'
import { useTranslations } from 'next-intl'
import { useSession } from 'next-auth/react'
import { Card } from '@/components/ui/card'
import styles from './tokens.module.css'

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const payload = parts[1]
    const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(decoded)
  } catch {
    return null
  }
}

function TokenCard({ title, token, t }: { title: string; token?: string; t: (key: string) => string }) {
  const [showRaw, setShowRaw] = useState(false)

  if (!token) {
    return (
      <Card title={title}>
        <p className={styles.notPresent}>{t('notPresent')}</p>
      </Card>
    )
  }

  const decoded = decodeJwtPayload(token)

  return (
    <Card title={title}>
      {decoded && (
        <div>
          <strong style={{ fontSize: '0.85rem' }}>{t('decodedPayload')}</strong>
          <pre className={styles.pre}>{JSON.stringify(decoded, null, 2)}</pre>
        </div>
      )}
      <button className={styles.toggleButton} onClick={() => setShowRaw(!showRaw)}>
        {showRaw ? t('hideRaw') : t('showRaw')}
      </button>
      {showRaw && <pre className={styles.pre} style={{ marginTop: 'var(--spacing-sm)' }}>{token}</pre>}
    </Card>
  )
}

export default function TokensPage() {
  const { data: session } = useSession()
  const t = useTranslations('profile')

  return (
    <div>
      <Link href="/wallet/profile" className={styles.backLink}>&larr; {t('title')}</Link>
      <h1>{t('tokensTitle')}</h1>
      <p className={styles.description}>{t('tokensDescription')}</p>

      <div className={styles.stack}>
        <Card title={t('sessionInfo')}>
          <pre className={styles.pre}>
            {JSON.stringify(
              { user: session?.user, expires: session?.expires },
              null,
              2,
            )}
          </pre>
        </Card>

        <TokenCard title={t('idToken')} token={session?.idToken} t={t} />
        <TokenCard title={t('accessToken')} token={session?.accessToken} t={t} />
      </div>
    </div>
  )
}
