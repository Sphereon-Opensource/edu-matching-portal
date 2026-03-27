'use client'

import { useState, useRef, useEffect, useCallback } from 'react'
import { useLocale } from 'next-intl'
import styles from './language-switcher.module.css'

const languages = [
  { code: 'en', label: 'English', flag: 'EN' },
  { code: 'nl', label: 'Nederlands', flag: 'NL' },
] as const

function setLocaleCookie(locale: string) {
  document.cookie = `locale=${locale};path=/;max-age=${365 * 24 * 60 * 60};SameSite=Lax`
}

export function LanguageSwitcher() {
  const currentLocale = useLocale()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  const current = languages.find((l) => l.code === currentLocale) ?? languages[0]

  const handleSelect = useCallback((code: string) => {
    setOpen(false)
    if (code === currentLocale) return
    setLocaleCookie(code)
    window.location.reload()
  }, [currentLocale])

  // Close on outside click
  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  // Close on Escape
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open])

  return (
    <div className={styles.wrapper} ref={ref}>
      <button
        className={styles.trigger}
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-haspopup="listbox"
        aria-label={`Language: ${current.label}`}
      >
        <span className={styles.flag}>{current.flag}</span>
        <svg className={styles.chevron} width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden="true">
          <path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>

      {open && (
        <ul className={styles.dropdown} role="listbox" aria-label="Select language">
          {languages.map((lang) => (
            <li key={lang.code} role="option" aria-selected={lang.code === currentLocale}>
              <button
                className={styles.item}
                onClick={() => handleSelect(lang.code)}
                data-active={lang.code === currentLocale || undefined}
              >
                <span className={styles.itemFlag}>{lang.flag}</span>
                <span className={styles.itemLabel}>{lang.label}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
