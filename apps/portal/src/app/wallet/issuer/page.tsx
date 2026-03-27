import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function IssuerPage() {
  const t = await getTranslations('issuer')

  return (
    <div>
      <h1>{t('title')}</h1>
      <Card title={t('issuanceTitle')}>
        <p>{t('issuanceDescription')}</p>
      </Card>
    </div>
  )
}
