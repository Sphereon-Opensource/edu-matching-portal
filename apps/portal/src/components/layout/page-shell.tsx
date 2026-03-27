import type { ReactNode } from 'react'
import styles from './page-shell.module.css'

interface PageShellProps {
  children: ReactNode
}

export function PageShell({ children }: PageShellProps) {
  return <main className={styles.shell}>{children}</main>
}
