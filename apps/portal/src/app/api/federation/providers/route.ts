import { NextResponse } from 'next/server'
import { env } from '@/lib/config/env'

// GET /api/federation/providers — proxy to STS /federation/providers
export async function GET() {
  try {
    // Server-side fetch — use the docker-internal URL so the call works from inside the
    // portal container without depending on the browser-facing issuer URL being reachable.
    const res = await fetch(`${env.STS_INTERNAL_URL}/federation/providers`, {
      headers: { Accept: 'application/json' },
    })

    if (!res.ok) {
      return NextResponse.json([], { status: 200 })
    }

    const providers = await res.json()
    return NextResponse.json(providers)
  } catch {
    // STS unavailable — return empty list so the UI falls back to default
    return NextResponse.json([], { status: 200 })
  }
}
