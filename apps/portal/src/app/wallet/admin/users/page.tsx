import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function AdminUsersPage() {
  const t = await getTranslations('admin')

  return (
    <div>
      <h1>{t('usersTitle')}</h1>
      <Card title={t('usersCardTitle')}>
        <p>{t('usersDescription')}</p>
      </Card>
    </div>
  )
}
