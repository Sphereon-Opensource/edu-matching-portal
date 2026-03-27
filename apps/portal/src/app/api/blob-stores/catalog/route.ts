import { NextRequest, NextResponse } from 'next/server'

const BLOB_SERVICE_URL = process.env.BLOB_SERVICE_URL ?? 'http://localhost:8081'

/**
 * GET /api/blob-stores/catalog — Proxy to service-blobstore-camel catalog endpoint.
 * Returns the list of available blob stores with capabilities and UI metadata.
 */
export async function GET(request: NextRequest) {
  try {
    const headers: Record<string, string> = { Accept: 'application/json' }
    const auth = request.headers.get('authorization')
    if (auth) headers['Authorization'] = auth
    const tenantId = request.headers.get('x-tenant-id')
    if (tenantId) headers['X-Tenant-Id'] = tenantId

    const upstream = await fetch(`${BLOB_SERVICE_URL}/api/blob-stores/catalog`, { headers })

    if (!upstream.ok) {
      return NextResponse.json(
        { error: `Upstream error: ${upstream.status}` },
        { status: upstream.status },
      )
    }

    return NextResponse.json(await upstream.json())
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to fetch blob store catalog'
    return NextResponse.json({ error: message }, { status: 502 })
  }
}
