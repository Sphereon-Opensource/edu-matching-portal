'use client'

import { Card as UiCard } from '@sphereon/ui-react'
import type { ReactNode } from 'react'

interface CardProps {
  title?: string
  children: ReactNode
  className?: string
}

export function Card({ title, children, className }: CardProps) {
  return (
    <UiCard className={className}>
      {title && <h3 style={{ margin: '0 0 var(--spacing-stack-sm)', fontSize: '1.1rem', fontWeight: 600 }}>{title}</h3>}
      {children}
    </UiCard>
  )
}
