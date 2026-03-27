'use client'

import Link from 'next/link'
import { usePathname, useSearchParams } from 'next/navigation'
import { useTranslations } from 'next-intl'
import { signOut, useSession } from 'next-auth/react'
import { useEffect, useState } from 'react'
import clsx from 'clsx'
import styles from './sidebar.module.css'
import { BrandLogo } from '@/components/ui/brand-logo'

interface CatalogStore {
  storeId: string
  label: string
}

export function Sidebar() {
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const { data: session } = useSession()
  const t = useTranslations('nav')
  const [stores, setStores] = useState<CatalogStore[]>([])

  const storeParam = searchParams.get('store')
  const isDocuments = pathname === '/documents'

  useEffect(() => {
    fetch('/api/blob-stores/catalog')
      .then((res) => (res.ok ? res.json() : null))
      .then((data) => {
        if (data?.stores) {
          setStores(data.stores.map((s: CatalogStore) => ({ storeId: s.storeId, label: s.label })))
        }
      })
      .catch(() => {})
  }, [])

  const handleLogout = () => {
    signOut({ redirectTo: '/login' })
  }

  return (
    <aside className={styles.sidebar}>
      <div className={styles.logo}>
          <BrandLogo className={styles.logoImage} />
        </div>
      <nav className={styles.nav}>
        <Link
          href="/documents"
          className={clsx(styles.navItem, isDocuments && !storeParam && styles.navItemActive)}
        >
          {t('documents')}
        </Link>
        {stores.map((store) => (
          <Link
            key={store.storeId}
            href={`/documents?store=${store.storeId}`}
            className={clsx(
              styles.navItem,
              styles.navSubItem,
              isDocuments && storeParam === store.storeId && styles.navItemActive,
            )}
          >
            {store.label}
          </Link>
        ))}
      </nav>
      <div className={styles.sidebarBottom}>
        <button className={styles.logoutButton} onClick={handleLogout}>
          {t('logout')}
        </button>
        <div style={{ marginTop: 'var(--spacing-sm)' }} />
        <Link
          href="/wallet/profile"
          className={clsx(styles.navItem, pathname === '/wallet/profile' && styles.navItemActive)}
        >
          {t('profile')}
        </Link>
        {session?.user?.name && (
          <div className={styles.footer}>{session.user.name}</div>
        )}
      </div>
    </aside>
  )
}
