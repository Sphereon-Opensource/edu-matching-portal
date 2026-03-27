import { NextResponse } from 'next/server'
import { authBridgeClient } from '@/lib/services/auth-bridge'

// POST /api/wallet/sessions/:sessionId/complete - Complete wallet auth
export async function POST(
  _request: Request,
  { params }: { params: Promise<{ sessionId: string }> },
) {
  try {
    const { sessionId } = await params
    // Complete the OID4VP session on the auth bridge side.
    // After this, the client calls signIn('sts-wallet') to get STS tokens via BFF.
    const result = await authBridgeClient.completeAuth(sessionId)

    if (result.idvRequired) {
      return NextResponse.json({
        status: 'IDV_REQUIRED',
        sessionId,
        idvMethod: result.idvMethod,
        idvSteps: result.idvSteps,
        message: result.message,
      }, { status: 202 })
    }

    return NextResponse.json({
      success: true,
      userId: result.userId,
      sessionId,
      claims: result.claims,
      isNewUser: result.isNewUser,
      assurance: result.assurance,
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to complete authentication'
    return NextResponse.json(
      { error: message },
      { status: 502 },
    )
  }
}
