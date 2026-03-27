import { env } from '@/lib/config/env'
import { NextResponse } from 'next/server'

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 3000)

    const res = await fetch(
      `${env.STS_ISSUER_URL}/.well-known/openid-configuration`,
      { signal: controller.signal },
    )
    clearTimeout(timeout)

    if (!res.ok) {
      return NextResponse.json(
        { status: 'error', message: `STS returned ${res.status}` },
        { status: 502 },
      )
    }

    const data = await res.json()
    return NextResponse.json({
      status: 'ok',
      issuer: data.issuer,
    })
  } catch (e: any) {
    const message =
      e?.name === 'AbortError'
        ? 'STS connection timed out'
        : `STS unreachable: ${e?.message ?? 'unknown error'}`

    return NextResponse.json({ status: 'error', message }, { status: 502 })
  }
}
