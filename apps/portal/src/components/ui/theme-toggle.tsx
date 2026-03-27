'use client'

import clsx from 'clsx'
import { useThemeMode } from '@sphereon/theme-react'
import styles from './theme-toggle.module.css'
import type { ThemeMode } from '@sphereon/theme-react'

const modes: { value: ThemeMode; icon: string; label: string }[] = [
  { value: 'light', icon: '\u2600', label: 'Light' },
  { value: 'dark', icon: '\u263E', label: 'Dark' },
  { value: 'system', icon: '\u2699', label: 'Auto' },
]

export function ThemeToggle() {
  const { mode, setMode } = useThemeMode()

  return (
    <div className={styles.toggle} role="radiogroup" aria-label="Theme">
      {modes.map((m) => (
        <button
          key={m.value}
          className={clsx(styles.option, mode === m.value && styles.optionActive)}
          onClick={() => setMode(m.value)}
          aria-label={m.label}
          aria-checked={mode === m.value}
          role="radio"
          title={m.label}
        >
          {m.icon}
        </button>
      ))}
    </div>
  )
}
