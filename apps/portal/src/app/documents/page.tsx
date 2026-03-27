'use client'

import { BlobExplorer, type ListResultDTO, type BlobDescriptorDTO } from '@sphereon/ui-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'next/navigation'
import { useTranslations } from 'next-intl'
import { useBlobDelete, useBlobUpload } from '@/queries/blob-store'

interface CatalogItem {
  storeId: string
  kind: string
  label: string
  description?: string
  order: number
  readOnly: boolean
  capabilities: {
    supportsListing: boolean
    supportsMetadata: boolean
    supportsCopy: boolean
    supportsMove: boolean
    supportsTempUrls: boolean
    supportsBulkDelete: boolean
    supportsEtag: boolean
    supportsFolders: boolean
  }
}

interface Catalog {
  defaultStoreId: string | null
  stores: CatalogItem[]
}

/**
 * Documents page — catalog-driven blob explorer.
 *
 * On mount, fetches the store catalog from service-blobstore-camel via the BFF.
 * The active store is selected from: ?store= query param > catalog default > first store.
 * Capabilities and readOnly are derived from the catalog, not hardcoded.
 */
export default function DocumentsPage() {
  const t = useTranslations('documents')
  const searchParams = useSearchParams()
  const storeParam = searchParams.get('store')

  const [catalog, setCatalog] = useState<Catalog | null>(null)
  const [activeStoreId, setActiveStoreId] = useState<string | null>(storeParam)
  const [serviceError, setServiceError] = useState<string | null>(null)

  // Sync activeStoreId when URL ?store= param changes (sidebar navigation)
  useEffect(() => {
    if (storeParam) {
      setActiveStoreId(storeParam)
    } else if (catalog) {
      setActiveStoreId(catalog.defaultStoreId || catalog.stores[0]?.storeId || null)
    }
  }, [storeParam]) // eslint-disable-line react-hooks/exhaustive-deps

  // Fetch catalog on mount
  useEffect(() => {
    fetch('/api/blob-stores/catalog')
      .then((res) => {
        if (!res.ok) {
          setServiceError(t('serviceUnavailable'))
          return null
        }
        return res.json()
      })
      .then((data: Catalog | null) => {
        if (!data) return
        setServiceError(null)
        setCatalog(data)
        if (!activeStoreId) {
          setActiveStoreId(storeParam || data.defaultStoreId || data.stores[0]?.storeId || null)
        }
      })
      .catch(() => {
        setServiceError(t('serviceUnavailable'))
      })
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const storeId = activeStoreId ?? 'default'
  const activeStore = catalog?.stores.find((s) => s.storeId === storeId)

  const deleteMutation = useBlobDelete(storeId)
  const uploadMutation = useBlobUpload(storeId)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [currentPrefix, setCurrentPrefix] = useState<string | null>(null)
  const [refreshKey, setRefreshKey] = useState(0)

  const fetchBlobs = useCallback(
    async (prefix: string | null, pageToken: string | null): Promise<ListResultDTO> => {
      setCurrentPrefix(prefix)

      const params = new URLSearchParams()
      if (prefix) params.set('prefix', prefix)
      if (pageToken) params.set('pageToken', pageToken)
      const res = await fetch(`/api/blobs/${storeId}/list?${params.toString()}`)
      if (!res.ok) {
        throw new Error(t('serviceUnavailable'))
      }
      return res.json()
    },
    [storeId],
  )

  const handleDelete = useCallback(
    async (blob: BlobDescriptorDTO): Promise<boolean> => {
      await deleteMutation.mutateAsync(blob.path)
      setRefreshKey((k) => k + 1)
      return true
    },
    [deleteMutation],
  )

  const handleUpload = useCallback(
    async (path: string, file: File): Promise<BlobDescriptorDTO> => {
      const result = await uploadMutation.mutateAsync({ path, file })
      setRefreshKey((k) => k + 1)
      return result
    },
    [uploadMutation],
  )

  const handleView = useCallback((blob: BlobDescriptorDTO) => {
    window.open(`/api/blobs/${storeId}/${blob.path}`, '_blank')
  }, [storeId])

  const handleDownload = useCallback((blob: BlobDescriptorDTO) => {
    const a = document.createElement('a')
    a.href = `/api/blobs/${storeId}/${blob.path}`
    a.download = blob.filename ?? blob.path.split('/').pop() ?? 'download'
    a.click()
  }, [storeId])

  const handleUploadClick = useCallback(() => {
    fileInputRef.current?.click()
  }, [])

  const handleFileSelected = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const files = e.target.files
      if (!files || files.length === 0) return

      for (let i = 0; i < files.length; i++) {
        const file = files[i]
        const prefix = currentPrefix ? currentPrefix.replace(/\/+$/, '') + '/' : ''
        const path = prefix + file.name
        handleUpload(path, file).catch((err) => {
          console.error('Upload failed:', err)
        })
      }

      e.target.value = ''
    },
    [handleUpload, currentPrefix],
  )

  // Derive capabilities from catalog metadata
  const capabilities = activeStore
    ? {
        supportsDelete: !activeStore.readOnly,
        supportsUpload: !activeStore.readOnly,
        supportsCopy: activeStore.capabilities.supportsCopy,
        supportsMove: activeStore.capabilities.supportsMove,
        supportsTempUrls: activeStore.capabilities.supportsTempUrls,
      }
    : { supportsDelete: true, supportsUpload: true, supportsCopy: false, supportsMove: false, supportsTempUrls: false }

  if (serviceError) {
    return (
      <div style={{ height: 'calc(100vh - 120px)' }}>
        <h1>{t('title')}</h1>
        <p style={{ color: 'var(--color-secondary)', marginTop: 'var(--spacing-md)' }}>{serviceError}</p>
      </div>
    )
  }

  if (!catalog) {
    return (
      <div style={{ height: 'calc(100vh - 120px)' }}>
        <h1>{t('title')}</h1>
      </div>
    )
  }

  // Single-store mode when navigated via sidebar sub-item (?store=)
  const isSingleStoreMode = !!storeParam

  return (
    <div style={{ height: 'calc(100vh - 120px)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 16 }}>
        <h1>{t('title')}</h1>
        {isSingleStoreMode ? (
          <span style={{ fontSize: '0.95rem', color: 'var(--color-secondary)' }}>
            {activeStore?.label ?? storeId}
            {activeStore?.readOnly ? ` (read-only)` : ''}
          </span>
        ) : catalog.stores.length > 1 ? (
          <select
            value={storeId}
            onChange={(e) => setActiveStoreId(e.target.value)}
            style={{ padding: '4px 8px' }}
          >
            {catalog.stores.map((s) => (
              <option key={s.storeId} value={s.storeId}>
                {s.label}{s.readOnly ? ` (read-only)` : ''}
              </option>
            ))}
          </select>
        ) : (
          <span style={{ fontSize: '0.95rem', color: 'var(--color-secondary)' }}>
            {activeStore?.label ?? storeId}
          </span>
        )}
      </div>
      <input
        ref={fileInputRef}
        type="file"
        multiple
        style={{ display: 'none' }}
        onChange={handleFileSelected}
      />
      <BlobExplorer
        key={`${storeId}-${refreshKey}`}
        fetchBlobs={fetchBlobs}
        capabilities={capabilities}
        sidebar={{ defaultMode: activeStore?.capabilities.supportsFolders ? 'visible' : 'hidden' }}
        labels={{
          allFiles: t('allFiles'),
          name: t('colName'),
          size: t('colSize'),
          modified: t('colModified'),
          created: t('colCreated'),
          type: t('colType'),
          upload: t('upload'),
          noFiles: t('noFiles'),
          loading: t('loading'),
          loadMore: t('loadMore'),
          delete: t('delete'),
          cancel: t('cancel'),
        }}
        onDeleteBlob={activeStore?.readOnly ? undefined : handleDelete}
        onUploadBlob={activeStore?.readOnly ? undefined : handleUpload}
        onUploadClick={activeStore?.readOnly ? undefined : handleUploadClick}
        onViewBlob={handleView}
        onDownloadBlob={handleDownload}
      />
    </div>
  )
}
