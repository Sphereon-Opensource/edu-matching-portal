import { Card } from '@/components/ui/card'
import { LoginMethodSelector } from '@/components/auth/login-method-selector'
import { Toolbar } from '@/components/ui/toolbar'
import { BrandLogo } from '@/components/ui/brand-logo'
import { getTranslations } from 'next-intl/server'
import styles from './login.module.css'

export default async function LoginPage() {
  const t = await getTranslations('login')

  return (
    <div className={styles.container}>
      <Toolbar />
      <div className={styles.logo}>
        <BrandLogo className={styles.logoImage} />
      </div>
      <div className={styles.card}>
        <Card title={t('title')}>
          <LoginMethodSelector />
        </Card>
      </div>
    </div>
  )
}
