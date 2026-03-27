import { getRequestConfig } from 'next-intl/server'
import { cookies, headers } from 'next/headers'

export const locales = ['en', 'nl'] as const
export type Locale = (typeof locales)[number]
export const defaultLocale: Locale = 'en'

export default getRequestConfig(async () => {
  const cookieStore = await cookies()
  const headerStore = await headers()

  // Resolve locale: cookie > Accept-Language header > default
  let locale: Locale = defaultLocale

  const cookieLocale = cookieStore.get('locale')?.value
  if (cookieLocale && locales.includes(cookieLocale as Locale)) {
    locale = cookieLocale as Locale
  } else {
    const acceptLanguage = headerStore.get('accept-language') || ''
    const preferred = acceptLanguage.split(',').map((l) => l.split(';')[0].trim().substring(0, 2))
    const match = preferred.find((l) => locales.includes(l as Locale))
    if (match) locale = match as Locale
  }

  return {
    locale,
    messages: (await import(`../../messages/${locale}.json`)).default,
  }
})
