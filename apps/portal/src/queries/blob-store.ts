import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { BlobDescriptorDTO, ListResultDTO } from '@sphereon/ui-react'

/**
 * TanStack Query hook for paginated blob listing.
 */
export function useBlobList(storeId: string, prefix?: string | null) {
  return useInfiniteQuery<ListResultDTO>({
    queryKey: ['blobs', storeId, prefix],
    queryFn: async ({ pageParam }) => {
      const params = new URLSearchParams()
      if (prefix) params.set('prefix', prefix)
      if (pageParam) params.set('pageToken', pageParam as string)
      const res = await fetch(`/api/blobs/${storeId}/list?${params.toString()}`)
      if (!res.ok) throw new Error('Failed to list blobs')
      return res.json()
    },
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextPageToken ?? undefined,
  })
}

/**
 * Mutation hook for deleting a blob.
 */
export function useBlobDelete(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (path: string) => {
      const res = await fetch(`/api/blobs/${storeId}/${path}`, { method: 'DELETE' })
      if (!res.ok) throw new Error('Failed to delete blob')
      return res.json()
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['blobs', storeId] })
    },
  })
}

/**
 * Mutation hook for uploading a blob.
 */
export function useBlobUpload(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ path, file }: { path: string; file: File }) => {
      const res = await fetch(`/api/blobs/${storeId}/${path}`, {
        method: 'PUT',
        headers: { 'Content-Type': file.type || 'application/octet-stream' },
        body: file,
      })
      if (!res.ok) throw new Error('Failed to upload blob')
      return res.json() as Promise<BlobDescriptorDTO>
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['blobs', storeId] })
    },
  })
}

/**
 * Mutation hook for copying a blob.
 */
export function useBlobCopy(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ source, destination }: { source: string; destination: string }) => {
      const res = await fetch(`/api/blobs/${storeId}/${source}?action=copy&destination=${encodeURIComponent(destination)}`, {
        method: 'PUT',
      })
      if (!res.ok) throw new Error('Failed to copy blob')
      return res.json() as Promise<BlobDescriptorDTO>
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['blobs', storeId] })
    },
  })
}

/**
 * Mutation hook for moving a blob.
 */
export function useBlobMove(storeId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ source, destination }: { source: string; destination: string }) => {
      const res = await fetch(`/api/blobs/${storeId}/${source}?action=move&destination=${encodeURIComponent(destination)}`, {
        method: 'PUT',
      })
      if (!res.ok) throw new Error('Failed to move blob')
      return res.json() as Promise<BlobDescriptorDTO>
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['blobs', storeId] })
    },
  })
}

/**
 * Mutation hook for creating a temporary URL.
 */
export function useBlobTempUrl(storeId: string) {
  return useMutation({
    mutationFn: async (path: string) => {
      const res = await fetch(`/api/blobs/${storeId}/${path}?action=tempUrl`)
      if (!res.ok) throw new Error('Failed to create temp URL')
      const data = await res.json()
      return data.url as string
    },
  })
}
