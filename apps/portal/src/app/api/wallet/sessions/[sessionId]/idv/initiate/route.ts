import { NextResponse } from 'next/server'
import { authBridgeClient } from '@/lib/services/auth-bridge'

// POST /api/wallet/sessions/:sessionId/idv/initiate - Start reconciliation
// The OIDC redirect_uri points directly at the auth-bridge's static callback path.
// Session correlation is done via the OIDC state parameter — no session ID in the URL.
// After callback, the auth-bridge redirects the browser back to the portal frontend
// (configured via the auth-bridge's frontend-url setting).
export async function POST(
  request: Request,
  { params }: { params: Promise<{ sessionId: string }> },
) {
  try {
    const { sessionId } = await params
    const result = await authBridgeClient.initiateIdv(sessionId)
    return NextResponse.json(result)
  } catch (err) {
    return NextResponse.json(
      { error: 'Failed to initiate identity verification' },
      { status: 502 },
    )
  }
}
