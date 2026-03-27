import { NextRequest, NextResponse } from 'next/server'
import { authBridgeClient } from '@/lib/services/auth-bridge'

// POST /api/wallet/sessions - Create OID4VP session
export async function POST(request: NextRequest) {
  try {
    let forceReconciliation = false
    try {
      const body = await request.json()
      forceReconciliation = body?.forceReconciliation === true
    } catch {
      // empty body is fine
    }
    const session = await authBridgeClient.createSession(
      forceReconciliation ? { forceReconciliation: true } : undefined
    )
    return NextResponse.json(session)
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to create wallet session'
    return NextResponse.json(
      { error: message },
      { status: 502 },
    )
  }
}
