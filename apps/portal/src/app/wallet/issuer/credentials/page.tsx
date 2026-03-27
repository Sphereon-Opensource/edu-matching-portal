import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function IssuerCredentialsPage() {
  const t = await getTranslations('issuer')

  return (
    <div>
      <h1>{t('issuedTitle')}</h1>
      <Card title={t('templatesTitle')}>
        <p>{t('templatesDescription')}</p>
      </Card>
    </div>
  )
}
