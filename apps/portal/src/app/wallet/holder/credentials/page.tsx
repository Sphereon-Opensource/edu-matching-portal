import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function CredentialsPage() {
  const t = await getTranslations('holder')

  return (
    <div>
      <h1>{t('credentialStoreTitle')}</h1>
      <Card title={t('credentialStoreTitle')}>
        <p>{t('credentialStoreDescription')}</p>
      </Card>
    </div>
  )
}
