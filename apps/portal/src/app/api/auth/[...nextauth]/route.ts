import { handlers } from '@/lib/auth'
import { type NextRequest, NextResponse } from 'next/server'

// Wrap the GET handler to intercept OAuth callback errors from the STS.
// NextAuth loses the error_description and only passes error=OAuthCallbackError.
// We catch the STS error_description before NextAuth processes it and redirect
// to the login page with the real error message.
export async function GET(request: NextRequest) {
  const url = new URL(request.url)

  // Only intercept callback routes with error parameters
  if (url.pathname.includes('/callback/') && url.searchParams.has('error')) {
    const error = url.searchParams.get('error')
    const description = url.searchParams.get('error_description')
    if (description) {
      const loginUrl = new URL('/login', request.url)
      loginUrl.searchParams.set('error', description)
      return NextResponse.redirect(loginUrl)
    }
    if (error && error !== 'OAuthCallbackError') {
      const loginUrl = new URL('/login', request.url)
      loginUrl.searchParams.set('error', error)
      return NextResponse.redirect(loginUrl)
    }
  }

  return handlers.GET(request)
}

export const { POST } = handlers
