'use client'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { ThemeProvider, allComponentTokenDefaults } from '@sphereon/theme-react'
import { ToastProvider } from '@sphereon/ui-react'
import { getPortalDefaults } from '@/theme/portal-defaults'
import { getBrandingConfig, getColorConfig } from '@/config/branding'

const branding = getBrandingConfig()
const colorConfig = getColorConfig(branding)

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            staleTime: 60 * 1000,
            refetchOnWindowFocus: false,
          },
        },
      }),
  )

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider
        colorConfig={colorConfig}
        appName={branding.appName}
        logoUrl={branding.logoUrl}
        logoDarkUrl={branding.logoDarkUrl}
        tokenOverrides={(variant) => ({
          ...allComponentTokenDefaults,
          ...getPortalDefaults(variant),
        })}
      >
        <ToastProvider>
          {children}
        </ToastProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}
