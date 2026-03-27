import { env } from '@/lib/config/env'
import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 3000)
    const res = await fetch(`${env.AUTH_BRIDGE_URL}/health`, {
      signal: controller.signal,
    })
    clearTimeout(timeout)
    if (!res.ok) {
      return NextResponse.json(
        { status: 'error', message: `Auth bridge returned ${res.status}` },
        { status: 502 },
      )
    }
    return NextResponse.json({ status: 'ok' })
  } catch (e: any) {
    const message =
      e?.name === 'AbortError'
        ? 'Auth bridge connection timed out'
        : `Auth bridge unreachable: ${e?.message ?? 'unknown error'}`
    return NextResponse.json({ status: 'error', message }, { status: 502 })
  }
}
