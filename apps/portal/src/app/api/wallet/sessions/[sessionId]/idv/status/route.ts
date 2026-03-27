import { NextResponse } from 'next/server'
import { authBridgeClient } from '@/lib/services/auth-bridge'

// GET /api/wallet/sessions/:sessionId/idv/status - Poll IDV status
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ sessionId: string }> },
) {
  try {
    const { sessionId } = await params
    const status = await authBridgeClient.getIdvStatus(sessionId)
    return NextResponse.json(status)
  } catch (err) {
    return NextResponse.json(
      { error: 'Failed to get IDV status' },
      { status: 502 },
    )
  }
}
