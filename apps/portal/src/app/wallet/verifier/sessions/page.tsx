import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function VerifierSessionsPage() {
  const t = await getTranslations('verifier')

  return (
    <div>
      <h1>{t('sessionsTitle')}</h1>
      <Card title={t('activeSessionsTitle')}>
        <p>{t('activeSessionsEmpty')}</p>
      </Card>
    </div>
  )
}
