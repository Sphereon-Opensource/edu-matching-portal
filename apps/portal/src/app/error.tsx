'use client'

import { useTranslations } from 'next-intl'

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  const t = useTranslations('error')
  const tc = useTranslations('common')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', gap: '16px' }}>
      <h1>{t('title')}</h1>
      <p>{error.message}</p>
      <button onClick={reset}>{tc('tryAgain')}</button>
    </div>
  )
}
