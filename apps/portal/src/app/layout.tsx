import type { Metadata } from 'next'
import { Poppins } from 'next/font/google'
import { NextIntlClientProvider } from 'next-intl'
import { getLocale, getMessages } from 'next-intl/server'
import { cookies } from 'next/headers'
import './globals.css'
import { AppProviders } from '@/providers/app-providers'
import { ThemeScript } from '@sphereon/theme-react'
import { resolveInitialMode } from '@sphereon/theme-react/server'
import { getBrandingConfig } from '@/config/branding'

const poppins = Poppins({
  subsets: ['latin'],
  weight: ['300', '400', '500', '600', '700'],
  variable: '--font-poppins',
})

const brandingConfig = getBrandingConfig()

export const metadata: Metadata = {
  title: brandingConfig.appName,
  ...(brandingConfig.faviconUrl ? { icons: { icon: brandingConfig.faviconUrl } } : {}),
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const locale = await getLocale()
  const messages = await getMessages()
  const cookieStore = await cookies()
  const initialMode = resolveInitialMode(cookieStore)

  return (
    <html lang={locale} suppressHydrationWarning>
      <head>
        <ThemeScript defaultMode={initialMode} />
      </head>
      <body className={poppins.variable}>
        <NextIntlClientProvider messages={messages}>
          <AppProviders>{children}</AppProviders>
        </NextIntlClientProvider>
      </body>
    </html>
  )
}
