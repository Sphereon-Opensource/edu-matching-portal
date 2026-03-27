'use client'

import { useTheme } from '@sphereon/theme-react'

export function BrandLogo({ className }: { className?: string }) {
  const { appName, branding, resolvedMode } = useTheme()
  const logoSrc =
    resolvedMode === 'dark' && branding.logoDarkUrl ? branding.logoDarkUrl : branding.logoUrl

  if (logoSrc) {
    return <img src={logoSrc} alt={appName} className={className} />
  }
  return <>{appName}</>
}
