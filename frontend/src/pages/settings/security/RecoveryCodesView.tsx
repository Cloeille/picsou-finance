import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Copy, Check, DownloadSimple } from '@phosphor-icons/react'

/**
 * Displays a fresh set of recovery codes once. Codes are unmasked, formatted
 * in a 2-column grid (responsive to single column on narrow screens), with
 * Copy-to-clipboard and Download-as-txt actions. The user must positively
 * confirm via {@code onSaved} before this view goes away — recovery codes
 * are shown ONCE and never recoverable.
 */
export function RecoveryCodesView({
  codes,
  onSaved,
  saveLabel,
}: {
  codes: string[]
  onSaved: () => void
  saveLabel: string
}) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const [acknowledged, setAcknowledged] = useState(false)

  const handleCopy = async () => {
    await navigator.clipboard.writeText(codes.join('\n'))
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const handleDownload = () => {
    const blob = new Blob([codes.join('\n') + '\n'], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = 'picsou-recovery-codes.txt'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 rounded-lg border bg-muted/30 p-3">
        {codes.map(code => (
          <code
            key={code}
            className="font-mono text-sm tracking-wider text-center py-1.5 px-2 rounded bg-background border"
          >
            {code}
          </code>
        ))}
      </div>

      <div className="flex flex-col sm:flex-row gap-2">
        <Button type="button" variant="outline" size="sm" onClick={handleCopy} className="flex-1">
          {copied ? <Check size={14} className=".5" /> : <Copy size={14} className=".5" />}
          {copied ? t('settings.mfaCopied') : t('settings.mfaCopy')}
        </Button>
        <Button type="button" variant="outline" size="sm" onClick={handleDownload} className="flex-1">
          <DownloadSimple size={14} className=".5" />
          {t('settings.mfaDownload')}
        </Button>
      </div>

      <label className="flex items-start gap-2 cursor-pointer">
        <input
          type="checkbox"
          checked={acknowledged}
          onChange={e => setAcknowledged(e.target.checked)}
          className="mt-0.5 h-4 w-4 rounded"
        />
        <span className="text-sm">{t('settings.mfaEnrollStep4Confirm')}</span>
      </label>

      <Button type="button" disabled={!acknowledged} onClick={onSaved} className="w-full">
        {saveLabel}
      </Button>
    </div>
  )
}
