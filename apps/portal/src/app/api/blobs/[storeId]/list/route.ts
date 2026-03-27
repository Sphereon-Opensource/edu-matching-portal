import { NextRequest, NextResponse } from 'next/server'

const BLOB_SERVICE_URL = process.env.BLOB_SERVICE_URL ?? 'http://localhost:8081'

/**
 * GET /api/blobs/[storeId]/list?prefix=...&pageToken=...&maxResults=...
 *
 * Proxies blob listing to service-data and transforms the response
 * from IDK format (descriptors[].ref.path) to BlobExplorer format (items[].path).
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ storeId: string }> },
) {
  try {
    const { storeId } = await params
    const { searchParams } = new URL(request.url)
    const prefix = searchParams.get('prefix')
    const pageToken = searchParams.get('pageToken')
    const maxResults = searchParams.get('maxResults') ?? '100'

    const query = new URLSearchParams()
    if (prefix) query.set('prefix', prefix)
    if (pageToken) query.set('pageToken', pageToken)
    query.set('maxResults', maxResults)

    const upstream = await fetch(
      `${BLOB_SERVICE_URL}/api/blob-stores/${storeId}/blobs?${query.toString()}`,
      {
        headers: {
          Accept: 'application/json',
          ...(request.headers.get('authorization')
            ? { Authorization: request.headers.get('authorization')! }
            : {}),
          ...(request.headers.get('x-tenant-id')
            ? { 'X-Tenant-Id': request.headers.get('x-tenant-id')! }
            : {}),
        },
      },
    )

    if (!upstream.ok) {
      return NextResponse.json(
        { error: `Upstream error: ${upstream.status}` },
        { status: upstream.status },
      )
    }

    const data = await upstream.json()

    // Transform IDK ListResult to BlobExplorer ListResultDTO
    const items = (data.descriptors ?? []).map((desc: any) => {
      const rawPath = desc.ref?.path ?? desc.path ?? ''
      const path = decodeURIComponent(rawPath)
      const filename = decodeURIComponent(desc.filename ?? rawPath)
      return {
      path,
      filename,
      sizeBytes: desc.sizeBytes ?? 0,
      contentType: desc.contentType ?? desc.metadata?.contentType,
      lastModified: desc.lastModified,
      createdAt: desc.createdAt ?? desc.lastModified,
      etag: desc.etag,
      metadata: desc.metadata?.custom,
    }})

    return NextResponse.json({
      items,
      commonPrefixes: data.commonPrefixes ?? [],
      nextPageToken: data.nextPageToken,
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : 'Failed to list blobs'
    return NextResponse.json({ error: message }, { status: 502 })
  }
}
