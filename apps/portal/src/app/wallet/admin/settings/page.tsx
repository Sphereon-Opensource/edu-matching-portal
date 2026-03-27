import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function AdminSettingsPage() {
  const t = await getTranslations('admin')

  return (
    <div>
      <h1>{t('settingsTitle')}</h1>
      <Card title={t('settingsCardTitle')}>
        <p>{t('settingsDescription')}</p>
      </Card>
    </div>
  )
}
