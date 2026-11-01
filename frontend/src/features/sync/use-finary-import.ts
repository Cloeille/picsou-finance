import { useState } from 'react'
import {
  usePreviewFinaryFile,
  usePreviewFinaryApi,
  useImportFinary,
  useExecuteFinaryApiSync,
} from '@/features/sync/hooks'
import { initialFinaryMappings } from '@/features/sync/finary-mappings'
import { getErrorStatus } from '@/lib/errors'
import { ACCOUNT_COLORS } from '@/lib/constants'
import type {
  FinaryPreviewResponse,
  FinaryAccountMapping,
  FinaryImportResultResponse,
  FinaryMappingAction,
} from '@/types/api'

export type FinaryStep = 1 | 2 | 3
export type NewAccountField = keyof NonNullable<FinaryAccountMapping['newAccount']>

interface FinaryImportOptions {
  /** Message shown when a preview, upload or import fails. */
  errorMessage: (err: unknown) => string
  /** Runs once an import has landed, before the results step shows. */
  onImported?: () => void
}

/**
 * State machine behind the Finary login/upload → mapping → results wizard, shared by the Sync
 * tab and the add-account modal. How a session is opened and how a connected user syncs differ
 * per screen, so those stay with the caller; they drive this through `previewApiSync` and
 * `syncWithTotp`.
 */
export function useFinaryImport({ errorMessage, onImported }: FinaryImportOptions) {
  const [step, setStep] = useState<FinaryStep>(1)
  const [isApiSync, setIsApiSync] = useState(false)
  const [previewData, setPreviewData] = useState<FinaryPreviewResponse | null>(null)
  const [mappings, setMappings] = useState<FinaryAccountMapping[]>([])
  const [importResult, setImportResult] = useState<FinaryImportResultResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [totpRequired, setTotpRequired] = useState(false)
  const [totpCode, setTotpCode] = useState('')

  const previewFileMutation = usePreviewFinaryFile()
  const previewApiMutation = usePreviewFinaryApi()
  const importMutation = useImportFinary()
  const executeApiMutation = useExecuteFinaryApiSync()

  function initMappings(preview: FinaryPreviewResponse) {
    setMappings(initialFinaryMappings(preview))
  }

  function onPreviewError(err: unknown) {
    setLoading(false)
    if (getErrorStatus(err) === 403) {
      setTotpRequired(true)
    } else {
      setError(errorMessage(err))
    }
  }

  // Preview with the stored session (a TOTP code when one was asked for); an auto-mapped
  // preview is executed straight away instead of stopping at the mapping step.
  function syncWithTotp() {
    setLoading(true)
    setError(null)
    previewApiMutation.mutate(totpCode || undefined, {
      onSuccess: (data) => {
        setLoading(false)
        setPreviewData(data)
        initMappings(data)
        setIsApiSync(true)
        setTotpRequired(false)
        setTotpCode('')

        if (data.autoMapped && data.suggestedMappings) {
          executeWithMappings(data.fileToken, data.suggestedMappings, true)
        } else {
          setStep(2)
        }
      },
      onError: onPreviewError,
    })
  }

  function previewApiSync() {
    setLoading(true)
    setError(null)
    previewApiMutation.mutate(totpCode || undefined, {
      onSuccess: (data) => {
        setLoading(false)
        setPreviewData(data)
        initMappings(data)
        setIsApiSync(true)
        setTotpRequired(false)
        setStep(2)
      },
      onError: onPreviewError,
    })
  }

  // The mode is a parameter, not read from isApiSync: syncWithTotp calls this in the
  // same tick it calls setIsApiSync(true), when the closure still holds the old value.
  function executeWithMappings(token: string, mappingsToUse: FinaryAccountMapping[], apiSync: boolean) {
    setLoading(true)
    setError(null)
    const onSuccess = (data: FinaryImportResultResponse) => {
      setLoading(false)
      setImportResult(data)
      setStep(3)
      onImported?.()
    }
    const onError = (err: unknown) => {
      setLoading(false)
      setError(errorMessage(err))
    }

    // Each mutation takes a distinct payload shape — branch so the call stays
    // type-safe instead of erasing it with a cast.
    if (apiSync) {
      executeApiMutation.mutate({ syncToken: token, mappings: mappingsToUse }, { onSuccess, onError })
    } else {
      importMutation.mutate({ fileToken: token, mappings: mappingsToUse }, { onSuccess, onError })
    }
  }

  function uploadFile(file: File) {
    setLoading(true)
    setError(null)
    previewFileMutation.mutate(file, {
      onSuccess: (data) => {
        setLoading(false)
        setPreviewData(data)
        initMappings(data)
        setIsApiSync(false)
        setStep(2)
      },
      onError: (err: unknown) => {
        setLoading(false)
        setError(errorMessage(err))
      },
    })
  }

  function updateMapping(index: number, patch: Partial<FinaryAccountMapping>) {
    setMappings((prev) => prev.map((m, i) => (i === index ? { ...m, ...patch } : m)))
  }

  function setMappingAction(index: number, action: FinaryMappingAction) {
    if (action === 'SKIP') {
      updateMapping(index, { action, targetAccountId: undefined, newAccount: undefined })
    } else if (action === 'MAP_EXISTING') {
      updateMapping(index, { action, newAccount: undefined })
    } else {
      const account = previewData?.accounts[index]
      setMappings((prev) => prev.map((m, i): FinaryAccountMapping => {
        if (i !== index) return m
        return {
          ...m,
          action,
          targetAccountId: undefined,
          newAccount: {
            name: m.newAccount?.name ?? account?.finaryName ?? '',
            type: m.newAccount?.type ?? account?.suggestedType ?? 'OTHER',
            provider: m.newAccount?.provider ?? account?.finaryInstitution ?? 'Finary',
            currency: m.newAccount?.currency ?? account?.nativeCurrency ?? 'EUR',
            color: m.newAccount?.color ?? ACCOUNT_COLORS[0],
          },
        }
      }))
    }
  }

  function updateNewAccountField(index: number, field: NewAccountField, value: string) {
    setMappings((prev) => prev.map((m, i) => {
      if (i !== index || !m.newAccount) return m
      return { ...m, newAccount: { ...m.newAccount, [field]: value } }
    }))
  }

  function importMapped() {
    if (!previewData) return
    executeWithMappings(previewData.fileToken, mappings, isApiSync)
  }

  function backToStart() {
    setStep(1)
    setPreviewData(null)
  }

  function reset() {
    setStep(1)
    setPreviewData(null)
    setMappings([])
    setImportResult(null)
    setError(null)
    setTotpRequired(false)
    setTotpCode('')
    setIsApiSync(false)
  }

  return {
    step,
    previewData,
    mappings,
    importResult,
    loading,
    setLoading,
    error,
    setError,
    totpRequired,
    setTotpRequired,
    totpCode,
    setTotpCode,
    hasSkipAll: mappings.every((m) => m.action === 'SKIP'),
    syncWithTotp,
    previewApiSync,
    uploadFile,
    setMappingAction,
    updateMapping,
    updateNewAccountField,
    importMapped,
    backToStart,
    reset,
  }
}
