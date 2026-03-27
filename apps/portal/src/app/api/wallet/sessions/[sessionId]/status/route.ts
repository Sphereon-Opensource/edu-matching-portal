import { NextResponse } from 'next/server'
import { authBridgeClient } from '@/lib/services/auth-bridge'

// GET /api/wallet/sessions/:sessionId/status - Poll session status
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ sessionId: string }> },
) {
  try {
    const { sessionId } = await params
    const status = await authBridgeClient.getSessionStatus(sessionId)
    return NextResponse.json(status)
  } catch (err) {
    return NextResponse.json(
      { error: 'Failed to get session status' },
      { status: 502 },
    )
  }
}
