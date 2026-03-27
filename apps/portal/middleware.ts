export { auth as middleware } from '@/lib/auth'

export const config = {
  // Protect wallet and documents routes, skip API/static/next internals
  matcher: ['/wallet/:path*', '/documents'],
}
