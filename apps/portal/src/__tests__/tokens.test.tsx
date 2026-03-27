import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

vi.mock('next-intl', () => ({
  useTranslations: () => (key: string) => key,
}))

vi.mock('next/link', () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}))

vi.mock('@sphereon/ui-react', () => ({
  Card: ({ children, className }: { children: React.ReactNode; className?: string }) => (
    <div data-testid="card" className={className}>{children}</div>
  ),
}))

// Helper: create a valid-looking JWT with a given payload
function makeJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'none', typ: 'JWT' }))
  const body = btoa(JSON.stringify(payload))
  return `${header}.${body}.sig`
}

describe('Token Inspector page', () => {
  it('renders session info and decoded tokens', async () => {
    const idPayload = { sub: 'user-1', iss: 'https://sts.example.com', name: 'Jan' }
    const accessPayload = { sub: 'user-1', scope: 'openid profile', client_id: 'portal' }

    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: {
          user: { id: 'user-1', name: 'Jan de Vries' },
          expires: '2026-03-26T00:00:00.000Z',
          idToken: makeJwt(idPayload),
          accessToken: makeJwt(accessPayload),
        },
        status: 'authenticated',
      }),
    }))

    const { default: TokensPage } = await import('@/app/wallet/profile/tokens/page')
    render(<TokensPage />)

    expect(screen.getByText('tokensTitle')).toBeInTheDocument()
    expect(screen.getByText('tokensDescription')).toBeInTheDocument()

    // Session card present
    expect(screen.getByText('sessionInfo')).toBeInTheDocument()

    // Token cards present
    expect(screen.getByText('idToken')).toBeInTheDocument()
    expect(screen.getByText('accessToken')).toBeInTheDocument()

    // Decoded payloads rendered
    expect(screen.getAllByText('decodedPayload')).toHaveLength(2)
  })

  it('shows not present when tokens are missing', async () => {
    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: {
          user: { id: 'user-1' },
          expires: '',
        },
        status: 'authenticated',
      }),
    }))

    const { default: TokensPage } = await import('@/app/wallet/profile/tokens/page')
    render(<TokensPage />)

    const notPresentElements = screen.getAllByText('notPresent')
    expect(notPresentElements.length).toBeGreaterThanOrEqual(2)
  })

  it('toggles raw token visibility', async () => {
    const payload = { sub: 'user-1' }
    const token = makeJwt(payload)

    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: {
          user: { id: 'user-1' },
          expires: '',
          idToken: token,
        },
        status: 'authenticated',
      }),
    }))

    const { default: TokensPage } = await import('@/app/wallet/profile/tokens/page')
    render(<TokensPage />)

    // Raw token should not be visible initially
    expect(screen.queryByText(token)).not.toBeInTheDocument()

    // Click "Show raw token"
    const showButton = screen.getAllByText('showRaw')[0]
    fireEvent.click(showButton)

    // Raw token should now be visible
    expect(screen.getByText(token)).toBeInTheDocument()
  })

  it('has back link to profile', async () => {
    vi.doMock('next-auth/react', () => ({
      useSession: () => ({
        data: { user: { id: 'u' }, expires: '' },
        status: 'authenticated',
      }),
    }))

    const { default: TokensPage } = await import('@/app/wallet/profile/tokens/page')
    render(<TokensPage />)

    const backLink = screen.getByText(/title/)
    expect(backLink.closest('a')).toHaveAttribute('href', '/wallet/profile')
  })
})
