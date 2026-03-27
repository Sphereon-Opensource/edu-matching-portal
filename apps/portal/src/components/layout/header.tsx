'use client'

import styles from './header.module.css'
import { ThemeToggle } from '@/components/ui/theme-toggle'
import { LanguageSwitcher } from '@/components/ui/language-switcher'

interface HeaderProps {
  title?: string
}

export function Header({ title }: HeaderProps) {
  return (
    <header className={styles.header}>
      <span className={styles.title}>{title}</span>
      <div className={styles.actions}>
        <LanguageSwitcher />
        <ThemeToggle />
      </div>
    </header>
  )
}
