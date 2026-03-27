import { getTranslations } from 'next-intl/server'
import { Card } from '@/components/ui/card'

export default async function SessionDetailPage({ params }: { params: Promise<{ sessionId: string }> }) {
  const t = await getTranslations('verifier')

  return (
    <div>
      <h1>{t('sessionDetailTitle')}</h1>
      <Card title={t('sessionInfoTitle')}>
        <p>{t('sessionInfoDescription')}</p>
      </Card>
    </div>
  )
}
