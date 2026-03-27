import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function HolderPage() {
  const t = await getTranslations('holder')

  return (
    <div>
      <h1>{t('title')}</h1>
      <Card title={t('credentialsTitle')}>
        <p>{t('credentialsEmpty')}</p>
      </Card>
    </div>
  )
}
