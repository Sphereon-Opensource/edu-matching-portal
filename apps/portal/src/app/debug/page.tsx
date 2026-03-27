'use client'

import { SessionProvider, useSession, signIn, signOut } from 'next-auth/react'
import { useState } from 'react'

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const payload = parts[1]
    const decoded = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(decoded)
  } catch {
    return null
  }
}

function TokenDisplay({ label, token }: { label: string; token?: string }) {
  const [expanded, setExpanded] = useState(false)

  if (!token) {
    return (
      <div style={{ marginBottom: 24 }}>
        <h3 style={{ margin: '0 0 8px', color: '#888' }}>{label}</h3>
        <p style={{ color: '#666', fontStyle: 'italic' }}>Not present</p>
      </div>
    )
  }

  const decoded = decodeJwtPayload(token)

  return (
    <div style={{ marginBottom: 24 }}>
      <h3 style={{ margin: '0 0 8px' }}>{label}</h3>
      {decoded && (
        <div style={{ marginBottom: 8 }}>
          <strong>Decoded payload:</strong>
          <pre
            style={{
              background: '#1a1a2e',
              color: '#0f0',
              padding: 16,
              borderRadius: 8,
              overflow: 'auto',
              maxHeight: 400,
              fontSize: 13,
            }}
          >
            {JSON.stringify(decoded, null, 2)}
          </pre>
        </div>
      )}
      <button
        onClick={() => setExpanded(!expanded)}
        style={{
          background: '#333',
          color: '#fff',
          border: 'none',
          padding: '6px 12px',
          borderRadius: 4,
          cursor: 'pointer',
          marginBottom: 8,
        }}
      >
        {expanded ? 'Hide raw token' : 'Show raw token'}
      </button>
      {expanded && (
        <pre
          style={{
            background: '#111',
            color: '#ccc',
            padding: 12,
            borderRadius: 8,
            wordBreak: 'break-all',
            whiteSpace: 'pre-wrap',
            fontSize: 11,
          }}
        >
          {token}
        </pre>
      )}
    </div>
  )
}

function DebugContent() {
  const { data: session, status } = useSession()

  return (
    <div
      style={{
        maxWidth: 900,
        margin: '0 auto',
        padding: 32,
        fontFamily: 'monospace',
        color: '#eee',
        background: '#0a0a0a',
        minHeight: '100vh',
      }}
    >
      <h1 style={{ marginBottom: 8 }}>Auth Debug</h1>
      <p style={{ color: '#888', marginBottom: 24 }}>
        Status: <strong style={{ color: status === 'authenticated' ? '#0f0' : '#f80' }}>{status}</strong>
      </p>

      {status === 'unauthenticated' && (
        <div>
          <p>Not logged in. Sign in to see tokens.</p>
          <button
            onClick={() => signIn('sts')}
            style={{
              background: '#2563eb',
              color: '#fff',
              border: 'none',
              padding: '10px 20px',
              borderRadius: 6,
              cursor: 'pointer',
              fontSize: 16,
            }}
          >
            Login with STS (SURF)
          </button>
        </div>
      )}

      {status === 'authenticated' && session && (
        <>
          <div style={{ marginBottom: 24 }}>
            <h2 style={{ margin: '0 0 12px' }}>Session</h2>
            <pre
              style={{
                background: '#1a1a2e',
                color: '#7df',
                padding: 16,
                borderRadius: 8,
                overflow: 'auto',
                fontSize: 13,
              }}
            >
              {JSON.stringify(
                {
                  user: session.user,
                  expires: session.expires,
                },
                null,
                2,
              )}
            </pre>
          </div>

          <TokenDisplay label="ID Token (from STS)" token={session.idToken} />
          <TokenDisplay label="Access Token (from STS)" token={session.accessToken} />

          <button
            onClick={() => signOut()}
            style={{
              background: '#dc2626',
              color: '#fff',
              border: 'none',
              padding: '10px 20px',
              borderRadius: 6,
              cursor: 'pointer',
              fontSize: 16,
              marginTop: 16,
            }}
          >
            Sign out
          </button>
        </>
      )}

      {status === 'loading' && <p>Loading session...</p>}
    </div>
  )
}

export default function DebugPage() {
  return (
    <SessionProvider>
      <DebugContent />
    </SessionProvider>
  )
}
