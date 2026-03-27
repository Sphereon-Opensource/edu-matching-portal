import { NextRequest, NextResponse } from 'next/server'

const BLOB_SERVICE_URL = process.env.BLOB_SERVICE_URL ?? 'http://localhost:8081'

const MIME_TYPES: Record<string, string> = {
  '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript',
  '.json': 'application/json', '.xml': 'application/xml', '.txt': 'text/plain',
  '.csv': 'text/csv', '.md': 'text/markdown', '.svg': 'image/svg+xml',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
  '.gif': 'image/gif', '.webp': 'image/webp', '.ico': 'image/x-icon',
  '.pdf': 'application/pdf', '.zip': 'application/zip',
}

function inferContentType(filename: string): string {
  const ext = filename.substring(filename.lastIndexOf('.')).toLowerCase()
  return MIME_TYPES[ext] ?? 'application/octet-stream'
}

type RouteParams = { params: Promise<{ storeId: string; path: string[] }> }

function buildUpstreamUrl(storeId: string, path: string[]): string {
  const blobPath = path.join('/')
  return `${BLOB_SERVICE_URL}/api/blob-stores/${storeId}/blobs/${blobPath}`
}

/** Forward auth and tenant headers from the browser request to the upstream service. */
function forwardHeaders(request: NextRequest): Record<string, string> {
  const headers: Record<string, string> = {}
  const auth = request.headers.get('authorization')
  if (auth) headers['Authorization'] = auth
  const tenantId = request.headers.get('x-tenant-id')
  if (tenantId) headers['X-Tenant-Id'] = tenantId
  return headers
}

/**
 * GET /api/blobs/[storeId]/[...path] — Get blob content as binary download.
 * Calls the /content endpoint on the blob service and streams raw bytes back.
 */
export async function GET(request: NextRequest, { params }: RouteParams) {
  try {
    const { storeId, path } = await params
    const blobPath = path.join('/')
    const url = buildUpstreamUrl(storeId, path)

    const upstream = await fetch(url, {
      headers: { Accept: 'application/json', ...forwardHeaders(request) },
    })

    if (!upstream.ok) {
      return NextResponse.json(
        { error: `Upstream error: ${upstream.status}` },
        { status: upstream.status },
      )
    }

    const data = await upstream.json()
    const bytes = Buffer.from(data.dataBase64 ?? '', 'base64')
    const filename = decodeURIComponent(blobPath.split('/').pop() ?? 'download')
    const contentType = data.info?.contentType ?? data.descriptor?.contentType ?? inferContentType(filename)

    return new NextResponse(bytes, {
      status: 200,
      headers: {
        'Content-Type': contentType,
        'Content-Disposition': `inline; filename="${filename}"`,
      },
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to get blob'
    return NextResponse.json({ error: message }, { status: 502 })
  }
}

/**
 * PUT /api/blobs/[storeId]/[...path] — Upload blob.
 * Accepts raw file upload, encodes to base64, forwards to blob service.
 */
export async function PUT(request: NextRequest, { params }: RouteParams) {
  try {
    const { storeId, path } = await params
    const blobPath = path.join('/')
    const url = buildUpstreamUrl(storeId, path)
    const body = await request.arrayBuffer()
    const dataBase64 = Buffer.from(body).toString('base64')
    const contentType = request.headers.get('content-type') ?? 'application/octet-stream'

    const upstream = await fetch(url, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...forwardHeaders(request) },
      body: JSON.stringify({
        path: blobPath,
        dataBase64,
        metadata: { contentType },
      }),
    })

    if (!upstream.ok) {
      const err = await upstream.text()
      return NextResponse.json(
        { error: `Upstream error: ${upstream.status}`, detail: err },
        { status: upstream.status },
      )
    }

    const data = await upstream.json()

    return NextResponse.json({
      path: data.ref?.path ?? data.path ?? blobPath,
      filename: data.filename ?? blobPath.split('/').pop(),
      sizeBytes: data.sizeBytes ?? 0,
      contentType: data.contentType ?? contentType,
      lastModified: data.lastModified,
      createdAt: data.createdAt,
    }, { status: 201 })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to store blob'
    return NextResponse.json({ error: message }, { status: 502 })
  }
}

/**
 * DELETE /api/blobs/[storeId]/[...path] — Delete blob.
 */
export async function DELETE(request: NextRequest, { params }: RouteParams) {
  try {
    const { storeId, path } = await params
    const url = buildUpstreamUrl(storeId, path)

    const upstream = await fetch(url, {
      method: 'DELETE',
      headers: forwardHeaders(request),
    })

    if (!upstream.ok) {
      return NextResponse.json(
        { error: `Upstream error: ${upstream.status}` },
        { status: upstream.status },
      )
    }

    return NextResponse.json({ deleted: true })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to delete blob'
    return NextResponse.json({ error: message }, { status: 502 })
  }
}

/**
 * POST /api/blobs/[storeId]/[...path] — Copy, move, or stat.
 * Action determined by ?action= query parameter.
 */
export async function POST(request: NextRequest, { params }: RouteParams) {
  try {
    const { storeId, path } = await params
    const blobPath = path.join('/')
    const { searchParams } = new URL(request.url)
    const action = searchParams.get('action')

    if (action === 'stat') {
      const url = `${BLOB_SERVICE_URL}/api/blob-stores/${storeId}/blobs/${blobPath}/stat`
      const upstream = await fetch(url, { headers: forwardHeaders(request) })
      if (!upstream.ok) {
        return NextResponse.json({ error: `Upstream error: ${upstream.status}` }, { status: upstream.status })
      }
      return NextResponse.json(await upstream.json())
    }

    if (action === 'copy' || action === 'move') {
      const destination = searchParams.get('destination')
      if (!destination) {
        return NextResponse.json({ error: 'Missing destination parameter' }, { status: 400 })
      }
      const url = `${BLOB_SERVICE_URL}/api/blob-stores/${storeId}/blobs/${blobPath}/${action}`
      const upstream = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...forwardHeaders(request) },
        body: JSON.stringify({ destination }),
      })
      if (!upstream.ok) {
        return NextResponse.json({ error: `Upstream error: ${upstream.status}` }, { status: upstream.status })
      }
      return NextResponse.json(await upstream.json())
    }

    return NextResponse.json({ error: `Unknown action: ${action}` }, { status: 400 })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to perform action'
    return NextResponse.json({ error: message }, { status: 502 })
  }
}
