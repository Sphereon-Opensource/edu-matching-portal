'use client'

import { ThemeToggle } from './theme-toggle'
import { LanguageSwitcher } from './language-switcher'
import styles from './toolbar.module.css'

export function Toolbar() {
  return (
    <div className={styles.toolbar}>
      <LanguageSwitcher />
      <ThemeToggle />
    </div>
  )
}
