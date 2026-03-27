import { NextResponse } from 'next/server'
import { auth } from '@/lib/auth'
import { env } from '@/lib/config/env'

export async function DELETE(
  _req: Request,
  { params }: { params: Promise<{ bindingId: string }> },
) {
  const session = await auth()
  if (!session?.user) {
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  const { bindingId } = await params
  const internalIdentityId = session.user.federated_subject
  if (!internalIdentityId) {
    return NextResponse.json({ error: 'No identity' }, { status: 400 })
  }

  try {
    const res = await fetch(
      `${env.AUTH_BRIDGE_URL}/internal/bindings/${encodeURIComponent(internalIdentityId)}/${encodeURIComponent(bindingId)}`,
      { method: 'DELETE', headers: { 'Content-Type': 'application/json' } },
    )

    if (!res.ok) {
      return NextResponse.json({ error: 'Failed to revoke' }, { status: res.status })
    }

    return NextResponse.json(await res.json())
  } catch {
    return NextResponse.json({ error: 'Service unavailable' }, { status: 503 })
  }
}
