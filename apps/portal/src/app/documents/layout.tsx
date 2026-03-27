import { SessionProvider } from 'next-auth/react'
import { Sidebar } from '@/components/layout/sidebar'
import { Header } from '@/components/layout/header'
import { PageShell } from '@/components/layout/page-shell'

export default function DocumentsLayout({ children }: { children: React.ReactNode }) {
  return (
    <SessionProvider>
      <Sidebar />
      <Header />
      <PageShell>{children}</PageShell>
    </SessionProvider>
  )
}
