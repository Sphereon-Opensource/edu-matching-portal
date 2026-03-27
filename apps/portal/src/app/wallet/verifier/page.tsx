import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function VerifierPage() {
  const t = await getTranslations('verifier')

  return (
    <div>
      <h1>{t('title')}</h1>
      <Card title={t('requestsTitle')}>
        <p>{t('requestsDescription')}</p>
      </Card>
    </div>
  )
}
