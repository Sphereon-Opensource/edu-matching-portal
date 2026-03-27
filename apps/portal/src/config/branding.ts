import type { ThemeColorConfig } from '@sphereon/theme-react'

export interface PortalBrandingConfig {
  appName: string
  primaryColor: string
  secondaryColor?: string
  neutralColor?: string
  logoUrl?: string
  logoDarkUrl?: string
  faviconUrl?: string
}

export function getBrandingConfig(): PortalBrandingConfig {
  return {
    appName: process.env.NEXT_PUBLIC_APP_NAME || 'Portal',
    primaryColor: process.env.NEXT_PUBLIC_PRIMARY_COLOR || '#0085CA',
    secondaryColor: process.env.NEXT_PUBLIC_SECONDARY_COLOR || undefined,
    neutralColor: process.env.NEXT_PUBLIC_NEUTRAL_COLOR || undefined,
    logoUrl: process.env.NEXT_PUBLIC_LOGO_URL || undefined,
    logoDarkUrl: process.env.NEXT_PUBLIC_LOGO_DARK_URL || undefined,
    faviconUrl: process.env.NEXT_PUBLIC_FAVICON_URL || undefined,
  }
}

export function getColorConfig(branding: PortalBrandingConfig): ThemeColorConfig {
  if (branding.secondaryColor || branding.neutralColor) {
    return {
      mode: 'multi-seed',
      primary: branding.primaryColor,
      secondary: branding.secondaryColor,
      neutral: branding.neutralColor,
    }
  }
  return { mode: 'seed', seed: branding.primaryColor }
}
