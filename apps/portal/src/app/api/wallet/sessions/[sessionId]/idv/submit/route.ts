import { NextResponse } from 'next/server'
import { authBridgeClient } from '@/lib/services/auth-bridge'

// POST /api/wallet/sessions/:sessionId/idv/submit - Submit user input during IDV
export async function POST(
  request: Request,
  { params }: { params: Promise<{ sessionId: string }> },
) {
  try {
    const { sessionId } = await params
    const body = await request.json()
    const data = await authBridgeClient.submitIdv(sessionId, body)
    return NextResponse.json(data)
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to submit IDV input'
    const status = message.includes(': 4') ? parseInt(message.split(': ')[1]) || 502 : 502
    return NextResponse.json(
      { error: message },
      { status },
    )
  }
}
