import { NextResponse } from 'next/server'
import { auth } from '@/lib/auth'
import { env } from '@/lib/config/env'

export async function GET() {
  const session = await auth()
  if (!session?.user) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  // Use federated_subject as the internal identity ID for the auth-bridge lookup
  const internalIdentityId = session.user.federated_subject
  if (!internalIdentityId) {
    return NextResponse.json([], { status: 200 })
  }

  try {
    const res = await fetch(
      `${env.AUTH_BRIDGE_URL}/internal/bindings/${encodeURIComponent(internalIdentityId)}`,
      { headers: { 'Content-Type': 'application/json' } },
    )

    if (!res.ok) {
      return NextResponse.json([], { status: 200 })
    }

    const bindings = await res.json()
    return NextResponse.json(bindings)
  } catch {
    return NextResponse.json([], { status: 200 })
  }
}
