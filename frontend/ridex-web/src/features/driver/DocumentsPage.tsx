import { Download, FileText, Trash2, Upload } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { api } from '@/api/client'
import { toFriendlyError } from '@/api/errors'
import type { DocumentType, DriverDocument } from '@/api/types'
import { PageHeader } from '@/components/brand/PageHeader'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { DOCUMENT_LABEL, saveBlob } from '@/lib/documents'
import { formatDateTime } from '@/lib/format'

const TYPES = Object.keys(DOCUMENT_LABEL) as DocumentType[]

export function DocumentsPage() {
  const [documents, setDocuments] = useState<DriverDocument[]>([])
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState<DocumentType | null>(null)
  const picker = useRef<HTMLInputElement>(null)
  const pendingType = useRef<DocumentType | null>(null)

  const reload = useCallback(async () => {
    try {
      const { data } = await api.get<DriverDocument[]>('/api/drivers/me/documents')
      setDocuments(data)
    } catch (err) {
      setError(toFriendlyError(err).message)
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial load
    void reload()
  }, [reload])

  function choose(type: DocumentType) {
    pendingType.current = type
    picker.current?.click()
  }

  async function onFile(file: File | undefined) {
    const type = pendingType.current
    if (picker.current) picker.current.value = ''
    if (!file || !type) return
    if (file.size > 5 * 1024 * 1024) {
      toast.error('That file is over 5 MB')
      return
    }
    setUploading(type)
    try {
      const form = new FormData()
      form.append('type', type)
      form.append('file', file)
      // Let the browser set the multipart boundary itself
      await api.post('/api/drivers/me/documents', form, { headers: { 'Content-Type': undefined } })
      toast.success(`${DOCUMENT_LABEL[type]} uploaded`)
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    } finally {
      setUploading(null)
    }
  }

  async function download(doc: DriverDocument) {
    try {
      const { data } = await api.get<Blob>(`/api/drivers/me/documents/${doc.id}/file`, { responseType: 'blob' })
      saveBlob(data, doc.originalFilename)
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  async function remove(doc: DriverDocument) {
    try {
      await api.delete(`/api/drivers/me/documents/${doc.id}`)
      toast.success('Document deleted')
      await reload()
    } catch (err) {
      toast.error(toFriendlyError(err).message)
    }
  }

  return (
    <>
      <PageHeader
        eyebrow="Driver"
        title="Documents"
        description="PDF, JPG or PNG, up to 5 MB each. Uploading a type again replaces the old file."
      />
      {error && <Alert kind="error" className="mb-4">{error}</Alert>}
      <input
        ref={picker}
        type="file"
        accept="application/pdf,image/jpeg,image/png"
        className="sr-only"
        aria-label="Choose a file"
        tabIndex={-1}
        onChange={(e) => void onFile(e.target.files?.[0])}
      />

      <div className="grid gap-4 sm:grid-cols-2">
        {TYPES.map((type) => {
          const doc = documents.find((d) => d.type === type)
          return (
            <Card key={type}>
              <CardContent className="flex flex-col gap-3">
                <p className="flex items-center gap-2 font-semibold text-fg">
                  <FileText className="size-4 text-accent" aria-hidden /> {DOCUMENT_LABEL[type]}
                </p>
                {doc ? (
                  <p className="text-sm text-fg-secondary">
                    {doc.originalFilename}
                    <span className="block text-fg-muted">{formatDateTime(doc.uploadedAt)} · {(doc.sizeBytes / 1024).toFixed(0)} KB</span>
                  </p>
                ) : (
                  <p className="text-sm text-fg-muted">Not uploaded yet</p>
                )}
                <div className="flex flex-wrap gap-2">
                  <Button size="sm" variant={doc ? 'secondary' : 'primary'} loading={uploading === type} onClick={() => choose(type)}>
                    <Upload className="size-4" aria-hidden /> {doc ? 'Replace' : 'Upload'}
                  </Button>
                  {doc && (
                    <>
                      <Button size="sm" variant="ghost" onClick={() => download(doc)}>
                        <Download className="size-4" aria-hidden /> Download
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => remove(doc)} aria-label={`Delete ${DOCUMENT_LABEL[type]}`}>
                        <Trash2 className="size-4" aria-hidden />
                      </Button>
                    </>
                  )}
                </div>
              </CardContent>
            </Card>
          )
        })}
      </div>
    </>
  )
}
