import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function AdminPage() {
  const t = await getTranslations('admin')

  return (
    <div>
      <h1>{t('title')}</h1>
      <Card title={t('overviewTitle')}>
        <p>{t('overviewDescription')}</p>
      </Card>
    </div>
  )
}
