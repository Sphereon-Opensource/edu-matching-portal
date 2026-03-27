import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

// Mock next-intl
vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}))

// Mock next/link
vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}))

// Mock @sphereon/ui-react Card
vi.mock('@sphereon/ui-react', () => ({
  Card: ({ children, className }: { children: React.ReactNode; className?: string }) => (
    <div data-testid="card" className={className}>{children}</div>
  ),
}))

describe('Profile page', () => {
  it('renders user info when authenticated', async () => {
    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: {
          user: {
            id: 'user-1',
            name: 'Jan de Vries',
            email: 'jan@kw1c.nl',
            institutionId: 'kw1c.nl',
            federated_subject: 'urn:collab:person:surfnet.nl:jan',
            authMethod: 'federated' as const,
            assurance: { acr: 'urn:test:acr', amr: ['fed'] },
          },
          expires: '2026-03-26T00:00:00.000Z',
          accessToken: 'test-access-token',
          idToken: 'test-id-token',
        },
        status: 'authenticated',
      }),
    }))

    const { default: ProfilePage } = await import('@/app/wallet/profile/page')
    render(<ProfilePage />)

    expect(screen.getByText('title')).toBeInTheDocument()
    expect(screen.getByText('Jan de Vries')).toBeInTheDocument()
    expect(screen.getByText('jan@kw1c.nl')).toBeInTheDocument()
    expect(screen.getByText('kw1c.nl')).toBeInTheDocument()
    expect(screen.getByText('authMethodFederated')).toBeInTheDocument()
  })

  it('renders wallet auth method badge', async () => {
    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: {
          user: {
            id: 'user-2',
            name: 'Wallet User',
            authMethod: 'wallet' as const,
          },
          expires: '2026-03-26T00:00:00.000Z',
        },
        status: 'authenticated',
      }),
    }))

    const { default: ProfilePage } = await import('@/app/wallet/profile/page')
    render(<ProfilePage />)

    expect(screen.getByText('authMethodWallet')).toBeInTheDocument()
  })

  it('renders empty wallet list', async () => {
    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: { user: { id: 'u' }, expires: '' },
        status: 'authenticated',
      }),
    }))

    const { default: ProfilePage } = await import('@/app/wallet/profile/page')
    render(<ProfilePage />)

    expect(screen.getByText('walletsEmpty')).toBeInTheDocument()
  })

  it('links to token inspector', async () => {
    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: { user: { id: 'u' }, expires: '' },
        status: 'authenticated',
      }),
    }))

    const { default: ProfilePage } = await import('@/app/wallet/profile/page')
    render(<ProfilePage />)

    const link = screen.getByText(/tokensLink/)
    expect(link).toBeInTheDocument()
    expect(link.closest('a')).toHaveAttribute('href', '/wallet/profile/tokens')
  })
})
